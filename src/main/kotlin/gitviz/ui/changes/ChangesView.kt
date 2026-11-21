package gitviz.ui.changes

import gitviz.git.*
import gitviz.logic.*
import gitviz.service.*
import gitviz.ui.*
import gitviz.ui.history.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Selection(val path: String, val staged: Boolean)

private val IndentStep = 14.dp
private val ListRowHeight = 26.dp
private val MinListWidth = 260.dp
private val MaxListWidth = 560.dp

/** 每行右边勾选框所在的格子；右边另留 10dp 给竖向滚动条。 */
private val CheckCellWidth = 34.dp
private val ScrollbarGutter = 10.dp

/** 一组（未暂存或已暂存）自己的勾选和收起状态。勾选按路径记，切换平铺和按文件夹时不会丢。 */
private class GroupState {
    var checked by mutableStateOf(emptySet<String>())
    var collapsed by mutableStateOf(emptySet<String>())

    /** 文件换了组、被提交或者不见了，它的勾选也就没意义了。 */
    fun prune(files: List<WorkingFile>) {
        val live = files.mapTo(HashSet()) { it.path }
        if (!live.containsAll(checked)) checked = checked.filterTo(HashSet()) { it in live }
    }
}

/** 本地改动：未暂存、已暂存两个列表，提交说明，右边是所选文件的 diff。 */
@Composable
internal fun ChangesView(
    cli: GitCli,
    files: List<WorkingFile>,
    branch: String?,
    onChanged: (historyChanged: Boolean) -> Unit,
    onRun: OperationRunner,
    modifier: Modifier,
    busy: Boolean = false,
    externalRevision: Int = 0,
    draft: CommitDraft = remember(cli) { CommitDraft() },
    /** 从别处（比如文件页）带过来、要选中的文件。选中后调用 onFocused。 */
    focus: String? = null,
    onFocused: () -> Unit = {},
    onAi: (() -> Unit)? = null,
) {
    var selection by remember(cli) { mutableStateOf<Selection?>(null) }
    LaunchedEffect(focus) {
        val path = focus ?: return@LaunchedEffect
        files.firstOrNull { it.path == path }?.let { selection = Selection(it.path, staged = !it.hasUnstaged) }
        onFocused()
    }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }
    var toDiscard by remember { mutableStateOf<List<WorkingFile>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    val tree = Settings.changesAsTree
    val unstagedGroup = remember(cli) { GroupState() }
    val stagedGroup = remember(cli) { GroupState() }

    val unstaged = files.filter { it.hasUnstaged }
    val staged = files.filter { it.hasStaged }
    LaunchedEffect(unstaged) { unstagedGroup.prune(unstaged) }
    LaunchedEffect(staged) { stagedGroup.prune(staged) }

    fun apply(running: String, success: String, historyChanged: Boolean = false, op: GitCli.() -> GitResult, done: () -> Unit = {}) {
        onRun(running, success, op) { ok ->
            if (ok) done()
            onChanged(historyChanged)
            revision++
        }
    }

    // 忽略只对新文件生效：所选里已跟踪的跳过，全是已跟踪的就禁用
    fun ignoreItems(picked: List<WorkingFile>): List<MenuEntry> {
        val fresh = picked.filter { it.untracked }
        val subject = if (fresh.isNotEmpty() && fresh.size < picked.size) t("Ignore ${countOf(fresh.size, "new file")}", "忽略所选 ${fresh.size} 个新文件")
            else t("Ignore selected", "忽略所选")
        fun item(target: IgnoreTarget): MenuItem {
            val file = if (target == IgnoreTarget.GITIGNORE) ".gitignore" else ".git/info/exclude"
            val label = if (target == IgnoreTarget.GITIGNORE) t("$subject: add to $file", "$subject：加入 $file")
                else t("$subject locally: add to $file", "$subject（仅本机）：加入 $file")
            return MenuItem(label, enabled = !busy && fresh.isNotEmpty()) {
                val done = if (fresh.size == 1) t("Added ${fresh.single().path} to $file", "已将 ${fresh.single().path} 加入 $file")
                    else t("Added ${countOf(fresh.size, "file")} to $file", "已将 ${fresh.size} 个文件加入 $file")
                apply(t("Updating $file", "正在更新 $file"), done, op = { ignore(fresh.map { it.path }, false, target) })
            }
        }
        return listOf(item(IgnoreTarget.GITIGNORE), item(IgnoreTarget.EXCLUDE))
    }

    fun stageFiles(list: List<WorkingFile>) = apply(
        t("Staging", "正在暂存"),
        if (list.size == 1) t("Staged ${list.single().path}", "已暂存 ${list.single().path}") else t("Staged ${countOf(list.size, "file")}", "已暂存 ${list.size} 个文件"),
        op = { stage(list) },
    ) { unstagedGroup.checked -= list.map { it.path }.toSet() }

    fun unstageFiles(list: List<WorkingFile>) = apply(
        t("Unstaging", "正在取消暂存"),
        if (list.size == 1) t("Unstaged ${list.single().path}", "已取消暂存 ${list.single().path}") else t("Unstaged ${countOf(list.size, "file")}", "已取消暂存 ${list.size} 个文件"),
        op = { unstage(list) },
    ) { stagedGroup.checked -= list.map { it.path }.toSet() }

    // 选中的文件没了（比如已提交）就回到第一个
    val current = selection?.takeIf { s -> (if (s.staged) staged else unstaged).any { it.path == s.path } }
        ?: unstaged.firstOrNull()?.let { Selection(it.path, false) }
        ?: staged.firstOrNull()?.let { Selection(it.path, true) }
    val currentFile = current?.let { s -> files.firstOrNull { it.path == s.path } }

    val unstagedFolders = remember(unstaged, tree) { if (tree) allFolderPaths(unstaged) else emptySet() }
    val stagedFolders = remember(staged, tree) { if (tree) allFolderPaths(staged) else emptySet() }
    val anyFolderOpen = (unstagedFolders - unstagedGroup.collapsed).isNotEmpty() || (stagedFolders - stagedGroup.collapsed).isNotEmpty()

    BoxWithConstraints(modifier) {
        // 左栏最窄 260dp，最宽 560dp，并且不超过这一页宽度的一半，右边的 diff 始终有地方
        val maxList = maxOf(MinListWidth, minOf(MaxListWidth, maxWidth / 2))
        var dragged by remember { mutableStateOf(Settings.changesWidth.dp) }
        val listWidth = dragged.coerceIn(MinListWidth, maxList)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    ChangesToolbar(tree, anyFolderOpen) {
                        if (anyFolderOpen) {
                            unstagedGroup.collapsed = unstagedFolders
                            stagedGroup.collapsed = stagedFolders
                        } else {
                            unstagedGroup.collapsed = emptySet()
                            stagedGroup.collapsed = emptySet()
                        }
                    }
                    HorizontalDivider()
                    ChangeGroup(
                        title = t("Unstaged", "未暂存"), empty = t("Nothing unstaged", "没有未暂存的改动"),
                        files = unstaged, staged = false, group = unstagedGroup, tree = tree, current = current,
                        onSelect = { selection = Selection(it.path, false) }, modifier = Modifier.weight(1f),
                        batchMenu = { picked ->
                            listOf(
                                MenuItem(t("Stage selected", "暂存所选"), enabled = !busy && picked.isNotEmpty()) { stageFiles(picked) },
                                MenuItem(t("Discard selected…", "丢弃所选…"), enabled = !busy && picked.isNotEmpty(), danger = true) { toDiscard = picked },
                                MenuSeparator,
                            ) + ignoreItems(picked)
                        },
                    )
                    HorizontalDivider()
                    ChangeGroup(
                        title = t("Staged", "已暂存"), empty = t("Nothing staged", "没有已暂存的改动"),
                        files = staged, staged = true, group = stagedGroup, tree = tree, current = current,
                        onSelect = { selection = Selection(it.path, true) }, modifier = Modifier.weight(1f),
                        batchMenu = { picked ->
                            listOf(MenuItem(t("Unstage selected", "取消暂存所选"), enabled = !busy && picked.isNotEmpty()) { unstageFiles(picked) })
                        },
                    )
                    HorizontalDivider()
                    CommitBox(cli, branch, staged.size, !busy, draft, onAi, onCommit = { message, amend, done ->
                        apply(t("Committing", "正在提交"), t("Committed", "已提交"), historyChanged = true, op = { commit(message, amend) }, done = done)
                    })
                }
                // 拖右边缘调整宽度，松手后记住
                WidthDragHandle(t("Drag to resize the list", "拖动调整列表宽度"), Modifier.align(Alignment.CenterEnd),
                    onDrag = { dragged = (listWidth + it).coerceIn(MinListWidth, maxList) },
                    onDragStopped = { Settings.updateChangesWidth(dragged.value.roundToInt()) })
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight().background(Palette.Panel)) {
                if (current == null || currentFile == null) {
                    Message(t("Working tree is clean", "工作区是干净的"), Modifier.fillMaxSize())
                } else {
                    Row(
                        Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(current.path, fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Muted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode) { mode = it }
                    }
                    HorizontalDivider()
                    val content = Modifier.fillMaxWidth().weight(1f)
                    PartialStageView(cli, currentFile, current.staged, revision + externalRevision, mode, busy,
                        onApply = { document, selected -> apply(t("Updating selected changes", "正在更新选中改动"), t("Selected changes updated", "选中改动已更新"), op = { applySelection(document, selected) }) }, modifier = content)
                }
            }
        }
    }

    if (toDiscard.isNotEmpty()) {
        val list = toDiscard
        AlertDialog(
            onDismissRequest = { toDiscard = emptyList() },
            title = { Text(t("Discard changes", "丢弃改动"), fontSize = TypeScale.Title, fontWeight = FontWeight.SemiBold) },
            text = { Text(discardMessage(list), fontSize = TypeScale.Body) },
            confirmButton = {
                DialogButton(t("Discard", "丢弃"), Palette.Removed) {
                    toDiscard = emptyList()
                    val done = if (list.size == 1) t("Discarded changes to ${list.single().path}", "已丢弃 ${list.single().path} 的改动")
                        else t("Discarded changes to ${countOf(list.size, "file")}", "已丢弃 ${list.size} 个文件的改动")
                    apply(t("Discarding", "正在丢弃"), done, op = { discard(list) }) { unstagedGroup.checked -= list.map { it.path }.toSet() }
                }
            },
            dismissButton = { DialogButton(t("Cancel", "取消")) { toDiscard = emptyList() } },
            containerColor = Palette.Field,
            shape = RoundedCornerShape(10.dp),
        )
    }
}

