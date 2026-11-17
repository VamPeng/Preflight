package gitviz

import gitviz.git.*
import gitviz.logic.*
import gitviz.platform.*
import gitviz.service.*
import gitviz.ui.*
import gitviz.ui.changes.*
import gitviz.ui.history.*
import gitviz.ui.tools.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", AppIcon.name)
    val initial = args.firstOrNull()?.let(::File)
        ?: Settings.recentRepos.firstOrNull()?.let(::File)
        ?: File(System.getProperty("user.dir"))
    System.setProperty(
        "apple.awt.application.appearance",
        if (Palette.isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
    )
    val savedSize = initialWindowSize()
    val size = DpSize(savedSize.width.dp, savedSize.height.dp)
    var requestClose: (() -> Unit)? = null
    val root: @Composable FrameWindowScope.(() -> Unit) -> Unit = { exit ->
        RememberWindowSize(window)
        GitVizTheme {
            Surface(
                Modifier.fillMaxSize().clip(RoundedCornerShape(if (isMac) 16.dp else 0.dp)),
                color = Palette.Ground,
                contentColor = Palette.Ink,
            ) {
                App(initial, reportInitialError = args.isNotEmpty(), onExit = exit, registerClose = { requestClose = it })
            }
        }
    }
    val appIcon = AppIcon.load()
    // 开发运行和安装版使用各自的图标，Dock 和任务栏里可以同时辨认。
    if (appIcon != null && java.awt.Taskbar.isTaskbarSupported() &&
        java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)
    ) {
        java.awt.Taskbar.getTaskbar().iconImage = appIcon
    }
    application {
        if (isMac) {
            GlassWindow(AppIcon.name, size, onClose = { requestClose?.invoke() ?: exitApplication() }) { root { exitApplication() } }
        } else {
            Window(
                onCloseRequest = { requestClose?.invoke() ?: exitApplication() },
                title = AppIcon.name,
                icon = appIcon?.let { remember { BitmapPainter(it.toComposeImageBitmap()) } },
                state = rememberWindowState(size = size),
            ) {
                root { exitApplication() }
            }
        }
    }
}

/** 开发安装包显式标记 dev；Gradle 和 IDE 直接运行时没有 jpackage 版本号，也视为开发版。 */
private object AppIcon {
    private val isDev = System.getProperty("gitviz.dev")?.toBoolean()
        ?: (System.getProperty("jpackage.app-version") == null)
    val name = if (isDev) "GitViz Dev" else "GitViz"

    fun load(): java.awt.image.BufferedImage? =
        AppIcon::class.java.getResourceAsStream(if (isDev) "/icon-dev.png" else "/icon.png")
            ?.use { javax.imageio.ImageIO.read(it) }
}

/** 启动时加载画面至少显示多久。仓库打开得太快时，画面一闪就切走，看着像闪烁。 */
private const val STARTUP_MIN_SHOW_MS = 600L

// TODO 减少主页首次渲染时的主线程类加载；若采用离屏预热，需确保不干扰真实窗口。

/** 监听不了文件变化时，多久读一次工作区状态。 */
private const val POLL_INTERVAL_MS = 3000L

/** 监听到变化后，两次读工作区状态之间至少隔多久。 */
private const val MIN_RELOAD_GAP_MS = 1500L

/** 监听期间没有变化时，多久兜底读一次。 */
private const val FALLBACK_RELOAD_MS = 30_000L

private enum class MainView { HISTORY, CHANGES, FILES, STASH, CONFLICT, SETTINGS, REFLOG, INSIGHTS, COMPARE, AI }

/** 刚打开仓库时默认选中 HEAD；HEAD 不在已加载范围内就选第一个真正的提交，最后才是储藏。 */
internal fun defaultSelection(commits: List<CommitInfo>, headId: String?): CommitInfo? =
    commits.firstOrNull { it.id == headId } ?: commits.firstOrNull { !it.isStash } ?: commits.firstOrNull()

/** 打开的仓库和已加载的那部分历史。不可变，每加载一页换一个新对象。 */
internal class RepoState(
    val repo: GitRepo,
    val result: LoadResult,
    val rows: List<GraphRow>,
    val headId: String?,
    val history: CommitPager? = null,
    // 排出 rows 的那个 GraphBuilder，下一页接着它往下排
    private val layout: GraphBuilder? = null,
) {
    val branchColors = BranchColors(result.refs)
    val indexById: Map<String, Int> = result.commits.withIndex().associate { (i, c) -> c.id to i }

    /** 再读 count 个提交。会阻塞，在 IO 线程调用。 */
    fun loadMore(count: Int): RepoState {
        val pager = checkNotNull(history)
        val next = pager.load(count)
        val from = next.appendedFrom
        // 前面的没动，而且 layout 正好排到这里，就只排新的；否则从头排
        if (layout != null && from > 0 && from == result.commits.size && layout.size == from) {
            return RepoState(repo, next, layout.add(next.commits.subList(from, next.commits.size)), headId, pager, layout)
        }
        return create(repo, next, headId, pager)
    }

    companion object {
        fun create(repo: GitRepo, result: LoadResult, headId: String?, history: CommitPager? = null): RepoState {
            val layout = GraphBuilder(BranchColors(result.refs))
            return RepoState(repo, result, layout.add(result.commits), headId, history, layout)
        }
    }
}

