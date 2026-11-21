package gitviz.ui.history

import gitviz.logic.*
import gitviz.ui.*
import gitviz.ui.changes.DiffSelection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffSelectionTest {
    private val lines = listOf(
        DiffLine("@@ -1,3 +1,3 @@", DiffLineKind.HUNK),
        DiffLine(" keep", DiffLineKind.CONTEXT, 1, 1),
        DiffLine("-old value", DiffLineKind.REMOVED, 2, null),
        DiffLine("+new value", DiffLineKind.ADDED, null, 2),
        DiffLine(" tail", DiffLineKind.CONTEXT, 3, 3),
    )

    /** 第 index 行的竖直中线：列表上边留 4dp，每行 18dp。 */
    private fun rowY(index: Int) = 4f + 18f * index + 9f

    @Test fun `unified diff copies code without line numbers or signs`() {
        SelectionScene(800, 300) { UnifiedDiff(lines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            // 代码从两列行号和 +/− 之后开始（40 + 40 + 14）
            assertEquals("keep\nold value\nnew value", s.copyDrag(Offset(95f, rowY(1)), Offset(600f, rowY(3))))
        }
    }

    @Test fun `split diff copies only the side where the drag started`() {
        SelectionScene(800, 300) { SplitDiff(lines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            // 右半边：行号 40dp 之后是代码
            val right = s.copyDrag(Offset(443f, rowY(1)), Offset(780f, rowY(3)))
            assertEquals("keep\nnew value\ntail", right)
            val left = s.copyDrag(Offset(42f, rowY(1)), Offset(390f, rowY(3)))
            assertEquals("keep\nold value\ntail", left)
        }
    }

    @Test fun `line clicks for partial staging still toggle`() {
        val toggled = mutableStateListOf<DiffLine>()
        SelectionScene(800, 300) {
            UnifiedDiff(lines, Modifier.fillMaxSize(), DiffSelection({ it in toggled }, { if (it in toggled) toggled.remove(it) else toggled.add(it) }))
        }.use { s ->
            s.settle()
            s.click(Offset(200f, rowY(3)))
            assertEquals(listOf(lines[3]), toggled.toList())
        }
    }
}
