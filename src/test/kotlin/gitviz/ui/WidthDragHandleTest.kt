package gitviz.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class WidthDragHandleTest {
    @Test fun `dragging the right edge resizes within bounds and reports the end`() {
        var width by mutableStateOf(248.dp)
        var stopped = 0
        val scene = ImageComposeScene(800, 400) {
            Box(Modifier.width(width).fillMaxHeight()) {
                WidthDragHandle("resize", Modifier.align(Alignment.CenterEnd),
                    onDrag = { width = (width + it).coerceIn(200.dp, 440.dp) },
                    onDragStopped = { stopped++ })
            }
        }
        try {
            var frame = 0L
            fun draw() = repeat(3) { scene.render(++frame * 16_000_000L) }
            fun drag(fromX: Float, toX: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(fromX, 200f))
                for (step in 1..10) { scene.sendPointerEvent(PointerEventType.Move, Offset(fromX + (toX - fromX) * step / 10, 200f)); draw() }
                scene.sendPointerEvent(PointerEventType.Release, Offset(toX, 200f)); draw()
            }
            draw()
            drag(245f, 345f)
            assertEquals(1, stopped)
            // 拖动要先超过触摸阈值才开始计算，所以只要求变宽、并跟着手走到大致位置
            assertEquals(true, width > 300.dp && width <= 348.dp, "width $width")
            // 超出上限时停在 440
            val edge = width.value - 3
            drag(edge, edge + 400)
            assertEquals(440.dp, width)
            assertEquals(2, stopped)
            // 不在边缘的按下不触发
            drag(100f, 300f)
            assertEquals(440.dp, width)
            assertEquals(2, stopped)
        } finally { scene.close() }
    }
}
