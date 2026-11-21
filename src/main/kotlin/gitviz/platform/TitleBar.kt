package gitviz.platform

import gitviz.ui.isMac
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter

/**
 * 标题栏区域：按住拖动窗口；macOS 上双击执行系统的标题栏双击动作（默认铺满／还原）。
 * 按钮、输入框等子组件自己消费了按下事件，不会触发拖动或双击。
 * 其他平台有系统标题栏，双击交给系统，这里只拖动。
 */
@Composable
internal fun WindowScope.TitleBarArea(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val drag = remember(window) { WindowDrag(window) }
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown()
            val event = currentEvent
            val clicks = event.awtEventOrNull?.clickCount ?: 1
            // 第二次按下不开始拖动，否则窗口缩放后手一抖会被拖回原位置
            if (isMac && event.buttons.isPrimaryPressed && clicks >= 2 && clicks % 2 == 0) Vibrancy.titleBarDoubleClick()
            else drag.start()
        }
    }) { content() }
}

/** 与 Compose 的 WindowDraggableArea 相同：记下按下时的指针和窗口位置，跟随 AWT 拖动事件移动。 */
private class WindowDrag(private val window: Window) {
    private var windowStart: Point? = null
    private var pointerStart: Point? = null

    private val motion = object : MouseMotionAdapter() {
        override fun mouseDragged(event: MouseEvent) {
            val windowStart = windowStart ?: return
            val pointerStart = pointerStart ?: return
            val point = MouseInfo.getPointerInfo()?.location ?: return
            window.setLocation(windowStart.x + point.x - pointerStart.x, windowStart.y + point.y - pointerStart.y)
        }
    }
    private val release = object : MouseAdapter() {
        override fun mouseReleased(event: MouseEvent) {
            window.removeMouseMotionListener(motion)
            window.removeMouseListener(this)
        }
    }

    fun start() {
        pointerStart = MouseInfo.getPointerInfo()?.location ?: return
        windowStart = window.location
        window.removeMouseMotionListener(motion)
        window.removeMouseListener(release)
        window.addMouseListener(release)
        window.addMouseMotionListener(motion)
    }
}
