@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package gitviz.ui.changes

import gitviz.*
import gitviz.git.*
import gitviz.ui.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 改动页：勾选、批量操作菜单、按文件夹、横向滑动和拖动宽度。 */
class ChangesViewTest {
    private fun <T> withRepository(body: (RepositoryFixture) -> T): T {
        val language = Settings.language
        val tree = Settings.changesAsTree
        val width = Settings.changesWidth
        Settings.language = Language.EN
        try {
            return RepositoryFixture().use(body)
        } finally {
            Settings.language = language
            Settings.changesAsTree = tree
            Settings.updateChangesWidth(width)
        }
    }

    private fun RepositoryFixture.seed() {
        write("src/main/A.kt", "a\n"); write("src/main/B.kt", "b\n"); write("docs/n.md", "n\n"); write("README.md", "r\n")
        git("add", "--", "."); git("commit", "-q", "-m", "base")
        write("src/main/A.kt", "a2\n"); write("src/main/B.kt", "b2\n"); write("docs/n.md", "n2\n"); write("src/other/New.kt", "new\n")
    }

    private fun staged(fixture: RepositoryFixture) = fixture.cli.status().filter { it.hasStaged }.map { it.path }.toSet()

    @Test
    fun `ticking a folder and choosing stage selected stages every file below it`() = withRepository { repo ->
        repo.seed()
        Settings.changesAsTree = true
        ChangesScene(repo).use { ui ->
            ui.frame()
            ui.click(ui.node("Select main/").boundsInRoot.center)
            ui.frame()
            assertTrue(ui.has("2 selected"), "Ticking the folder ticks both files below it")
            ui.click(ui.nodes("Batch actions").minBy { it.boundsInRoot.top }.boundsInRoot.center)
            ui.frame(); ui.frame()
            ui.click(ui.node("Stage selected").boundsInRoot.center)
            ui.frame(); ui.frame()
            assertEquals(setOf("src/main/A.kt", "src/main/B.kt"), staged(repo))
            assertFalse(ui.hasMatch(Regex("\\d+ selected")), "Staged files are no longer ticked")
        }
    }

    @Test
    fun `batch menu items are disabled until something is ticked`() = withRepository { repo ->
        repo.seed()
        Settings.changesAsTree = false
        ChangesScene(repo).use { ui ->
            ui.frame()
            ui.click(ui.nodes("Batch actions").minBy { it.boundsInRoot.top }.boundsInRoot.center)
            ui.frame(); ui.frame()
            ui.click(ui.node("Stage selected").boundsInRoot.center)
            ui.frame(); ui.frame()
            assertTrue(staged(repo).isEmpty(), "Nothing is ticked, so nothing is staged")
        }
    }

    @Test
    fun `scrolling sideways moves the names but not the checkboxes`() = withRepository { repo ->
        val long = "a-very-long-file-name-".repeat(8) + "end.kt"
        repo.write("README.md", "r\n"); repo.git("add", "--", "."); repo.git("commit", "-q", "-m", "base")
        repo.write("README.md", "r2\n"); repo.write(long, "x\n")
        Settings.changesAsTree = false
        ChangesScene(repo).use { ui ->
            ui.frame(); ui.frame()
            val name = ui.nodes(long).first()
            val box = ui.node("Select $long").boundsInRoot
            val left = name.positionInRoot.x
            ui.scroll(Offset(box.left - 40f, box.center.y), Offset(400f, 0f))
            repeat(40) { ui.frame() } // 滚轮是带动画的，多渲染几帧才到位
            assertTrue(ui.nodes(long).first().positionInRoot.x < left - 100f, "The name slides left")
            assertEquals(box, ui.node("Select $long").boundsInRoot, "The checkbox stays where it is")
        }
    }

    @Test
    fun `dragging the edge resizes the list within its limits`() = withRepository { repo ->
        repo.seed()
        Settings.changesAsTree = false
        ChangesScene(repo).use { ui ->
            ui.frame()
            fun right() = ui.node("Drag to resize the list").boundsInRoot.right
            val start = ui.node("Drag to resize the list").boundsInRoot.center
            ui.drag(start, Offset(start.x + 2000f, start.y))
            ui.frame(); ui.frame()
            assertEquals(450f, right(), 1f, "At most half of the page")
            val wide = ui.node("Drag to resize the list").boundsInRoot.center
            ui.drag(wide, Offset(wide.x - 2000f, wide.y))
            ui.frame(); ui.frame()
            assertEquals(260f, right(), 1f, "At least 260")
        }
    }

    private class ChangesScene(private val repo: RepositoryFixture) : AutoCloseable {
        private val width = 900
        private val height = 700
        private val owners = mutableListOf<SemanticsOwner>()
        private val platform = object : PlatformContext by PlatformContext.Empty {
            override val windowInfo = object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize = IntSize(width, height)
            }
            override val semanticsOwnerListener = object : PlatformContext.SemanticsOwnerListener {
                override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) { owners += semanticsOwner }
                override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) { owners -= semanticsOwner }
                override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {}
                override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {}
            }
        }
        private val scene = CanvasLayersComposeScene(density = Density(1f), size = IntSize(width, height), coroutineContext = Dispatchers.Unconfined, platformContext = platform)
        private val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height)
        private var nanos = 0L

        init {
            scene.setContent {
                var files by mutableStateOf(repo.cli.status())
                GitVizTheme {
                    Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Ink) {
                        ChangesView(
                            repo.cli, files, "main", onChanged = { files = repo.cli.status() },
                            onRun = { _, _, op, done -> done(repo.cli.op().ok) }, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        fun frame() { nanos += 16_000_000L; scene.render(surface.canvas.asComposeCanvas(), nanos) }
        fun click(position: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(PointerEventType.Press, position)
            scene.sendPointerEvent(PointerEventType.Release, position)
        }
        fun scroll(position: Offset, delta: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = delta)
        }
        fun drag(from: Offset, to: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, from)
            scene.sendPointerEvent(PointerEventType.Press, from)
            for (i in 1..10) { scene.sendPointerEvent(PointerEventType.Move, from + (to - from) * (i / 10f)); frame() }
            scene.sendPointerEvent(PointerEventType.Release, to)
        }
        private fun all(): List<SemanticsNode> {
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            return owners.flatMap { descendants(it.unmergedRootSemanticsNode) }
        }
        private fun labels(node: SemanticsNode): List<String> =
            (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) +
                (node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
        fun nodes(label: String) = all().filter { label in labels(it) }
        fun node(label: String) = nodes(label).firstOrNull() ?: error("No node labelled \"$label\"; have ${all().flatMap(::labels).distinct()}")
        fun has(label: String) = nodes(label).isNotEmpty()
        fun hasMatch(pattern: Regex) = all().flatMap(::labels).any { pattern.matches(it) }
        override fun close() { scene.close(); surface.close() }
    }
}