/** 丢弃前的确认文字：写清楚几个文件，以及其中有几个新文件会被直接删掉。 */
internal fun discardMessage(files: List<WorkingFile>): String {
    val fresh = files.count { it.untracked }
    if (files.size == 1) {
        val f = files.single()
        return if (f.untracked) t("“${f.path}” is a new file. It will be permanently deleted.", "「${f.path}」是新文件，将被永久删除。")
            else t("Unstaged changes to “${f.path}” will be permanently discarded.", "「${f.path}」未暂存的改动将被永久丢弃。")
    }
    val base = t("Unstaged changes to ${countOf(files.size, "file")} will be permanently discarded.", "${files.size} 个文件未暂存的改动将被永久丢弃。")
    return if (fresh == 0) base else base + t(" ${countOf(fresh, "new file")} will be permanently deleted.", "其中 $fresh 个新文件会被永久删除。")
}

@Composable
private fun ChangesToolbar(tree: Boolean, anyFolderOpen: Boolean, onToggleFolders: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(t("Changes", "本地改动"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
        if (tree) {
            Hint(if (anyFolderOpen) t("Collapse all folders", "收起全部文件夹") else t("Expand all folders", "展开全部文件夹")) {
                IconButton(if (anyFolderOpen) Icons.CollapseAll else Icons.ExpandAll, onClick = onToggleFolders)
            }
        }
        ViewSwitch(tree)
    }
}

/** 平铺和按文件夹两种看法，选择记在设置里。 */
@Composable
private fun ViewSwitch(tree: Boolean) {
    val shape = RoundedCornerShape(5.dp)
    Row(Modifier.border(1.dp, Palette.Line, shape).clip(shape)) {
        for ((asTree, icon, label) in listOf(Triple(false, Icons.List, t("List", "平铺")), Triple(true, Icons.Folder, t("By folder", "按文件夹")))) {
            Hint(label) {
                Box(
                    Modifier.size(width = 28.dp, height = 22.dp).background(if (asTree == tree) Palette.Raised else Color.Transparent)
                        .clickable(role = Role.RadioButton) { Settings.updateChangesAsTree(asTree) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, if (asTree == tree) Palette.Ink else Palette.Muted, size = 14.dp)
                }
            }
        }
    }
}

/**
 * 一组改动：标题、文件列表、底栏。
 * 列表可以左右滑动：每一行的内容放在各自的滚动区里，共用同一个滚动位置，勾选框在滚动区外面，滑动时不动。
 */
@Composable
private fun ChangeGroup(
    title: String,
    empty: String,
    files: List<WorkingFile>,
    staged: Boolean,
    group: GroupState,
    tree: Boolean,
    current: Selection?,
    onSelect: (WorkingFile) -> Unit,
    batchMenu: (picked: List<WorkingFile>) -> List<MenuEntry>,
    modifier: Modifier,
) {
    val rows = remember(files, tree, group.collapsed) { flattenChanges(files, tree, group.collapsed) }
    val picked = files.filter { it.path in group.checked }
    val allState = checkState(files.map { it.path }, group.checked)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$title · ${files.size}", fontSize = TypeScale.Small, color = Palette.Muted)
        }
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (files.isEmpty()) Text(empty, fontSize = TypeScale.Small, color = Palette.Faint, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            else ChangeList(rows, staged, tree, group, current, onSelect)
        }
        HorizontalDivider()
        GroupFooter(allState, picked.size, files.isNotEmpty(), batchMenu(picked)) {
            group.checked = if (allState == CheckState.ALL) emptySet() else files.mapTo(HashSet()) { it.path }
        }
    }
}

