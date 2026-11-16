package gitviz.ui.tools

import gitviz.git.*
import gitviz.platform.*
import gitviz.logic.*
import gitviz.service.*
import gitviz.ui.*
import gitviz.ui.changes.*
import gitviz.ui.history.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.focusable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val StatusColumn = 110.dp
private val SizeColumn = 84.dp
private val TimeColumn = 110.dp

/**
 * 文件：一棵带列的树，文件夹原地展开，列出状态、大小、修改时间；被忽略的也列出来。
 * 目录结构直接读磁盘，git status 只用来标状态、补上已删除的文件。不预览内容，双击文件交给系统打开。
 * 顶部可以按名称筛选：后台把所有没被忽略的目录扫一遍，匹配的路径自动展开。
 * reloadKey 变了（工作区状态重读过）就重新读一遍。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilesView(
    cli: GitCli,
    root: File,
    reloadKey: Any,
    onShowChanges: (String) -> Unit,
    onShowHistory: (String) -> Unit,
    onFailure: (title: String, detail: String) -> Unit,
    modifier: Modifier,
    onRun: OperationRunner,
    onSuccess: (String) -> Unit,
    busy: Boolean = false,
    /** 一开始展开到哪个目录并选中它，默认不展开。 */
    start: String = "",
    /** 一开始的筛选词，默认不筛选。 */
    filter: String = "",
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showIgnored by remember { mutableStateOf(Settings.showIgnoredFiles) }
    var expanded by remember(root) { mutableStateOf(if (start.isEmpty()) emptySet() else (ancestorsOf(start) + start).toSet()) }
    var selected by remember(root) { mutableStateOf(start.ifEmpty { null }) }
    var query by remember(root) { mutableStateOf(filter) }
    var status by remember(root) { mutableStateOf<TreeStatus?>(null) }
    var listings by remember(root) { mutableStateOf(emptyMap<String, DirListing>()) }
    // listings 是按哪一次 status 读的；status 换了就要整个重读
    var listedWith by remember(root) { mutableStateOf<TreeStatus?>(null) }
    // 筛选用的全量扫描，也是按哪一次 status 扫的；扫的时候旧结果先留着，扫完再换，不闪
    var scan by remember(root) { mutableStateOf<TreeScan?>(null) }
    var scannedWith by remember(root) { mutableStateOf<TreeStatus?>(null) }
    var revision by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf<List<MenuEntry>?>(null) }
    var dialog by remember { mutableStateOf<DialogSpec?>(null) }
    val filtering = query.isNotBlank()
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(cli, reloadKey, revision) {
        status = withContext(Dispatchers.IO) { TreeStatus(cli.treeStatus()) }
    }

    LaunchedEffect(status, expanded) {
        val st = status ?: return@LaunchedEffect
        val stale = listedWith !== st
        val wanted = directoriesToRead(expanded)
        val toRead = if (stale) wanted else wanted.filter { it !in listings }
        if (toRead.isEmpty()) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { toRead.associateWith { dir -> runCatching { listDirectory(root, dir, st) }.getOrNull() } }
        ensureActive()
        // 展开着的目录被删掉了（磁盘上没有、git 也不认识），收起它
        val gone = read.filter { (dir, listing) -> dir.isNotEmpty() && (listing == null || !File(root, dir).isDirectory && listing.entries.isEmpty()) }.keys
        val fresh = read.filterValues { it != null }.mapValues { it.value!! }
        listings = (if (stale) fresh else listings + fresh) - gone
        listedWith = st
        if (gone.isNotEmpty()) expanded = expanded - gone
    }

    LaunchedEffect(status, filtering) {
        val st = status ?: return@LaunchedEffect
        if (!filtering || scannedWith === st) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            scanWorkTree(root, st, cancelled = { !context.isActive })
        }
        ensureActive()
        scan = result
        scannedWith = st
    }

    val rows = remember(listings, expanded, showIgnored, scan, query) {
        if (filtering) scan?.let { filterTree(it, query, showIgnored) }.orEmpty() else flattenDirTree(listings, expanded, showIgnored)
    }
    val items = remember(rows) { rows.filterIsInstance<DirRow.Item>() }

    fun toggle(entry: FileEntry) {
        if (!entry.isDir || filtering) return
        val closing = entry.path in expanded
        expanded = if (closing) expanded - entry.path else expanded + entry.path
        // 收起的目录里有选中项，选中移到这个目录上，免得选中项看不见
        if (closing && selected?.startsWith(entry.path + "/") == true) selected = entry.path
    }

    fun select(path: String) {
        selected = path
        val index = rows.indexOfFirst { it.key == path }
        if (index >= 0) scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isNotEmpty() && (index <= visible.first().index || index >= visible.last().index)) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    fun systemOpen(entry: FileEntry) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching { openWithSystem(File(root, entry.path)) }.exceptionOrNull() }
            if (error != null && error !is CancellationException) {
                onFailure(t("Couldn't open ${entry.name}", "打不开 ${entry.name}"), error.message ?: error.toString())
            }
        }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDir) toggle(entry)
        else if (entry.missing) onFailure(t("${entry.name} was deleted", "${entry.name} 已删除"), t("It's no longer on disk. You can restore it in Changes.", "磁盘上已经没有这个文件，可以在“本地改动”里恢复。"))
        else systemOpen(entry)
    }

    fun runIgnore(entry: FileEntry, target: IgnoreTarget, stopTracking: Boolean = false) {
        val file = if (target == IgnoreTarget.GITIGNORE) ".gitignore" else ".git/info/exclude"
        val done = if (stopTracking) t("Added ${entry.name} to $file and stopped tracking its files", "已将 ${entry.name} 加入 $file，并停止跟踪其中的文件")
            else t("Added ${entry.name} to $file", "已将 ${entry.name} 加入 $file")
        onRun(t("Updating $file", "正在更新 $file"), done, { ignore(listOf(entry.path), entry.isDir, target, stopTracking) }) { revision++ }
    }

    // 文件夹里有已跟踪的文件时，忽略规则管不到它们：先说明，写 .gitignore 时可以顺手停止跟踪
    fun ignoreEntry(entry: FileEntry, target: IgnoreTarget) {
        if (!entry.isDir) return runIgnore(entry, target)
        scope.launch {
            val tracked = withContext(Dispatchers.IO) { cli.trackedCount(entry.path) }
            if (tracked == 0) return@launch runIgnore(entry, target)
            val rule = ignoreRule(entry.path, true)
            val files = countOf(tracked, "file")
            if (target == IgnoreTarget.GITIGNORE) {
                dialog = DialogSpec(
                    t("Ignore folder", "忽略文件夹"),
                    t("Add $rule to .gitignore. Already tracked here: $files. The rule doesn't apply to tracked files, so they stay tracked.",
                        "把 $rule 加入 .gitignore。其中 $tracked 个文件已在版本控制中，忽略规则对它们不生效，会继续跟踪。"),
                    t("Ignore", "忽略"),
                    option = DialogOption(
                        t("Also stop tracking these $files (keep local copies)", "同时停止跟踪这 $tracked 个文件（保留本地文件）"),
                        checked = false,
                        note = t("The removals are staged for you to commit. After you commit and push, anyone who pulls will have these files deleted from their disk.",
                            "删除记录会进入暂存区，等你提交。提交并推送后，其他人拉取时这些文件会从他们的磁盘上删掉。"),
                    ) { _, stop -> runIgnore(entry, target, stop) },
                )
            } else {
                dialog = DialogSpec(
                    t("Ignore folder locally", "仅本机忽略文件夹"),
                    t("Add $rule to .git/info/exclude (this computer only). Already tracked here: $files. The rule doesn't apply to tracked files, so they stay tracked.",
                        "把 $rule 加入 .git/info/exclude（只在本机生效）。其中 $tracked 个文件已在版本控制中，忽略规则对它们不生效，会继续跟踪。"),
                    t("Ignore", "忽略"),
                ) { _, _ -> runIgnore(entry, target) }
            }
        }
    }

    fun entryMenu(entry: FileEntry): List<MenuEntry> {
        val file = File(root, entry.path)
        val items = ArrayList<MenuEntry>()
        if (!entry.missing) {
            items += MenuItem(if (entry.isDir) t(if (entry.path in expanded) "Collapse" else "Expand", if (entry.path in expanded) "收起" else "展开") else t("Open with default app", "用默认程序打开"), enabled = !entry.isDir || !filtering) {
                openEntry(entry)
            }
            items += MenuItem(t(if (isMac) "Show in Finder" else "Show in folder", if (isMac) "在 Finder 中显示" else "在文件夹中显示")) {
                scope.launch {
                    val error = withContext(Dispatchers.IO) { runCatching { revealInFileManager(file) }.exceptionOrNull() }
                    if (error is CancellationException) throw error
                    if (error != null) onFailure(t("Couldn't show ${entry.name}", "无法显示 ${entry.name}"), error.message ?: error.toString())
                }
            }
            items += MenuSeparator
        }
        items += MenuItem(t("Copy path", "复制路径")) { clipboard.setText(AnnotatedString(file.path)); onSuccess(t("Path copied", "已复制路径")) }
        items += MenuItem(t("Copy relative path", "复制相对路径")) { clipboard.setText(AnnotatedString(entry.path)); onSuccess(t("Relative path copied", "已复制相对路径")) }
        val working = status?.working(entry.path)
        val showChanges = !entry.isDir && working != null && !working.conflicted && !entry.ignored
        val showHistory = !entry.ignored && entry.mark != FileMark.UNTRACKED && entry.mark != FileMark.ADDED && !(entry.isDir && entry.missing)
        // 已被忽略、或者文件已经在版本控制中（忽略对它不生效）的不给忽略；文件夹里有已跟踪的，点了再说明
        val canIgnore = !entry.ignored && !entry.missing &&
            (entry.isDir || entry.mark == FileMark.UNTRACKED || entry.mark == FileMark.REMOVED)
        if (showChanges || showHistory || canIgnore) items += MenuSeparator
        if (showChanges) items += MenuItem(t("Show changes", "查看改动")) { onShowChanges(entry.path) }
        if (showHistory) items += MenuItem(t("Show history", "查看历史")) { onShowHistory(entry.path) }
        if (canIgnore) {
            items += MenuItem(t("Ignore: add to .gitignore", "忽略：加入 .gitignore"), enabled = !busy) { ignoreEntry(entry, IgnoreTarget.GITIGNORE) }
            items += MenuItem(t("Ignore locally: add to .git/info/exclude", "仅本机忽略：加入 .git/info/exclude"), enabled = !busy) { ignoreEntry(entry, IgnoreTarget.EXCLUDE) }
        }
        return items
    }


    // 键盘：上下移动；右键展开目录（已展开就进到第一项），左键收起目录（已收起就回到上一级），回车打开
    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.isMetaPressed || event.isCtrlPressed || event.isAltPressed || items.isEmpty()) return false
        val at = items.indexOfFirst { it.entry.path == selected }
        val current = items.getOrNull(at)
        when (event.key) {
            Key.DirectionDown, Key.J -> select(items[(at + 1).coerceIn(items.indices)].entry.path)
            Key.DirectionUp, Key.K -> select(items[(if (at < 0) 0 else at - 1).coerceIn(items.indices)].entry.path)
            Key.DirectionRight -> if (current?.entry?.isDir == true) { if (current.expanded != true) toggle(current.entry) else items.getOrNull(at + 1)?.let { select(it.entry.path) } } else return false
            Key.DirectionLeft -> if (current != null) {
                if (current.entry.isDir && current.expanded == true && !filtering) toggle(current.entry)
                else parentOf(current.entry.path).takeIf { it.isNotEmpty() }?.let { select(it) }
            } else return false
            Key.Enter -> current?.let { openEntry(it.entry) } ?: return false
            else -> return false
        }
        return true
    }

    Column(modifier) {
        // 工具条：筛选、显示被忽略的、全部折叠、刷新
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SearchField(query, { query = it }, onEscape = { query = ""; listFocus.requestFocus() }, width = 300.dp,
                placeholder = t("Filter by name", "按名称筛选，匹配的路径自动展开"))
            val toggleIgnored = { showIgnored = !showIgnored; Settings.updateShowIgnoredFiles(showIgnored) }
            Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = toggleIgnored), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CheckBox(if (showIgnored) CheckState.ALL else CheckState.NONE)
                Text(t("Show ignored", "显示被忽略的"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
            Spacer(Modifier.weight(1f))
            val summary = when {
                filtering && scan == null -> t("Searching…", "正在搜索…")
                filtering -> t(countOf(items.count { it.hit != null }, "match", "matches"), "${items.count { it.hit != null }} 个匹配") +
                    if (scan?.truncated == true) t(" (incomplete: too many files)", "（不完整：文件太多）") else ""
                else -> status?.changeCount?.takeIf { it > 0 }?.let { t(countOf(it, "changed file"), "$it 个文件有改动") }.orEmpty()
            }
            Text(summary, fontSize = TypeScale.Caption, color = Palette.Muted, maxLines = 1)
            Text(t("Collapse all", "全部折叠"), fontSize = TypeScale.Small, color = Palette.Muted,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).hoverRow().clickable(enabled = !filtering && expanded.isNotEmpty()) { expanded = emptySet(); selected = selected?.substringBefore('/') }.padding(horizontal = 8.dp, vertical = 5.dp))
            IconButton(Icons.Refresh) { revision++ }
        }
        HorizontalDivider()
        TableHeader()
        HorizontalDivider()
        val now = rememberNowSeconds()
        Box(Modifier.fillMaxWidth().weight(1f).focusRequester(listFocus).onKeyEvent(::onKey).focusable()) {
            when {
                filtering && scan == null -> AfterDelay { Message(t("Searching…", "正在搜索…"), Modifier.fillMaxSize()) }
                filtering && rows.isEmpty() -> Message(
                    t("No file names contain “${query.trim()}”", "没有名称包含“${query.trim()}”的文件") +
                        if (!showIgnored) t(" (ignored files are hidden)", "（被忽略的文件已隐藏）") else "",
                    Modifier.fillMaxSize())
                !filtering && listings[""] == null -> AfterDelay { Message(t("Loading…", "正在读取…"), Modifier.fillMaxSize()) }
                else -> {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(rows, key = { it.key }) { row ->
                            when (row) {
                                is DirRow.Loading -> AfterDelay { RowNote(t("Loading…", "正在读取…"), row.depth) }
                                is DirRow.Note -> RowNote(row.text, row.depth)
                                is DirRow.Item -> TreeTableRow(
                                    row, now, row.entry.path == selected,
                                    onClick = { selected = row.entry.path; listFocus.requestFocus() },
                                    onToggle = { selected = row.entry.path; toggle(row.entry); listFocus.requestFocus() },
                                    onOpen = { openEntry(row.entry) },
                                    onMenu = { selected = row.entry.path; menu = entryMenu(row.entry) },
                                )
                            }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(selected ?: "", fontSize = TypeScale.Caption, fontFamily = CodeFont, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(t("↑↓ move · ←→ collapse/expand · Enter open · right-click for more", "上下键移动 · 左右键收起／展开 · 回车打开 · 右键更多"), fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1)
        }
    }
    menu?.let { GitMenuPopup(it, onDismiss = { menu = null }) }
    dialog?.let { ActionDialog(it) { dialog = null } }
}

/** 读目录一般只要几毫秒，“正在读取”一闪而过反而像在闪烁，所以过一会儿还没读完才显示。之前不占位置。 */
private const val LOADING_DELAY_MS = 200L

@Composable
private fun AfterDelay(content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MS)
        show = true
    }
    if (show) content()
}

