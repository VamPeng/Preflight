package gitviz.platform

import androidx.compose.runtime.*
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlinx.coroutines.delay
import gitviz.ui.isMac

/** 自建 ComposeWindow 的 LocalWindowInfo 焦点可能一直为 false，以实际宿主窗口为准。 */
@Composable
internal fun rememberWindowFocused(window: Window): Boolean {
    var focused by remember(window) { mutableStateOf(window.isFocused) }
    if (isMac) {
        // GlassWindow 的透明宿主可能不向 AWT/Compose 传递焦点。
        // 这里只查询激活状态，Git 刷新仍只在前台发生，并沿用原来的事件合并。
        LaunchedEffect(window) {
            while (true) {
                focused = Vibrancy.isApplicationActive() && window.isVisible
                delay(250)
            }
        }
        return focused
    }
    DisposableEffect(window) {
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) { focused = true }
            override fun windowLostFocus(e: WindowEvent) { focused = false }
        }
        window.addWindowFocusListener(listener)
        // 注册监听后再取一次，覆盖首次组合之前已经获得焦点的情况。
        focused = window.isFocused
        onDispose { window.removeWindowFocusListener(listener) }
    }
    return focused
}