@Composable
private fun ChangeList(rows: List<ChangeRow>, staged: Boolean, tree: Boolean, group: GroupState, current: Selection?, onSelect: (WorkingFile) -> Unit) {
    val listState = rememberLazyListState()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // 所有行取同一个宽度（最宽的那一行），横向滚动范围才一致
    val contentWidth = remember(rows, tree, density) { widestRow(rows, tree, measurer, density) }
    val overflows = scroll.maxValue in 1 until Int.MAX_VALUE
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = ScrollbarGutter), state = listState,
            contentPadding = PaddingValues(bottom = if (overflows) 10.dp else 0.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                val paths = if (row is ChangeRow.Folder) row.paths else listOf((row as ChangeRow.File).file.path)
                val state = checkState(paths, group.checked)
                ChangeLine(
                    row, tree, staged, selected = row is ChangeRow.File && current == Selection(row.file.path, staged),
                    state = state, contentWidth = contentWidth, scroll = scroll,
                    onClick = {
                        when (row) {
                            is ChangeRow.File -> onSelect(row.file)
                            is ChangeRow.Folder -> group.collapsed = if (row.collapsed) group.collapsed - row.path else group.collapsed + row.path
                        }
                    },
                    onToggle = { group.checked = if (state == CheckState.ALL) group.checked - paths.toSet() else group.checked + paths },
                )
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        if (overflows) HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(end = ScrollbarGutter + CheckCellWidth))
    }
}

