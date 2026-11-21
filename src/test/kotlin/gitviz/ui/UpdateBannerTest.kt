@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import gitviz.service.AvailableUpdate
import kotlinx.coroutines.Dispatchers
import java.net.URI
import kotlin.test.*

class UpdateBannerTest {
    @Test fun `download is explicit and later removes the notice in both languages`() {
        val previous = Settings.language
        try {
            for (language in listOf(Language.EN, Language.ZH)) {
                Settings.language = language
                val owners = mutableListOf<SemanticsOwner>()
                val platform = object : PlatformContext by PlatformContext.Empty {
                    override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                        override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                        override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                        override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                        override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
                    }
                }
                val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(420, 200),
                    coroutineContext = Dispatchers.Unconfined, platformContext = platform)
                val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(420, 200)
                try {
                    var visible by mutableStateOf(true)
                    var opened = 0
                    scene.setContent { GitVizTheme {
                        Box(Modifier.padding(16.dp)) {
                            if (visible) UpdateBanner(AvailableUpdate("1.0.10", URI("https://git.vambda.com/downloads/app.dmg"), "a".repeat(64)),
                                onDownload = { opened++ }, onDismiss = { visible = false })
                        }
                    } }
                    var nanos = 0L
                    fun frame() { nanos += 16_000_000; scene.render(surface.canvas.asComposeCanvas(), nanos) }
                    fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
                    fun all() = owners.flatMap { nodes(it.rootSemanticsNode) }
                    fun label(text: String) = all().first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
                    fun click(position: Offset) {
                        scene.sendPointerEvent(PointerEventType.Move, position)
                        scene.sendPointerEvent(PointerEventType.Press, position)
                        scene.sendPointerEvent(PointerEventType.Release, position)
                        repeat(3) { frame() }
                    }
                    repeat(4) { frame() }
                    assertEquals(0, opened)
                    val title = label(t("Preflight 1.0.10 is available", "发现新版本 Preflight 1.0.10"))
                    assertTrue(title.boundsInRoot.right <= 404)
                    // 仅在显式请求时生成观测图片，常规测试不留下截图。
                    System.getenv("PREFLIGHT_UPDATE_SCREENSHOT")?.let { path ->
                        surface.makeImageSnapshot().use { image ->
                            image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { java.io.File(path).writeBytes(it.bytes) }
                        }
                    }
                    click(label(t("Download", "去下载")).boundsInRoot.center)
                    assertEquals(1, opened)
                    assertTrue(visible)
                    click(label(t("Later", "稍后")).boundsInRoot.center)
                    assertFalse(visible)
                    assertTrue(all().none { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> "1.0.10" in value.text } == true })
                } finally { scene.close(); surface.close() }
            }
        } finally { Settings.language = previous }
    }
}
