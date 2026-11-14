package gitviz.ui.tools

import gitviz.git.*
import gitviz.logic.*
import gitviz.ui.*
import gitviz.ui.history.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

/**
 * 比较两个引用：从分支右键「与当前分支比较」进入。
 * request.right 是被比较的分支（标题里排在前面），request.left 是当前分支。
 * 打开时把两边解析成固定提交；引用移动后（refs 变化）重新解析。
 */
@Composable internal fun ComparisonView(
    directory: File,
    request: CompareRequest,
    refs: List<RefTarget>,
    onRequest: (CompareRequest) -> Unit,
    onOpenCommit: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    var loaded by remember(directory, request, refs) { mutableStateOf<Result<ComparisonSession>?>(null) }
    LaunchedEffect(directory, request, refs) {
        var owned: ComparisonSession? = null
        try {
            withContext(Dispatchers.IO) { owned = ComparisonSession.open(directory, request) }
            loaded = Result.success(checkNotNull(owned))
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loaded = Result.failure(e) }
        finally { withContext(NonCancellable + Dispatchers.IO) { owned?.close() } }
    }
    val session = loaded?.getOrNull()
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(5.dp)).hoverRow().clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.ChevronLeft, Palette.Muted, size = 12.dp, strokeWidth = 2.5f)
                Text(t("All commits", "所有提交"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
            Text(t("Compare", "比较"), fontSize = TypeScale.Body, fontWeight = FontWeight.SemiBold)
            RefPill(request.rightLabel, primary = true)
            Text("…", color = Palette.Faint, fontSize = TypeScale.Body)
            RefPill(request.leftLabel, primary = false)
            Hint(t("Swap direction", "交换方向")) {
                IconButton(Icons.Swap) { onRequest(CompareRequest(request.right, request.left, request.rightLabel, request.leftLabel, request.generation + 1)) }
            }
            Spacer(Modifier.weight(1f))
            if (session != null) when (session.bases.size) {
                0 -> Text(t("No common ancestor", "没有共同祖先"), fontSize = TypeScale.Small, color = Palette.Modified)
                1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Common ancestor ", "共同祖先 "), fontSize = TypeScale.Small, color = Palette.Muted)
                    val base = session.bases.single()
                    Text(base.take(7), fontSize = TypeScale.Small, fontFamily = CodeFont, color = Palette.Link,
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).clickable { onOpenCommit(base) }.padding(horizontal = 2.dp))
                }
                else -> Text(t("${session.bases.size} common ancestors", "${session.bases.size} 个共同祖先"), fontSize = TypeScale.Small, color = Palette.Modified)
            }
        }
        HorizontalDivider()
        val result = loaded
        when {
            result == null -> Message(t("Resolving comparison…", "正在解析比较…"), Modifier.weight(1f).fillMaxWidth())
            result.isFailure -> Message(
                t("Couldn't compare ${request.rightLabel} with ${request.leftLabel}: ${result.exceptionOrNull()?.message}. The branch may have been deleted or renamed.",
                    "无法比较 ${request.rightLabel} 和 ${request.leftLabel}：${result.exceptionOrNull()?.message}，分支可能已被删除或改名。"),
                Modifier.weight(1f).fillMaxWidth(),
            )
            else -> key(result.getOrThrow()) { ComparisonContents(result.getOrThrow(), onOpenCommit, Modifier.weight(1f).fillMaxWidth()) }
        }
    }
}