@Composable
private fun ChangeLine(
    row: ChangeRow, tree: Boolean, staged: Boolean, selected: Boolean, state: CheckState,
    contentWidth: Dp, scroll: ScrollState, onClick: () -> Unit, onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(ListRowHeight).hoverRow().rowHighlight(selected).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.width(contentWidth).padding(start = RowStart, end = RowEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                Indent(row.depth)
                when (row) {
                    is ChangeRow.Folder -> {
                        Icon(Icons.ChevronDown, Palette.Muted, Modifier.rotate(if (row.collapsed) -90f else 0f), size = 12.dp, strokeWidth = 3f)
                        Icon(Icons.Folder, Palette.Muted, size = 14.dp)
                        Text(row.label, fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1, softWrap = false)
                        Text("${row.paths.size}", fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, softWrap = false)
                    }
                    is ChangeRow.File -> {
                        // 按文件夹时给箭头留出位置，文件名才和文件夹名对齐
                        if (tree) Spacer(Modifier.width(12.dp))
                        val (tag, color) = statusBadge(if (staged) row.file.index else row.file.worktree)
                        ChangeBadge(tag, color)
                        Hint(row.file.path) {
                            Text(row.file.path.substringAfterLast('/'), fontSize = TypeScale.Body, maxLines = 1, softWrap = false)
                        }
                        if (!tree) row.file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        val name = when (row) { is ChangeRow.Folder -> row.label; is ChangeRow.File -> row.file.path.substringAfterLast('/') }
        CheckCell(state, t("Select $name", "勾选 $name"), Modifier.fillMaxHeight(), onToggle)
    }
}

/** 勾选框所在的格子，整格都能点。 */
@Composable
private fun CheckCell(state: CheckState, label: String, modifier: Modifier, onToggle: () -> Unit) {
    Box(
        modifier.width(CheckCellWidth)
            .triStateToggleable(
                state = when (state) { CheckState.ALL -> ToggleableState.On; CheckState.SOME -> ToggleableState.Indeterminate; CheckState.NONE -> ToggleableState.Off },
                // 不要点击涟漪：行本身已经有悬停底色，格子再闪一块方形的灰会很突兀
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Checkbox, onClick = onToggle,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CheckBox(state) }
}

/** 底栏：左边批量操作菜单，右边已选个数和全选。 */
@Composable
private fun GroupFooter(allState: CheckState, picked: Int, hasFiles: Boolean, menu: List<MenuEntry>, onToggleAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(30.dp).background(Palette.Rail).padding(start = 8.dp, end = ScrollbarGutter), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Hint(t("Batch actions", "批量操作")) {
                Box(
                    Modifier.size(width = 28.dp, height = 22.dp).clip(RoundedCornerShape(4.dp)).hoverRow()
                        .clickable(enabled = hasFiles, role = Role.Button) { open = true }
                        .alpha(if (hasFiles) 1f else 0.45f)
                        .semantics { contentDescription = t("Batch actions", "批量操作") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.More, Palette.Muted, size = 16.dp, strokeWidth = 4f) }
            }
            if (open) GitMenuPopup(menu, onDismiss = { open = false })
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (picked == 0) t("Select all", "全选") else t("$picked selected", "已选 $picked 个"),
            fontSize = TypeScale.Caption, color = Palette.Muted,
        )
        CheckCell(allState, t("Select all", "全选"), Modifier.height(30.dp).alpha(if (hasFiles) 1f else 0.45f), onToggleAll)
    }
}

/** 每深一层缩进一格，并在每一格画一条竖线，一眼看出谁在谁下面。 */
@Composable
private fun Indent(depth: Int) {
    Canvas(Modifier.width(IndentStep * depth).fillMaxHeight()) {
        val step = IndentStep.toPx()
        for (i in 0 until depth) {
            val x = i * step + step / 2
            drawLine(Palette.Line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}

private val RowStart = 10.dp
private val RowEnd = 16.dp
private val RowGap = 6.dp

/** 量出最长的一行要多宽。文字不截断，超出的部分靠横向滚动看。 */
private fun widestRow(rows: List<ChangeRow>, tree: Boolean, measurer: TextMeasurer, density: Density): Dp {
    fun text(value: String, size: TextUnit): Dp =
        with(density) { measurer.measure(value, TextStyle(fontSize = size, fontFamily = UiFont), maxLines = 1, softWrap = false).size.width.toDp() }
    var widest = 0.dp
    for (row in rows) {
        val parts = ArrayList<Dp>()
        parts += IndentStep * row.depth
        when (row) {
            is ChangeRow.Folder -> {
                parts += 12.dp; parts += 14.dp
                parts += text(row.label, TypeScale.Small); parts += text("${row.paths.size}", TypeScale.Caption)
            }
            is ChangeRow.File -> {
                if (tree) parts += 12.dp
                parts += 16.dp
                parts += text(row.file.path.substringAfterLast('/'), TypeScale.Body)
                if (!tree) row.file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { parts += text(it, TypeScale.Caption) }
            }
        }
        val width = RowStart + RowEnd + parts.fold(0.dp) { a, b -> a + b } + RowGap * (parts.size - 1) + 4.dp
        if (width > widest) widest = width
    }
    return widest
}

internal fun statusBadge(code: Char): Pair<String, Color> = when (code) {
    '?' -> "?" to Palette.Link
    'A' -> "A" to Palette.Added
    'D' -> "D" to Palette.Removed
    'R' -> "R" to Palette.Renamed
    'C' -> "C" to Palette.Copied
    'U' -> "U" to Palette.Removed
    else -> "M" to Palette.Modified
}

@Composable
private fun CommitBox(cli: GitCli, branch: String?, stagedCount: Int, enabled: Boolean, draft: CommitDraft, onAi: (() -> Unit)?, onCommit: (String, Boolean, () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var message by draft::message
    var amend by draft::amend
    val registry = LocalCommands.current
    val canCommit = enabled && message.isNotBlank() && (amend || stagedCount > 0)
    val shape = RoundedCornerShape(5.dp)
    fun commit() {
        if (!canCommit) return
        if (registry != null) { registry.execute("commit"); return }
        onCommit(message.trim(), amend) {
            message = ""
            amend = false
        }
    }

    Column(Modifier.fillMaxWidth().background(Palette.Rail).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Commit message", "提交说明"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
            Row(
                Modifier.clickable(enabled = enabled) {
                    amend = !amend
                    if (amend) scope.launch { message = withContext(Dispatchers.IO) { cli.lastCommitMessage() } } else message = ""
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(12.dp).background(if (amend) Palette.Accent else Palette.Field, RoundedCornerShape(3.dp)).border(1.dp, Palette.Line, RoundedCornerShape(3.dp)))
                Text(t("Amend last commit", "修改上一次提交"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
        }
        BasicTextField(
            message, { message = it }, enabled = enabled,
            textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth().height(110.dp).textInputFocus()
                // Cmd+Enter 提交；不能提交时也吞掉这个按键，免得插进一个换行
                .onPreviewKeyEvent { e ->
                    if (e.key != Key.Enter || !(e.isMetaPressed || e.isCtrlPressed)) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) commit()
                    true
                }
                .background(Palette.Ground, shape).border(1.dp, Palette.Line, shape).padding(8.dp),
        )
        if (onAi != null) TextButton(enabled = enabled && stagedCount > 0, onClick = onAi) { Text(t("Generate draft with AI…", "AI 生成草稿…")) }
        val target = branch ?: t("HEAD", "当前提交")
        Text(
            if (amend) t("Amend last commit", "修改上一次提交") else t("Commit to $target · ${countOf(stagedCount, "file")}", "提交到 $target · $stagedCount 个文件"),
            fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.fillMaxWidth().alpha(if (canCommit) 1f else 0.45f).clip(shape).background(Palette.Accent)
                .clickable(enabled = canCommit) { commit() }
                .padding(vertical = 6.dp),
        )
    }
}