/** 目录下面的提示行：空文件夹、还有多少项没列出、正在读取。 */
@Composable
private fun RowNote(text: String, depth: Int) {
    Text(text, fontSize = TypeScale.Small, color = Palette.Faint,
        modifier = Modifier.height(26.dp).padding(start = 14.dp + 18.dp * depth + 38.dp).wrapContentHeight())
}

@Composable
private fun Chevron(expanded: Boolean) {
    val color = Palette.Muted
    Canvas(Modifier.size(10.dp).rotate(if (expanded) 90f else 0f)) {
        val c = Offset(size.width / 2, size.height / 2)
        val arm = 2.5.dp.toPx()
        val path = Path().apply {
            moveTo(c.x - arm / 2, c.y - arm)
            lineTo(c.x + arm / 2, c.y)
            lineTo(c.x - arm / 2, c.y + arm)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("Name", "名称"), fontSize = TypeScale.Caption, color = Palette.Faint, modifier = Modifier.weight(1f))
        HeaderCell(t("Status", "状态"), StatusColumn)
        HeaderCell(t("Size", "大小"), SizeColumn)
        HeaderCell(t("Modified", "修改时间"), TimeColumn)
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(text, fontSize = TypeScale.Caption, color = Palette.Faint, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(width))
}

/** 树表的一行：缩进、展开箭头、图标、名字（筛选时高亮匹配的一段），右边是状态、大小、修改时间。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeTableRow(item: DirRow.Item, now: Long, selected: Boolean, onClick: () -> Unit, onToggle: () -> Unit, onOpen: () -> Unit, onMenu: () -> Unit) {
    val entry = item.entry
    val ink = if (entry.ignored) Palette.Faint else Palette.Ink
    Row(
        Modifier.fillMaxWidth().height(30.dp).hoverRow().rowHighlight(selected)
            .onRightClick(onMenu)
            .combinedClickable(onClick = onClick, onDoubleClick = onOpen)
            .padding(start = 14.dp + 18.dp * item.depth, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(14.dp).then(if (item.expanded != null) Modifier.clickable(onClick = onToggle) else Modifier), contentAlignment = Alignment.Center) {
                item.expanded?.let { Chevron(it) }
            }
            Icon(if (entry.isDir) Icons.Folder else Icons.File, if (entry.ignored) Palette.Faint else Palette.Muted, size = 15.dp)
            val name = remember(entry.name, item.hit) {
                buildAnnotatedString {
                    val hit = item.hit
                    if (hit == null) append(entry.name) else {
                        append(entry.name.substring(0, hit.first))
                        withStyle(SpanStyle(background = Palette.Raised, fontWeight = FontWeight.SemiBold)) { append(entry.name.substring(hit.first, hit.last + 1)) }
                        append(entry.name.substring(hit.last + 1))
                    }
                }
            }
            Text(name, fontSize = TypeScale.Body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (entry.isDir) FontWeight.Medium else FontWeight.Normal,
                textDecoration = if (entry.missing) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f, fill = false))
            // 文件夹里有改动：只在名字后面加一个小黄点，状态列留给文件本身
            if (entry.isDir && entry.dirty && !entry.ignored) Dot(Palette.Modified)
        }
        val (label, color) = statusLabel(entry)
        Text(label, fontSize = TypeScale.Small, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(StatusColumn))
        Text(if (entry.isDir || entry.missing) "" else formatSize(entry.size), fontSize = TypeScale.Small, color = Palette.Muted,
            textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(SizeColumn))
        Box(Modifier.width(TimeColumn), contentAlignment = Alignment.CenterEnd) {
            if (entry.modified > 0) RelativeTime(entry.modified / 1000, now)
        }
    }
}

private fun statusLabel(e: FileEntry): Pair<String, Color> = when (e.mark) {
    FileMark.MODIFIED -> t("Modified", "已修改") to Palette.Modified
    FileMark.ADDED -> t("Added", "新增") to Palette.Added
    FileMark.DELETED -> t("Deleted", "已删除") to Palette.Removed
    FileMark.RENAMED -> t("Renamed", "重命名") to Palette.Renamed
    FileMark.UNTRACKED -> t("Untracked", "未跟踪") to Palette.Link
    FileMark.CONFLICTED -> t("Conflict", "冲突") to Palette.Removed
    FileMark.REMOVED -> t("Untracked now", "已移出版本库") to Palette.Removed
    FileMark.IGNORED -> (if (e.ignoredRoot) t("Ignored", "已忽略") else "") to Palette.Faint
    FileMark.CLEAN -> "" to Palette.Modified
}

/** 用系统默认程序打开。 */
private fun openWithSystem(file: File) {
    if (isMac) {
        runSystem("open", file.path)
    } else {
        java.awt.Desktop.getDesktop().open(file)
    }
}
