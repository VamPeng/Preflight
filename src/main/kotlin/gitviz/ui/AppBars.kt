package gitviz.ui

import gitviz.RepoState
import gitviz.git.LoadResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset

/* 主窗口的标题栏、侧边栏条目、过滤提示条和底部状态栏。 */

internal val TitleBarHeight = 52.dp

@Composable
internal fun WorkspaceItem(label: String, icon: String, selected: Boolean, badge: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(5.dp))
            .background(if (selected) Palette.Raised else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, if (selected) Palette.AccentInk else Palette.Muted, size = 15.dp)
        Text(label, fontSize = TypeScale.Body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f))
        if (badge > 0) {
            Text(
                "$badge", fontSize = TypeScale.Caption, fontWeight = FontWeight.SemiBold, color = Palette.OnAccent,
                modifier = Modifier.background(Palette.Accent, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp),
            )
        }
    }
}

/** 主图只看一个文件时，列表上方的说明和“显示全部”。 */
@Composable
internal fun PathFilterBar(path: String, result: LoadResult, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(Palette.Accent.copy(alpha = 0.08f)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t("Only", "只看"), fontSize = TypeScale.Small, color = Palette.Muted)
        Text(path, fontSize = TypeScale.Small, fontFamily = CodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        val count = result.commits.size
        Text(if (result.truncated) t("Loaded ${countOf(count, "commit")} that touch it", "已加载 $count 个改动它的提交") else t("${countOf(count, "commit")} touch it", "$count 个改动它的提交"), fontSize = TypeScale.Small,
            color = Palette.Muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(t("Show all commits", "显示全部提交"), fontSize = TypeScale.Small, color = Palette.Link, modifier = Modifier.clickable(onClick = onClear))
    }
}

@Composable
internal fun Toolbar(
    state: RepoState?,
    loading: Boolean,
    start: Dp,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (IntOffset) -> Unit,
    onRefresh: () -> Unit,
    netBusy: Boolean,
    onPull: () -> Unit,
    onFetch: () -> Unit = {},
    onPush: () -> Unit,
    canStash: Boolean,
    onStash: () -> Unit,
    searchFocus: FocusRequester? = null,
    onPullMenu: () -> Unit = {},
    onPushMenu: () -> Unit = {},
    onSearchEscape: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(TitleBarHeight).padding(start = start, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 仓库菜单从标题正下方弹出，左边和标题对齐，不盖住标题
        var titleBounds by remember { mutableStateOf<Rect?>(null) }
        val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
        Row(
            Modifier.onGloballyPositioned { titleBounds = it.boundsInWindow() }
                .clip(RoundedCornerShape(5.dp))
                .clickable(enabled = !loading && !netBusy) {
                    titleBounds?.let { onOpen(IntOffset(it.left.toInt(), it.bottom.toInt() + gap)) }
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state?.repo?.displayName ?: t("Open repository", "打开仓库"), fontWeight = FontWeight.SemiBold, fontSize = TypeScale.Title, maxLines = 1)
            Icon(Icons.ChevronDown, Palette.Muted, size = 10.dp, strokeWidth = 3f)
        }
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        if (state != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolButton(t("Refresh", "刷新"), Icons.Refresh, enabled = !loading && !netBusy, onClick = onRefresh)
                val canSync = !loading && !netBusy && state.repo.workTree != null
                ToolButton(t("Fetch", "获取"), Icons.Fetch, enabled = canSync, onClick = onFetch)
                ToolButton(t("Pull", "拉取"), Icons.Pull, enabled = canSync, onClick = onPull, onMenu = onPullMenu)
                ToolButton(t("Push", "推送"), Icons.Push, enabled = canSync, onClick = onPush, onMenu = onPushMenu)
                ToolButton(t("Stash", "储藏"), Icons.Stash, enabled = canSync && canStash, onClick = onStash)
            }
            SearchField(query, onQuery, searchFocus, onSearchEscape)
        }
    }
}

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, focus: FocusRequester? = null, onEscape: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(5.dp)
    BasicTextField(
        value, onChange, singleLine = true,
        modifier = Modifier.textInputFocus().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onPreviewKeyEvent { e -> if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) { onEscape(); true } else false },
        textStyle = TextStyle(color = Palette.Ink, fontSize = TypeScale.Body, fontFamily = UiFont),
        cursorBrush = SolidColor(Palette.Accent),
        interactionSource = interaction,
        decorationBox = { field ->
            Row(
                Modifier.width(240.dp).height(28.dp).background(Palette.Rail, shape)
                    .border(1.dp, if (focused) Palette.Accent else Palette.Line, shape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Search, Palette.Faint, size = 14.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(t("Search commits, branches, tags", "搜索提交、分支、标签"), fontSize = TypeScale.Body, color = Palette.Faint, maxLines = 1)
                    field()
                }
            }
        },
    )
}

@Composable
internal fun StatusBar(state: RepoState, loading: Boolean = false, onCancel: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(Palette.Rail).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val path = state.repo.workTree?.path ?: state.repo.repository.directory.path
        Text(path, fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val count = state.result.commits.size
        Text(
            when {
                loading -> t("Loaded ${countOf(count, "commit")} · loading earlier…", "已加载 $count 个提交 · 正在加载更早的…")
                state.result.truncated -> t("Loaded ${countOf(count, "commit")} · scroll to load more", "已加载 $count 个提交 · 往下滚继续加载")
                else -> t(countOf(count, "commit"), "$count 个提交")
            },
            fontSize = TypeScale.Caption, color = Palette.Faint, maxLines = 1,
        )
        if (onCancel != null) {
            Text(t("Cancel", "取消"), fontSize = TypeScale.Caption, color = Palette.Link, modifier = Modifier.clickable(onClick = onCancel))
        }
    }
}