@Composable private fun RefPill(label: String, primary: Boolean) {
    Text(
        label, fontSize = TypeScale.Small, fontFamily = CodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (primary) Palette.AccentInk else Palette.Ink,
        modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(4.dp))
            .background(if (primary) Palette.Raised else Palette.Panel).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable private fun ComparisonContents(session: ComparisonSession, onOpenCommit: (String) -> Unit, modifier: Modifier) {
    var endpoint by remember { mutableStateOf(session.bases.isEmpty()) }
    var base by remember { mutableStateOf(session.bases.singleOrNull()) }
    var choosingBase by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(Settings.defaultDiffMode) }
    var selected by remember(endpoint, base) { mutableStateOf<FileChange?>(null) }
    val target = session.request.rightLabel
    val current = session.request.leftLabel
    Row(modifier) {
        CompareCommits(session, onOpenCommit, Modifier.width(380.dp).fillMaxHeight())
        VerticalDivider()
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (endpoint) "$current → $target" else t("Common ancestor → $target", "共同祖先 → $target"),
                    fontSize = TypeScale.Small, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!endpoint && session.bases.size > 1) TextButton(onClick = { choosingBase = true }) {
                    Text(base?.take(7) ?: t("Choose common ancestor…", "选择共同祖先…"), fontSize = TypeScale.Small)
                }
                // 没有共同祖先时只能直接比较两端
                if (session.bases.isNotEmpty()) Segmented(listOf(false to t("From common ancestor", "从共同祖先"), true to t("Endpoints", "直接比较两端")), endpoint) { endpoint = it }
                Segmented(listOf(DiffMode.Unified to t("Unified", "统一"), DiffMode.Split to t("Split", "并排")), mode) { mode = it }
            }
            if (choosingBase) GitMenuPopup(session.bases.map { oid -> MenuItem(oid) { base = oid } }, { choosingBase = false })
            HorizontalDivider()
            val content = Modifier.fillMaxWidth().weight(1f)
            if (!endpoint && base == null && session.bases.isNotEmpty()) Message(t("Several common ancestors: choose one to compare files", "有多个共同祖先，请选择一个再比较文件"), content)
            else Loaded(Triple(session, endpoint, base), content, { session.files(endpoint, base) }) { files ->
                val file = selected ?: files.firstOrNull()
                Row(content) {
                    Column(Modifier.width(300.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t(countOf(files.size, "file"), "${files.size} 个文件"), fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.weight(1f))
                            Text("+${files.sumOf { it.additions }}", fontSize = TypeScale.Small, color = Palette.Added)
                            Text("−${files.sumOf { it.deletions }}", fontSize = TypeScale.Small, color = Palette.Removed)
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.fillMaxWidth().weight(1f)) { items(files) { f -> FileRow(f, f == file) { selected = it } } }
                    }
                    VerticalDivider()
                    val diff = Modifier.weight(1f).fillMaxHeight()
                    if (file == null) Message(t("No file differences", "没有文件差异"), diff)
                    else Loaded(session to file, diff, { SourceDiff(parseDiffLines(session.diff(file)), session.sources(file)) }) { loaded -> DiffBody(loaded.lines, mode, diff, sources = loaded.sources) }
                }
            }
        }
    }
}

/** 一侧独有的提交，分页读取。 */
private class CommitPages(val session: ComparisonSession, val left: Boolean) {
    var commits by mutableStateOf(emptyList<CommitInfo>())
    var more by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val total get() = session.counts[if (left) 0 else 1]
    suspend fun load() {
        if (busy || !more) return
        busy = true
        try { val page = withContext(Dispatchers.IO) { session.load(left) }; commits = commits + page.commits; more = page.hasMore }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString(); more = false }
        finally { busy = false }
    }
}

@Composable private fun CompareCommits(session: ComparisonSession, onOpen: (String) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val target = remember(session) { CommitPages(session, left = false) }
    val current = remember(session) { CommitPages(session, left = true) }
    LaunchedEffect(session) { target.load(); current.load() }
    val now = remember { System.currentTimeMillis() / 1000 }
    fun LazyListScope.group(pages: CommitPages, label: String) {
        item(key = "header-${pages.left}") {
            Row(Modifier.fillMaxWidth().height(28.dp).background(Palette.Panel).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Only in ", "仅在 "), fontSize = TypeScale.Small, color = Palette.Muted)
                Text(label, fontSize = TypeScale.Small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(t(" · ${countOf(pages.total.toInt(), "commit")}", " · ${pages.total} 个提交"), fontSize = TypeScale.Small, color = Palette.Muted)
            }
            HorizontalDivider()
        }
        if (pages.total == 0L) item(key = "empty-${pages.left}") {
            Text(t("No commits of its own", "没有独有的提交"), fontSize = TypeScale.Small, color = Palette.Faint, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        items(pages.commits, key = { "${pages.left}-${it.id}" }) { c ->
            Column(
                Modifier.fillMaxWidth().height(46.dp).hoverRow().clickable { onOpen(c.id) }.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(c.shortMessage, fontSize = TypeScale.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.author} · ${relativeTime(c.timeSeconds, now)} · ${c.id.take(7)}", fontSize = TypeScale.Caption, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        pages.error?.let { item(key = "error-${pages.left}") { Text(it, color = Palette.Removed, fontSize = TypeScale.Small, modifier = Modifier.padding(14.dp)) } }
        if (pages.more && pages.commits.isNotEmpty()) item(key = "more-${pages.left}") {
            TextButton(enabled = !pages.busy, onClick = { scope.launch { pages.load() } }) {
                Text(if (pages.busy) t("Loading…", "正在读取…") else t("Load more (${pages.total - pages.commits.size} left)", "加载更多（还有 ${pages.total - pages.commits.size} 个）"), fontSize = TypeScale.Small)
            }
        }
    }
    LazyColumn(modifier) {
        group(target, session.request.rightLabel)
        group(current, session.request.leftLabel)
    }
}
