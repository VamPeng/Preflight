@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.unit.Density
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * 离屏测试文字选择：在场景里用鼠标拖选，再按 ⌘C／Ctrl+C，拿到复制进剪贴板的文字。
 * SelectionContainer 用的是 LocalClipboard，这里换成一个只记录内容的假剪贴板。
 */
internal class SelectionScene(width: Int, height: Int, density: Density = Density(1f), content: @Composable () -> Unit) : AutoCloseable {
    var copied: String? = null
        private set
    private val clipboard = object : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            copied = (clipEntry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
        }
        override val nativeClipboard: NativeClipboard get() = error("not used in tests")
    }
    val scene = ImageComposeScene(width, height, density) {
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            GitVizTheme { Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) { content() } }
        }
    }
    private var nanos = 0L

    fun settle(frames: Int = 10) = repeat(frames) { nanos += 16_000_000L; scene.render(nanos) }

    fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary, type = PointerType.Mouse)
        settle()
    }

    fun drag(from: Offset, to: Offset) {
        val down = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Press, from, buttons = down, button = PointerButton.Primary, type = PointerType.Mouse)
        settle(3)
        for (k in 1..12) {
            scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (k / 12f), buttons = down, type = PointerType.Mouse)
            settle(2)
        }
        scene.sendPointerEvent(PointerEventType.Release, to, button = PointerButton.Primary, type = PointerType.Mouse)
        settle()
    }

    /** 拖选后按复制键，返回复制的文字。 */
    fun copyDrag(from: Offset, to: Offset): String? {
        copied = null
        drag(from, to)
        val mac = System.getProperty("os.name").startsWith("Mac")
        val modifiers = if (mac) java.awt.event.InputEvent.META_DOWN_MASK else java.awt.event.InputEvent.CTRL_DOWN_MASK
        scene.sendKeyEvent(KeyEvent(Key.C, KeyEventType.KeyDown, isMetaPressed = mac, isCtrlPressed = !mac,
            nativeEvent = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_PRESSED, 0, modifiers, java.awt.event.KeyEvent.VK_C, 'c')))
        settle()
        return copied
    }

    override fun close() = scene.close()
}
