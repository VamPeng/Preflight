package gitviz

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

internal val CommitHistoryScrollbarWidth = 16.dp

@Composable
internal fun CommitHistoryScrollbar(listState: LazyListState, modifier: Modifier) {
    if (!listState.canScrollForward && !listState.canScrollBackward) return

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dragged by interaction.collectIsDraggedAsState()
    var recentlyScrolled by remember(listState) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1).collectLatest {
                recentlyScrolled = true
                delay(500)
                recentlyScrolled = false
            }
    }

    val style = LocalScrollbarStyle.current
    val active = hovered || dragged || listState.isScrollInProgress || recentlyScrolled
    val thumbWidth by animateDpAsState(if (active) 10.dp else style.thickness, tween(150))
    val thumbShape = remember(thumbWidth) { HistoryThumbShape(thumbWidth) }
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = modifier,
        style = style.copy(
            thickness = CommitHistoryScrollbarWidth,
            shape = thumbShape,
            unhoverColor = if (active) style.hoverColor else style.unhoverColor,
        ),
        interactionSource = interaction,
    )
}

/** Draw a narrower thumb inside Compose's full-width scrollbar, leaving its hit area fixed. */
private class HistoryThumbShape(private val width: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val right = (size.width - 3.dp.toPx()).coerceAtLeast(0f)
        val left = (right - width.toPx()).coerceAtLeast(0f)
        Outline.Rounded(RoundRect(Rect(left, 0f, right, size.height), CornerRadius((right - left) / 2)))
    }
}