@Composable
fun FrameWindowScope.App(initialDir: File, reportInitialError: Boolean, onExit: () -> Unit = {}, registerClose: ((() -> Unit)?) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RepoState?>(null) }
    // 一启动就要打开上次的仓库，先当成正在加载，第一帧不要露出欢迎页的按钮
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CommitInfo?>(null) }
    var query by remember { mutableStateOf("") }
    var net by remember { mutableStateOf<NetStatus?>(null) }
    var operationBusy by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(MainView.HISTORY) }
    var stashes by remember { mutableStateOf(emptyList<StashEntry>()) }
    var operation by remember { mutableStateOf<GitOperation?>(null) }
    var pullDialog by remember { mutableStateOf(false) }
    var branchDrop by remember { mutableStateOf<Pair<RefLabel, RefLabel>?>(null) }
    var preview by remember { mutableStateOf<OperationIntent?>(null) }
    var interactiveBase by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<List<MenuEntry>?>(null) }
    var menuAnchor by remember { mutableStateOf<IntOffset?>(null) } // null：跟着鼠标弹出
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    var working by remember { mutableStateOf(emptyList<WorkingFile>()) }
    val commitListState = rememberLazyListState()
    // 分页读历史和换仓库互斥，免得读到一半仓库被关掉
    val historyLock = remember { Mutex() }
    var loadingMore by remember { mutableStateOf(false) }
    // 正在加载、要找到的提交；找到后选中它
    var seeking by remember { mutableStateOf<Seek?>(null) }
    // 列表要滚到的提交。等列表按新数据重组后再滚，下标才对得上
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    // 储藏页选中的储藏，侧边栏和图上的储藏都能把它带过去
    var stashSelected by remember { mutableStateOf<String?>(null) }
    // 侧边栏鼠标指着的分支，图上只亮它那条线
    var hoveredRef by remember { mutableStateOf<RefTarget?>(null) }
    // 文件页右键“查看改动”时，本地改动页要选中的文件
    var changesFocus by remember { mutableStateOf<String?>(null) }
    // 每读一次工作区状态加一，文件页据此重读目录（status 没变时修改时间、被忽略的文件也可能变了）
    var workingReloads by remember { mutableStateOf(0) }
    // 上次读历史时引用的样子。别处（命令行、编辑器）新建分支、提交、切分支后它会变，就要重读历史
    var refsSeen by remember { mutableStateOf<String?>(null) }
    val conflictDrafts = remember { mutableStateMapOf<String, ConflictDraft>() }
    val runningOperation = remember { RunningOperation() }
    var draftSession by remember { mutableStateOf<String?>(null) }
    val closeAction by rememberUpdatedState<() -> Unit> {
        when {
            operationBusy -> dialog = DialogSpec(t("Operation in progress", "操作正在执行"), t("Wait for the Git operation to finish before closing.", "请等 Git 操作结束后再关闭。"), t("Keep open", "保持打开")) { _, _ -> }
            conflictDrafts.values.any { it.dirty } -> dialog = DialogSpec(t("Discard unsaved conflict drafts?", "放弃未保存的冲突草稿？"), t("Save the drafts before closing, or discard them and close.", "关闭前请先保存草稿，或放弃草稿后关闭。"), t("Discard and close", "放弃并关闭"), danger = true) { _, _ -> onExit() }
            else -> onExit()
        }
    }
    DisposableEffect(Unit) {
        registerClose { closeAction() }
        val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
        if (desktop?.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler { _, response ->
            response.cancelQuit()
            javax.swing.SwingUtilities.invokeLater { closeAction() }
        }
        onDispose {
            registerClose(null)
            if (desktop?.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER) == true) desktop.setQuitHandler(null)
        }
    }
    var openGeneration by remember { mutableStateOf(0L) }
    var historyError by remember { mutableStateOf<String?>(null) }

    /** minShowMs：至少转这么久再换成仓库，启动时用，免得加载画面一闪而过。 */
    fun open(dir: File, quiet: Boolean = false, minShowMs: Long = 0, reportSuccess: Boolean = false, internalRefresh: Boolean = false, discardDrafts: Boolean = false) {
        if (!discardDrafts && state?.repo?.workTree?.canonicalFile != dir.canonicalFile && conflictDrafts.values.any { it.dirty }) {
            dialog = DialogSpec(t("Discard conflict drafts?", "放弃冲突草稿？"), t("Save the drafts before switching repositories, or discard them.", "切换仓库前请先保存草稿，或放弃这些草稿。"), t("Discard and switch", "放弃并切换"), danger = true) { _, _ ->
                conflictDrafts.clear()
                open(dir, quiet, minShowMs, reportSuccess, internalRefresh, discardDrafts = true)
            }
            return
        }
        if (operationBusy && !internalRefresh) return
        val generation = ++openGeneration
        loading = true
        scope.launch {
            val started = System.currentTimeMillis()
            loading = true
            try {
                val before = state
                // 刷新时只读回看得到的部分：列表最底下那一行和选中的提交都要还在，滚到的位置才不变。
                // 再往下的等滚过去再读，不用把之前翻过的几万个提交一次读回来
                val lastVisible = commitListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key as? String
                val wanted = listOfNotNull(lastVisible, selected?.id)
                val needed = before?.let { st -> wanted.mapNotNull { st.indexById[it] }.maxOrNull()?.plus(1) } ?: 0
                var initialRefs: String? = null
                val newState = withContext(Dispatchers.IO) {
                    val repo = GitRepo.open(dir)
                    try {
                        // 先记下再读历史，读的过程中引用又变了，下次比较时会再读一遍
                        initialRefs = repo.refsSnapshot()
                        // 只看某个文件时继续只看它
                        val same = before?.takeIf { it.repo.repository.directory == repo.repository.directory }
                        val keep = if (same != null) needed else 0
                        val history = repo.openHistory(same?.history?.query ?: HistoryQuery())
                        var result = history.load(maxOf(GitRepo.PAGE_SIZE, keep))
                        // 新提交会把旧提交往后挤，按原来的条数可能正好读不到它们，就再读一页
                        if (same != null && result.truncated) {
                            val ids = result.commits.mapTo(HashSet()) { it.id }
                            if (wanted.any { it !in ids }) result = history.load(GitRepo.PAGE_SIZE)
                        }
                        RepoState.create(repo, result, repo.repository.resolve("HEAD")?.name, history)
                    } catch (e: Throwable) {
                        repo.close()
                        throw e
                    }
                }
                val waited = System.currentTimeMillis() - started
                if (waited < minShowMs) delay(minShowMs - waited)
                if (generation != openGeneration) { newState.repo.close(); return@launch }
                historyLock.withLock {
                    if (generation != openGeneration) { newState.repo.close(); return@launch }
                    state?.repo?.close()
                    state = newState
                }
                historyError = null
                refsSeen = initialRefs
                newState.repo.workTree?.let { Settings.addRecentRepo(it.path) }
                // 刷新时保持选中；提交不在了或换了仓库才回到第一个
                val commits = newState.result.commits
                val sameRepository = before?.repo?.repository?.directory == newState.repo.repository.directory
                selected = if (sameRepository) commits.firstOrNull { it.id == selected?.id } ?: defaultSelection(commits, newState.headId)
                    else defaultSelection(commits, newState.headId)
                if (!sameRepository) {
                    working = emptyList()
                    stashes = emptyList()
                    operation = null
                    draftSession = null
                    stashSelected = null
                    changesFocus = null
                    hoveredRef = null
                    query = ""
                    preview = null
                    branchDrop = null
                    pullDialog = false
                    conflictDrafts.clear()
                    dialog = null
                    menu = null
                    scrollTarget = selected?.id
                }
                if (reportSuccess) net = NetStatus(
                    if (sameRepository) t("Repository refreshed", "仓库已刷新") else t("Repository opened", "仓库已打开"),
                    newState.repo.displayName, NetState.DONE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is HistoryScopeException && generation == openGeneration) historyError = e.message
                if ((!quiet || e is HistoryScopeException) && generation == openGeneration) net = NetStatus(t("Couldn't open repository", "打开仓库失败"), e.message ?: e.toString(), NetState.FAILED)
            } finally {
                if (generation == openGeneration) loading = false
            }
        }
    }

    LaunchedEffect(Unit) { open(initialDir, quiet = !reportInitialError, minShowMs = STARTUP_MIN_SHOW_MS) }
    DisposableEffect(Unit) { onDispose { state?.repo?.close() } }

    val chooseAndOpen = { chooseDirectory(t("Choose a git repository folder", "选择 git 仓库目录"), state?.repo?.workTree?.parentFile)?.let { open(it, reportSuccess = true) }; Unit }

    fun askClone() {
        dialog = DialogSpec(t("Clone repository", "克隆仓库"), t("Enter the repository URL. Next, choose where to save it.", "输入仓库地址，下一步选择保存到哪个文件夹。"), t("Next", "下一步"), input = t("Repository URL", "仓库地址")) { url, _ ->
            val parent = chooseDirectory(t("Choose where to save it", "选择保存到哪个文件夹"), state?.repo?.workTree?.parentFile) ?: return@DialogSpec
            val name = repoNameFromUrl(url)
            if (operationBusy) return@DialogSpec
            operationBusy = true
            scope.launch {
                net = NetStatus(t("Cloning", "正在克隆"), name, NetState.RUNNING)
                try {
                    val result = withContext(Dispatchers.IO) { Teaching.capture(Settings.teachingMode) { GitCli(parent).clone(url, name) } }
                    net = describeAction(t("Cloning", "正在克隆"), t("Cloned $name", "已克隆 $name"), result)
                    operationBusy = false
                    if (result.ok) open(File(parent, name))
                } finally {
                    operationBusy = false
                }
            }
        }
    }
    val s = state
    val workTree = s?.repo?.workTree
    val historyFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val textFocus = remember { TextFocus() }
    var paletteOpen by remember { mutableStateOf(false) }
    val cli = remember(workTree) { workTree?.let(::GitCli) } // 刷新时不换对象，正在写的提交说明才不会丢
    val clipboard = LocalClipboardManager.current
    // 侧边栏「工作树」「子模块」分组的数据。跟着工作区一起重读：写操作结束、文件变化后都会刷新
    var worktrees by remember(cli) { mutableStateOf(emptyList<WorktreeInfo>()) }
    var submodules by remember(cli) { mutableStateOf(emptyList<SubmoduleInfo>()) }
    // 每个本地分支的领先／落后数和最后提交时间。引用变了（获取、提交、切分支）才重读，后台算，读不出来就不显示
    var branchSync by remember(cli) { mutableStateOf(emptyMap<String, GitRepo.BranchSync>()) }
    // 比较页正在比较的两个引用，从分支右键「与当前分支比较」进入
    var compare by remember(cli) { mutableStateOf<CompareRequest?>(null) }

    suspend fun reloadWorking(force: Boolean = false) {
        if (operationBusy && !force) return
        val git = cli ?: return
        val current = state ?: return
        try {
            val snapshot = withContext(Dispatchers.IO) { Triple(git.status(), git.stashList(), git.currentOperation() to git.conflictSession()) }
            if (state !== current || (operationBusy && !force)) return
            working = snapshot.first
            stashes = snapshot.second
            operation = snapshot.third.first
            val session = snapshot.third.second
            if (session != draftSession || (operation == null && working.none { it.conflicted })) conflictDrafts.clear()
            draftSession = session
            workingReloads++
            if (loading || force) return
            val refs = withContext(Dispatchers.IO) { current.repo.refsSnapshot() }
            if (refs != refsSeen && state === current && !operationBusy) {
                (current.repo.workTree ?: current.repo.repository.directory)?.let { open(it, quiet = true) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (state === current) net = NetStatus(t("Couldn't read repository state", "读取仓库状态失败"), e.message.orEmpty(), NetState.WARNING)
        }
    }

    // 文件可能在别的编辑器里改，要重读工作区状态。大仓库里 git status 一次要几百毫秒 CPU，所以：
    // 监听文件变化，有变化才读；只在窗口有焦点时读，切回窗口时马上读一次；监听不了时退回每 3 秒读一次
    val changes = remember(cli) { Channel<Unit>(Channel.CONFLATED) }
    var watching by remember(cli) { mutableStateOf<Boolean?>(null) } // null：还在启动监听
    // 首次打开必须取得这个仓库的状态，不能依赖原生窗口是否已经收到焦点。
    LaunchedEffect(cli, s?.repo) { if (cli != null) reloadWorking() }
    LaunchedEffect(cli) {
        val git = cli ?: return@LaunchedEffect
        var watcher: AutoCloseable? = null
        try {
            // Linux 上要逐个目录注册，可能要一会儿，放到后台。注册本身停不下来，
            // 这期间换了仓库也要拿到建好的监听器，下面 finally 里才关得掉
            watcher = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { watchWorkTree(git.watchRoots, git.gitDirectories) { changes.trySend(Unit) } }.getOrNull()
            }
            watching = watcher != null
            awaitCancellation()
        } finally {
            watcher?.close()
        }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(cli, windowFocused, watching) {
        if (!windowFocused || cli == null) return@LaunchedEffect
        if (watching != true) {
            while (true) {
                reloadWorking()
                delay(POLL_INTERVAL_MS)
            }
        }
        while (true) {
            changes.tryReceive() // 失焦期间攒下的变化，下面这次读就包括了
            reloadWorking()
            // 编译、切分支时文件一直在变，两次读之间至少隔一会儿，期间的变化合并成下一次
            delay(MIN_RELOAD_GAP_MS)
            // 监听可能漏掉事件（比如网络盘），隔一阵子无论如何读一次
            withTimeoutOrNull(FALLBACK_RELOAD_MS) { changes.receive() }
        }
    }

    val conflicted = remember(working) { working.filter { it.conflicted } }
    val inConflict = conflicted.isNotEmpty() || operation != null

    // 出现冲突时直接带到解决页面，处理完回到历史
    LaunchedEffect(inConflict) {
        if (inConflict) view = MainView.CONFLICT else if (view == MainView.CONFLICT) view = MainView.HISTORY
    }

    /** quiet：成功不出横条，进度也只在超过 400 毫秒时才显示。给暂存这类一眼就能看到结果的操作用。 */
    fun runOp(
        running: NetStatus,
        finish: (GitResult) -> NetStatus,
        refreshHistory: Boolean = true,
        onCompleted: (Boolean) -> Unit = {},
        quiet: Boolean = false,
        op: GitCli.() -> GitResult,
    ) {
        val git = cli ?: return
        val dir = workTree ?: return
        if (operationBusy || loading) return
        operationBusy = true
        val progress = if (quiet) scope.launch { delay(400); net = running } else null
        if (!quiet) net = running
        scope.launch {
            var succeeded = false
            try {
                val result = withContext(Dispatchers.IO + NonCancellable) {
                    runningOperation.begin()
                    try { Teaching.capture(Settings.teachingMode) { git.coordinator.execute(OperationRequest(OperationKind.OTHER)) { git.op() } } }
                    finally { runningOperation.end() }
                }
                progress?.cancel()
                net = when {
                    runningOperation.cancelled && !result.ok -> NetStatus(t("Cancelled", "已取消"),
                        t("The operation was interrupted. Anything already transferred stays; check the repository state.", "操作已中断，已经传输的内容会保留；请核对仓库状态。"), NetState.WARNING, output = result.output)
                    quiet && result.ok && result.lessons.isEmpty() -> net.takeIf { it !== running && it?.state != NetState.RUNNING }
                    else -> finish(result)
                }
                succeeded = result.ok
                reloadWorking(force = true)
                if (refreshHistory) open(dir, internalRefresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 写操作已有结果，刷新失败不能让用户以为写操作也失败而重复执行。
                net = NetStatus(t("Couldn't refresh repository", "仓库刷新失败"),
                    t("The operation has finished. Refresh the repository to see its current state.", "操作已结束，请刷新仓库以查看当前状态。"),
                    NetState.WARNING, output = e.message ?: e.toString())
            } finally {
                progress?.cancel()
                operationBusy = false
            }
            onCompleted(succeeded)
        }
    }
    val runLocal: OperationRunner = { running, done, op, completed ->
        runOp(NetStatus(running, "", NetState.RUNNING), { describeAction(running, done, it) },
            refreshHistory = false, onCompleted = completed, op = op)
    }
    val runQuiet: OperationRunner = { running, done, op, completed ->
        runOp(NetStatus(running, "", NetState.RUNNING), { describeAction(running, done, it) },
            refreshHistory = false, onCompleted = completed, quiet = true, op = op)
    }

    fun runPull(settings: PullSettings) =
        runOp(NetStatus(NetOp.PULL.running, "${settings.remote}/${settings.branch}", NetState.RUNNING, cancellable = true), { describe(NetOp.PULL, it) }) { pull(settings) }

    /** Pull with the saved strategy and upstream; ask only when the branch has no upstream yet. */
    fun startPull() {
        val git = cli ?: return
        if (operationBusy || loading) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { git.savedPull() }.getOrNull() }
            if (cli !== git || operationBusy || loading) return@launch
            if (saved == null) pullDialog = true else runPull(saved)
        }
    }

    fun runNet(op: NetOp, confirmed: Boolean = false) {
        if (op == NetOp.PULL) { startPull(); return }
        if (op == NetOp.FORCE_PUSH && !confirmed) {
            dialog = DialogSpec(t("Force push", "强制推送"),
                t("Replace the remote branch with your local one. Commits that exist only on the remote branch will be removed from it. Git refuses if the remote changed since your last fetch.", "用本地分支覆盖远程分支，只存在于远程分支上的提交会从它上面消失。如果远程在你上次获取之后又变了，Git 会拒绝。"),
                t("Force push", "强制推送"), danger = true) { _, _ -> runNet(op, confirmed = true) }
            return
        }
        val detail = workTree?.name.orEmpty()
        runOp(NetStatus(op.running, detail, NetState.RUNNING, cancellable = op != NetOp.ABORT), { describe(op, it) }) {
            when (op) {
                NetOp.FETCH -> fetch()
                NetOp.PUSH -> push()
                NetOp.FORCE_PUSH -> push(force = true)
                NetOp.PULL -> error("Pull runs through startPull")
                NetOp.ABORT -> abortOperation()
            }
        }
    }

    /** 右键分支推送：不切换分支。推当前分支时和工具栏按钮一样；推别的分支失败时，「先拉取」「重试」这些按钮针对的是当前分支，不能给。 */
    fun runBranchPush(branch: String, force: Boolean, confirmed: Boolean = false) {
        val here = s?.result?.refs?.firstOrNull { it.label.kind == RefKind.BRANCH && it.label.isCurrent }?.label?.name
        if (branch == here) { runNet(if (force) NetOp.FORCE_PUSH else NetOp.PUSH, confirmed); return }
        if (force && !confirmed) {
            dialog = DialogSpec(t("Force push $branch", "强制推送 $branch"),
                t("Replace the remote branch with your local $branch. Commits that exist only on the remote branch will be removed from it. Git refuses if the remote changed since your last fetch. Your current branch is not changed.", "用本地的 $branch 覆盖远程分支，只存在于远程分支上的提交会从它上面消失。如果远程在你上次获取之后又变了，Git 会拒绝。当前所在的分支不受影响。"),
                t("Force push", "强制推送"), danger = true) { _, _ -> runBranchPush(branch, true, confirmed = true) }
            return
        }
        val op = if (force) NetOp.FORCE_PUSH else NetOp.PUSH
        runOp(NetStatus(op.running, branch, NetState.RUNNING, cancellable = true), {
            describe(op, it).let { status -> if (status.state == NetState.FAILED || status.state == NetState.WARNING) status.copy(next = null, nextLabel = "", alternative = null, alternativeLabel = "") else status }
        }) { pushBranch(branch, force) }
    }

    val actions = GitActions(
        preview = { if (!operationBusy && !loading) preview = it },
        editRebase = { if (!operationBusy && !loading) interactiveBase = it },
        ask = { dialog = it },
        pendingChanges = { working.size },
        run = { running, done, op ->
            runOp(NetStatus(running, "", NetState.RUNNING), { describeAction(running, done, it) }, op = op)
        },
        copy = {
            clipboard.setText(AnnotatedString(it))
            if (!operationBusy) net = NetStatus(t("Copied to clipboard", "已复制到剪贴板"), "", NetState.DONE)
        },
    )
    val currentBranch = s?.result?.refs?.firstOrNull { it.label.kind == RefKind.BRANCH && it.label.isCurrent }?.label?.name
    // 几万个提交过滤一次要一百多毫秒，放到后台算，打字时界面不卡
    var filtered by remember { mutableStateOf<Filtered?>(null) }
    LaunchedEffect(s, query) {
        val st = s ?: return@LaunchedEffect
        if (query.isBlank()) return@LaunchedEffect
        val ids = withContext(Dispatchers.Default) { filterCommits(st.result.commits, query) }
        filtered = Filtered(st, query, ids)
    }
    val shown = remember(s, query, filtered) { s?.let { visibleCommits(it, query, filtered) }.orEmpty() }

    // 已加载的提交即时过滤；同时在后台搜全部历史，看没加载的部分里还有多少匹配
    var search by remember { mutableStateOf<SearchResult?>(null) }
    val searchRepo = s?.repo
    val pathFilter = s?.history?.path
    val historyStarts = s?.history?.startIds
    val branchFilter = s?.history?.query?.refs.orEmpty()
    LaunchedEffect(searchRepo, query, pathFilter, historyStarts) {
        search = null
        val q = query.trim()
        if (searchRepo == null || q.isEmpty()) return@LaunchedEffect
        delay(300) // 打字时不要每个字都搜一遍
        val job = coroutineContext.job
        search = withContext(Dispatchers.IO) {
            runCatching { searchRepo.searchCommits(q, path = pathFilter, cancelled = { !job.isActive }, startIds = historyStarts, refNames = s?.history?.refNames) }.getOrNull()
        }
    }
    val unloadedMatches = remember(search, s) { s?.let { st -> search?.ids?.filter { it !in st.indexById } } }

    LaunchedEffect(scrollTarget, shown) {
        val id = scrollTarget ?: return@LaunchedEffect
        val index = s?.indexById?.get(id) ?: return@LaunchedEffect // 还没加载到，等下一页
        val position = shown.indexOf(index)
        // 搜索时新读到的提交要等后台过滤完才进列表，过滤结果换了 shown 会再进来
        if (position < 0 && query.isNotBlank() && (filtered?.state !== s || filtered?.query != query)) return@LaunchedEffect
        // 浮层展开后只剩几行空间，跳转目标放到表头下面，避免落在浮层后面。
        if (position >= 0) commitListState.animateScrollToItem(position)
        scrollTarget = null
    }

    /**
     * 读下一页。target 不为空时一直读到它出现或历史读完，返回它是否已加载。
     * 找提交时每次读的量翻倍，几十万个提交也只重排十几次。
     */
    suspend fun loadMore(target: String? = null): Boolean {
        while (true) {
            val current = state ?: return false
            if (target != null && target in current.indexById) return true
            if (!current.result.truncated || current.history == null) return false
            val count = if (target == null) GitRepo.PAGE_SIZE else maxOf(GitRepo.PAGE_SIZE, current.result.commits.size)
            loadingMore = true
            try {
                historyLock.withLock {
                    // 等锁的时候可能刷新了，换成了新仓库，回到循环开头重新看
                    if (state === current) state = withContext(Dispatchers.IO) { current.loadMore(count) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return false // 仓库被关掉之类，下次刷新会重新读
            } finally {
                loadingMore = false
            }
            if (target == null) return true
        }
    }

    /** 主图换成只看 path 的历史；path 为空时回到全部历史。选中的提交还在就继续选中它。 */
    fun showHistoryOf(path: String?, refs: Set<String> = state?.history?.query?.refs.orEmpty()) {
        if (operationBusy) return
        val requested = HistoryQuery(refs, path)
        if (state?.history?.query == requested) return
        seeking?.job?.cancel()
        val generation = ++openGeneration
        loading = true
        scope.launch {
            try {
                historyLock.withLock {
                    if (generation != openGeneration) return@withLock
                    val now = state ?: return@withLock
                    val next = withContext(Dispatchers.IO) {
                        val pager = now.repo.openHistory(requested)
                        try {
                            val result = pager.load(GitRepo.PAGE_SIZE)
                            RepoState.create(now.repo, result, now.headId, pager)
                        } catch (e: Throwable) {
                            pager.close()
                            throw e
                        }
                    }
                    if (generation != openGeneration || state !== now) { next.history?.close(); return@withLock }
                    now.history?.close()
                    state = next
                    historyError = null
                    val commits = next.result.commits
                    selected = commits.firstOrNull { it.id == selected?.id } ?: defaultSelection(commits, next.headId)
                    scrollTarget = selected?.id
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == openGeneration) net = NetStatus(t("Couldn't read history", "读取历史失败"), e.message ?: e.toString(), NetState.FAILED)
            } finally {
                if (generation == openGeneration) loading = false
            }
        }
    }

    /** 选中并滚到 id；还没加载时先往下读，select 为 false 时只加载不选中。 */
    fun jumpTo(id: String, select: Boolean = true, direct: Boolean = false) {
        val current = s ?: return
        current.indexById[id]?.let { index ->
            if (select) { selected = current.result.commits[index]; scrollTarget = id }
            return
        }
        seeking?.job?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext.job
            try {
                val outsideBranches = if (current.history?.query?.refs?.isNotEmpty() == true) withContext(Dispatchers.IO) { !current.repo.isReachable(id, current.history.startIds) } else false
                if (select && (direct || current.history?.path != null || outsideBranches)) {
                    // A reflog object need not be reachable by the currently filtered pager.
                    val commit = withContext(Dispatchers.IO) { current.repo.commit(id) }
                    if (state?.repo === current.repo) { selected = commit; scrollTarget = id }
                } else {
                    val found = loadMore(target = id)
                    val now = state
                    if (select && now?.repo === current.repo) {
                        val commit = if (found) now.result.commits[now.indexById.getValue(id)] else withContext(Dispatchers.IO) { now.repo.commit(id) }
                        if (state?.repo === now.repo) { selected = commit; scrollTarget = id }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (state?.repo === current.repo) net = NetStatus(t("Commit unavailable", "提交不可用"),
                    t("The object may have been pruned. ${e.message}", "对象可能已被清理。${e.message}"), NetState.WARNING)
            } finally { if (seeking?.job === me) seeking = null }
        }
        seeking = Seek(id, job)
        job.start()
    }

    LaunchedEffect(cli) { rootFocus.requestFocus() }
    LaunchedEffect(cli, s?.result?.refs) {
        val dir = workTree ?: return@LaunchedEffect
        branchSync = withContext(Dispatchers.IO) { runCatching { GitRepo.open(dir).use { it.branchSync() } }.getOrDefault(emptyMap()) }
    }
    LaunchedEffect(cli, workingReloads) {
        val git = cli ?: return@LaunchedEffect
        val read = withContext(Dispatchers.IO) {
            // 读不出来就不显示分组，不打扰日常使用
            runCatching { git.worktrees() }.getOrDefault(emptyList()) to runCatching { git.submodules() }.getOrDefault(emptyList())
        }
        worktrees = read.first; submodules = read.second
    }
    val commitDraft = remember(cli) { CommitDraft() }
    val aiConnection = remember { AiConnection() }
    val available = cli != null && !loading && !operationBusy
    val registry = CommandRegistry(listOf(
        AppCommand("search", t("Search history", "搜索历史"), s != null, "⌘/Ctrl F") { view = MainView.HISTORY; scope.launch { withFrameNanos { }; searchFocus.requestFocus() } },
        AppCommand("refresh", t("Refresh repository", "刷新仓库"), s != null && !loading && !operationBusy, "⌘/Ctrl R") { (s?.repo?.workTree ?: s?.repo?.repository?.directory)?.let { open(it, reportSuccess = true) } },
        AppCommand("changes", t("Local changes", "本地改动"), cli != null) { view = MainView.CHANGES },
        AppCommand("insights", t("Repository insights", "统计和洞察"), s != null) { view = MainView.INSIGHTS },
        AppCommand("ai", t("AI assistance", "AI 辅助"), cli != null) { view = MainView.AI },
        AppCommand("interactive-rebase", t("Interactive rebase…", "交互式变基…"), available && !inConflict && currentBranch != null) { interactiveBase = "HEAD^" },
        AppCommand("commit", t("Commit staged changes", "提交暂存改动"), available && !inConflict && commitDraft.message.isNotBlank() && (commitDraft.amend || working.any { it.hasStaged }), "⌘/Ctrl Enter") {
            runOp(NetStatus(t("Committing", "正在提交"), "", NetState.RUNNING), { describeAction(t("Committing", "正在提交"), t("Committed", "已提交"), it) }, onCompleted = { if (it) { commitDraft.message = ""; commitDraft.amend = false } }) { commit(commitDraft.message.trim(), commitDraft.amend) }
        },
        AppCommand("fetch", t("Fetch", "获取"), available && !inConflict) { runNet(NetOp.FETCH) },
        AppCommand("pull", t("Pull", "拉取"), available && !inConflict) { runNet(NetOp.PULL) },
        AppCommand("pull-settings", t("Pull settings…", "拉取设置…"), available && !inConflict) { pullDialog = true },
        AppCommand("push", t("Push", "推送"), available && !inConflict) { runNet(NetOp.PUSH) },
        AppCommand("force-push", t("Force push…", "强制推送…"), available && !inConflict && currentBranch != null) { runNet(NetOp.FORCE_PUSH) },
        AppCommand("stash", t("Stash changes…", "储藏改动…"), available && !inConflict && working.isNotEmpty()) { askStash(working.size, actions) },
        AppCommand("continue", t("Continue Git operation", "继续 Git 操作"), available && operation != null && conflicted.isEmpty()) { actions.run(t("Continuing", "正在继续"), t("Continued", "已继续")) { continueOperation() } },
        AppCommand("abort", t("Abort Git operation…", "中止 Git 操作…"), available && operation != null) { dialog = DialogSpec(t("Abort operation", "中止操作"), t("Discard the current conflict resolutions and abort?", "放弃当前冲突处理结果并中止？"), t("Abort", "中止"), danger = true) { _, _ -> runNet(NetOp.ABORT) } },
        AppCommand("palette", t("Command palette", "命令面板"), true, "⌘/Ctrl Shift P") { paletteOpen = true },
    ) + selected?.takeUnless { it.isStash }?.let { c -> commitMenu(c, c.id == s?.headId, currentBranch, actions).filterIsInstance<MenuItem>().mapIndexed { i, item -> AppCommand("selected-$i", item.label, available && !inConflict && item.enabled, action = item.action) } }.orEmpty())
    val drag = remember(cli, operationBusy, loading, inConflict) { BranchDragState(cli != null && !operationBusy && !loading && !inConflict) { a, b -> branchDrop = a to b } }
    CompositionLocalProvider(LocalBranchDrag provides drag, LocalTextFocus provides textFocus, LocalCommands provides registry) {
    Row(Modifier.fillMaxSize().focusRequester(rootFocus).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else shortcutCommand(event.key, if (isMac) event.isMetaPressed else event.isCtrlPressed, event.isShiftPressed, textFocus.editing)?.let { registry.execute(it) } ?: false
    }.focusable()) {
        if (s != null) {
            // 侧边栏从窗口顶部铺下来，顶上一段留给红绿灯
            Column(Modifier.width(248.dp).fillMaxHeight().background(Palette.Rail)) {
                if (isMac) WindowDraggableArea { Spacer(Modifier.fillMaxWidth().height(TitleBarHeight)) }
                Column(Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (inConflict) {
                        WorkspaceItem(t("Resolve conflicts", "解决冲突"), Icons.Branch, view == MainView.CONFLICT, conflicted.size) { view = MainView.CONFLICT }
                    }
                    WorkspaceItem(t("Changes", "本地改动"), Icons.Edit, view == MainView.CHANGES, working.size - conflicted.size) { view = MainView.CHANGES }
                    WorkspaceItem(t("All commits", "所有提交"), Icons.History, view == MainView.HISTORY, 0) { view = MainView.HISTORY }
                    if (FeatureVisibility.visible("ai")) WorkspaceItem(t("AI assistance", "AI 辅助"), Icons.Edit, view == MainView.AI, 0) { view = MainView.AI }
                    if (FeatureVisibility.visible("reflog")) WorkspaceItem("Reflog", Icons.History, view == MainView.REFLOG, 0) { view = MainView.REFLOG }
                    if (workTree != null) {
                        WorkspaceItem(t("Files", "文件"), Icons.Folder, view == MainView.FILES, 0) { view = MainView.FILES }
                    }
                    WorkspaceItem(t("Stashes", "储藏"), Icons.Stash, view == MainView.STASH, stashes.size) { view = MainView.STASH }
                    WorkspaceItem(t("Insights", "统计和洞察"), Icons.Chart, view == MainView.INSIGHTS, 0) { view = MainView.INSIGHTS }
                }
                HorizontalDivider()
                RefSidebar(
                    s.result.refs, query,
                    onSelect = { ref ->
                        view = MainView.HISTORY
                        jumpTo(ref.commitId)
                    },
                    onBranchOpen = { ref ->
                        if (cli != null && ref.label.kind == RefKind.REMOTE) {
                            actions.run(t("Checking out", "正在检出"), t("Checked out ${ref.label.name}", "已检出 ${ref.label.name}")) { checkoutRemote(ref.label.name) }
                        } else if (cli != null && !ref.label.isCurrent) {
                            actions.run(t("Switching", "正在切换"), t("Switched to ${ref.label.name}", "已切换到 ${ref.label.name}")) { switchTo(ref.label.name) }
                        }
                    },
                    onBranchMenu = { ref ->
                        if (cli != null) {
                            menuAnchor = null
                            val full = (if (ref.label.kind == RefKind.BRANCH) "refs/heads/" else "refs/remotes/") + ref.label.name
                            val own = if (ref.label.kind == RefKind.BRANCH) branchMenu(ref.label.name, ref.commitId, ref.label.isCurrent, currentBranch, actions,
                                    hasUpstream = branchSync[ref.label.name]?.ahead != null, onPush = { force -> runBranchPush(ref.label.name, force) })
                                else listOf(MenuItem(t("Check out as local branch", "检出为本地分支")) {
                                    actions.run(t("Checking out", "正在检出"), t("Checked out ${ref.label.name}", "已检出 ${ref.label.name}")) { checkoutRemote(ref.label.name) }
                                })
                            val here = currentBranch ?: "HEAD"
                            val isCurrent = ref.label.kind == RefKind.BRANCH && ref.label.isCurrent
                            val extra = listOfNotNull(
                                MenuItem(t("Compare with $here", "与 $here 比较"), enabled = !isCurrent) {
                                    compare = CompareRequest(currentBranch?.let { "refs/heads/$it" } ?: "HEAD", full, here, ref.label.name)
                                    view = MainView.COMPARE
                                },
                                if (workTree != null) worktreeCheckoutItem(ref.label.name, isCurrent, worktrees, workTree, actions, remote = ref.label.kind == RefKind.REMOTE) else null,
                            )
                            menu = own + MenuSeparator + extra + MenuSeparator + listOf(
                                MenuItem(t("Only this branch", "只看此分支")) { view = MainView.HISTORY; showHistoryOf(pathFilter, setOf(full)) },
                                MenuItem(t("Include in branch filter", "加入分支过滤"), checked = full in branchFilter) {
                                    view = MainView.HISTORY
                                    showHistoryOf(pathFilter, if (full in branchFilter) branchFilter - full else branchFilter + full)
                                },
                            )
                        }
                    },
                    branchColors = s.branchColors,
                    onHover = { hoveredRef = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    sync = branchSync,
                    nowSeconds = rememberNowSeconds(),
                    repositoryDirectory = s.repo.repository.directory,
                    groups = if (cli == null || workTree == null) emptyList() else {
                        val openRepo: (File) -> Unit = { open(it, reportSuccess = true) }
                        val reveal: (File) -> Unit = { dir ->
                            scope.launch {
                                val error = withContext(Dispatchers.IO) { runCatching { revealInFileManager(dir) }.exceptionOrNull() }
                                if (error != null) net = NetStatus(t("Couldn't show ${dir.name}", "无法显示 ${dir.name}"), error.message ?: error.toString(), NetState.FAILED)
                            }
                        }
                        val showMenu: (List<MenuEntry>) -> Unit = { menuAnchor = null; menu = it }
                        listOfNotNull(
                            worktreeGroup(worktrees, workTree, actions, openRepo, reveal, showMenu),
                            submoduleGroup(submodules, cli, actions, openRepo, reveal, showMenu),
                        )
                    },
                )
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(Icons.Settings) { view = MainView.SETTINGS }
                }
            }
            VerticalDivider()
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
        Column(Modifier.fillMaxSize().background(Palette.Ground)) {
            WindowDraggableArea {
                // 没有侧边栏时红绿灯压在工具栏上，要让开
                val start = if (isMac && s == null) 84.dp else 18.dp
                Toolbar(
                    s, loading, start, query, onQuery = { query = it },
                    onOpen = { at ->
                        menuAnchor = at
                        menu = repoMenu(workTree?.path, Settings.recentRepos, { open(File(it), reportSuccess = true) }, chooseAndOpen, ::askClone)
                    },
                    onRefresh = { registry.execute("refresh") },
                    searchFocus = searchFocus,
                    onPullMenu = { menuAnchor = null; menu = listOf(MenuItem(t("Pull", "拉取"), enabled = available && !inConflict) { registry.execute("pull") }, MenuItem(t("Pull settings…", "拉取设置…"), enabled = available && !inConflict) { registry.execute("pull-settings") }) },
                    onPushMenu = { menuAnchor = null; menu = listOf(MenuItem(t("Push", "推送"), enabled = available && !inConflict) { registry.execute("push") }, MenuItem(t("Force push…", "强制推送…"), enabled = available && !inConflict && currentBranch != null, danger = true) { registry.execute("force-push") }) },
                    onSearchEscape = { query = ""; view = MainView.HISTORY; scope.launch { withFrameNanos { }; historyFocus.requestFocus() } },
                    netBusy = operationBusy,
                    branch = currentBranch,
                    ahead = currentBranch?.let { branchSync[it]?.ahead },
                    behind = currentBranch?.let { branchSync[it]?.behind },
                    onFetch = { registry.execute("fetch") },
                    onPull = { registry.execute("pull") },
                    onPush = { registry.execute("push") },
                    canStash = working.isNotEmpty(),
                    onStash = {
                        registry.execute("stash")
                        view = MainView.STASH
                    },
                )
            }
            HorizontalDivider()
            if (s == null) {
                Welcome(loading, Settings.recentRepos, onRecent = { open(File(it), reportSuccess = true) }, onOpen = chooseAndOpen, onClone = ::askClone)
            } else {
                if (view == MainView.REFLOG) {
                    ReflogView(s.repo, actions, { id -> view = MainView.HISTORY; jumpTo(id, direct = true) }, Modifier.fillMaxWidth().weight(1f), operationBusy || loading || cli == null)
                } else if (view == MainView.INSIGHTS) {
                    InsightsView(s.repo.workTree ?: s.repo.repository.directory, Modifier.fillMaxWidth().weight(1f))
                } else if (view == MainView.AI && cli != null) {
                    AiView(cli, aiConnection, commitDraft, actions, workingReloads, operationBusy || loading, { view = MainView.CHANGES }, Modifier.fillMaxWidth().weight(1f))
                } else if (view == MainView.COMPARE && compare != null) {
                    ComparisonView(
                        s.repo.workTree ?: s.repo.repository.directory, compare!!, s.result.refs,
                        onRequest = { compare = it },
                        onOpenCommit = { id -> view = MainView.HISTORY; jumpTo(id, direct = true) },
                        onClose = { view = MainView.HISTORY },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else if (view == MainView.SETTINGS) {
                    SettingsView(cli, Modifier.fillMaxWidth().weight(1f))
                } else if (view == MainView.CONFLICT && cli != null) {
                    ConflictView(
                        cli, conflicted, operation,
                        onAbort = { runNet(NetOp.ABORT) },
                        onFinish = { actions.run(t("Finishing", "正在完成"), t("Finished", "已完成")) { continueOperation() } },
                        onRun = runLocal,
                        busy = operationBusy || loading,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        revision = workingReloads,
                        drafts = conflictDrafts,
                        onSkip = { dialog = DialogSpec(t("Skip commit", "跳过提交"), t("Discard the current patch and its conflict resolutions?", "丢弃当前补丁及其冲突处理结果？"), t("Skip", "跳过"), danger = true) { _, _ ->
                            actions.run(t("Skipping", "正在跳过"), t("Commit skipped", "已跳过提交")) { rebaseStep("--skip") }
                        } },
                        onKeepEmpty = { actions.run(t("Continuing", "正在继续"), t("Rebase continued", "变基已继续")) { keepEmptyRebaseCommit() } },
                    )
                } else if (view == MainView.FILES && cli != null && workTree != null) {
                    FilesView(
                        cli, workTree, workingReloads,
                        onShowChanges = { path ->
                            changesFocus = path
                            view = MainView.CHANGES
                        },
                        onShowHistory = { path ->
                            view = MainView.HISTORY
                            showHistoryOf(path)
                        },
                        onFailure = { title, detail -> net = NetStatus(title, detail, NetState.FAILED) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onRun = runLocal,
                        onSuccess = { title -> if (!operationBusy) net = NetStatus(title, "", NetState.DONE) },
                        busy = operationBusy || loading,
                    )
                } else if (view == MainView.STASH && cli != null) {
                    StashView(cli, stashes, actions, Modifier.fillMaxWidth().weight(1f), stashSelected, onSelect = { stashSelected = it })
                } else if (view == MainView.CHANGES && cli != null) {
                    ChangesView(
                        cli, working.filterNot { it.conflicted }, s.result.currentBranch,
                        onChanged = { historyChanged ->
                            if (historyChanged) s.repo.workTree?.let { open(it) }
                        },
                        onRun = runQuiet,
                        busy = operationBusy || loading,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        externalRevision = workingReloads,
                        draft = commitDraft,
                        onAi = if (FeatureVisibility.visible("ai")) ({ view = MainView.AI }) else null,
                        focus = changesFocus,
                        onFocused = { changesFocus = null },
                    )
                } else {
                    val searching = query.isNotBlank()
                    val footer: String? = when {
                        seeking != null -> t("Loading earlier commits…", "正在加载更早的提交…")
                        searching && s.result.truncated && search == null -> t("Searching all history…", "正在搜索全部历史…")
                        searching && !unloadedMatches.isNullOrEmpty() -> {
                            val more = if (search?.complete == false) "+" else ""
                            t("${unloadedMatches.size}$more more matches in earlier history. Click to load.", "更早的历史里还有 ${unloadedMatches.size}$more 个匹配，点击加载")
                        }
                        loadingMore -> t("Loading earlier commits…", "正在加载更早的提交…")
                        else -> null
                    }
                    historyError?.let { Text(t("History is outdated: $it", "历史已过期：$it"), color = Palette.Modified, modifier = Modifier.padding(12.dp)) }
                    // 只在过滤生效时出现；添加过滤从侧边栏右键分支进入
                    if (branchFilter.isNotEmpty()) {
                        BranchFilterBar(s.result.refs, branchFilter, loading || operationBusy, { showHistoryOf(pathFilter, it) })
                        HorizontalDivider()
                    }
                    if (pathFilter != null) {
                        PathFilterBar(pathFilter, s.result, onClear = { showHistoryOf(null) })
                        HorizontalDivider()
                    }
                    CommitHistoryLayout(
                        hasSelection = selected != null,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        list = { historyInsets ->
                            CommitList(
                                s, shown, selected, commitListState, onSelect = { selected = it },
                                Modifier.fillMaxSize(),
                                historyInsets = historyInsets,
                                focusRequester = historyFocus,
                                uncommitted = if (query.isBlank() && pathFilter == null) working.size else 0,
                                onUncommitted = { view = MainView.CHANGES },
                                sidebarHighlight = hoveredRef?.let { s.indexById[it.commitId] }?.let { s.rows[it].nodeTrack.line },
                                onMenu = { c ->
                                    selected = c
                                    // 储藏不能 checkout、cherry-pick，右键直接去储藏页应用或删除
                                    if (c.isStash) {
                                        stashSelected = c.id
                                        view = MainView.STASH
                                    } else if (cli != null) {
                                        menuAnchor = null
                                        menu = commitMenu(c, c.id == s.headId, currentBranch, actions)
                                    }
                                },
                                // 搜索时不自动往下读，免得为了凑结果把整个历史读进来
                                onNearEnd = {
                                    if (!searching && !loadingMore && seeking == null && s.result.truncated) scope.launch { loadMore() }
                                },
                                footer = footer,
                                onFooter = unloadedMatches?.lastOrNull()?.takeIf { searching && seeking == null }?.let { oldest ->
                                    { jumpTo(oldest, select = false) }
                                },
                            )
                        },
                        detail = { detailModifier ->
                            CommitDetail(
                                s.repo, selected,
                                onOpenCommit = { id -> jumpTo(id) },
                                modifier = detailModifier,
                                onShowFileHistory = { path -> showHistoryOf(path) },
                            )
                        },
                    )
                }
                HorizontalDivider()
                StatusBar(s, loadingMore || seeking != null, seeking?.let { { it.job.cancel() } })
            }
        }
        // 操作反馈浮在右下角，不再占一整行把列表顶下去
        net?.let {
            NetBanner(it, onAction = { op -> runNet(op) }, onDismiss = { if (net === it) net = null }, onCancel = runningOperation::cancel,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(end = 16.dp, bottom = 40.dp))
        }
        }
    }
    }
    if (pullDialog && cli != null) PullDialog(cli, operationBusy || loading, onDismiss = { pullDialog = false },
        onFetch = { remote -> actions.run(t("Fetching", "正在获取"), t("Fetched", "已获取")) { fetch(remote) } },
        onPull = ::runPull)
    if (paletteOpen) CommandPalette(registry) { paletteOpen = false }
    branchDrop?.let { (a, b) ->
        AlertDialog(onDismissRequest = { branchDrop = null }, title = { Text("${a.name} → ${b.name}") },
            text = { Text(t("Choose what to do with these two branches.", "选择要对这两个分支做的操作。")) },
            confirmButton = { Row {
                DialogButton(t("Merge ${a.name} into ${b.name}", "将 ${a.name} 合并进 ${b.name}"), Palette.AccentInk, b.kind == RefKind.BRANCH) { preview = branchDropIntent(a, b, PreviewKind.MERGE); branchDrop = null }
                DialogButton(t("Rebase ${a.name} onto ${b.name}", "将 ${a.name} 变基到 ${b.name}"), Palette.AccentInk, a.kind == RefKind.BRANCH) { preview = branchDropIntent(a, b, PreviewKind.REBASE); branchDrop = null }
            } }, dismissButton = { DialogButton(t("Cancel", "取消")) { branchDrop = null } })
    }
    menu?.let { GitMenuPopup(it, onDismiss = { menu = null }, anchor = menuAnchor) }
    dialog?.let { ActionDialog(it, onDismiss = { dialog = null }) }
    if (cli != null) interactiveBase?.let { base ->
        InteractiveRebaseDialog(cli, base, operationBusy || loading, onDismiss = { interactiveBase = null }) { intent ->
            interactiveBase = null
            actions.run(t("Rebasing", "正在变基"), t("Rebased", "已变基")) { executePlan(planOperation(intent)) }
        }
    }
    if (cli != null && s != null) preview?.let { intent ->
        OperationConfirmDialog(cli, intent, currentBranch, operationBusy || loading, onDismiss = { preview = null }) { plan ->
            preview = null
            val (running, done) = operationTitles(plan.intent)
            actions.run(running, done) { executePlan(plan) }
        }
    }
}

/** 正在执行写操作的线程。取消就是中断它：GitProcess 收到中断会结束 git 子进程并返回。 */
internal class RunningOperation {
    private var thread: Thread? = null
    var cancelled = false
        private set
    @Synchronized fun begin() { thread = Thread.currentThread(); cancelled = false }
    /** 在执行操作的线程上调用，顺便清掉中断标记，线程回到池里才是干净的。 */
    @Synchronized fun end() { thread = null; Thread.interrupted() }
    @Synchronized fun cancel() { thread?.let { cancelled = true; it.interrupt() } }
}

/** 后台算好的一次过滤结果。indices 是 state 里的下标，换了 state 就不能直接用。 */
internal class Filtered(val state: RepoState, val query: String, val indices: List<Int>)

/**
 * 列表里显示哪些提交。新结果还没算好时先用上一次的：
 * 同一个 state 直接用（只是慢一拍）；state 换了（翻页、刷新），按 id 换算到新列表的下标。
 * 一次都还没算过时先显示全部。
 */
internal fun visibleCommits(state: RepoState, query: String, filtered: Filtered?): List<Int> = when {
    query.isBlank() || filtered == null -> state.result.commits.indices.toList()
    filtered.state === state -> filtered.indices
    else -> filtered.indices.mapNotNull { state.indexById[filtered.state.result.commits[it].id] }.sorted()
}

/** 搜索说明、作者、哈希和引用名，返回匹配提交在完整列表里的下标。 */
internal fun filterCommits(commits: List<CommitInfo>, query: String): List<Int> {
    val q = query.trim()
    if (q.isEmpty()) return commits.indices.toList()
    return commits.indices.filter { i ->
        val c = commits[i]
        matchesQuery(q, c.shortMessage, c.author, c.id, c.refs.map { it.name })
    }
}

/** 正在加载、要找到的提交。 */
private class Seek(val id: String, val job: Job)
