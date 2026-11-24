package gitviz.ui.history

import gitviz.logic.*
import gitviz.ui.*
import gitviz.ui.changes.DiffSelection
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            // 可用宽度扣除 10dp 滚动条后，右半边的行号 40dp 之后是代码
            val right = s.copyDrag(Offset(438f, rowY(1)), Offset(780f, rowY(3)))
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

    @Test fun `unified scrollbar exposes the end of a wide unicode line`() {
        val longLine = DiffLine("+" + "中文\t".repeat(60) + "UNIFIED_END", DiffLineKind.ADDED, null, 1)
        SelectionScene(800, 300) { UnifiedDiff(listOf(longLine), Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            s.drag(Offset(20f, 297f), Offset(785f, 297f))
            val copied = s.copyDrag(Offset(2f, rowY(0)), Offset(780f, rowY(0))).orEmpty()
            assertTrue(copied.endsWith("UNIFIED_END"), "Line end must be reachable: $copied")
        }
    }

    @Test fun `split scrollbar exposes the end of the right long line`() {
        val wideLines = listOf(
            DiffLine("@@ -1 +1 @@", DiffLineKind.HUNK),
            DiffLine("-" + "中文\t".repeat(60) + "LEFT_END", DiffLineKind.REMOVED, 1, null),
            DiffLine("+" + "new value ".repeat(100) + "RIGHT_END", DiffLineKind.ADDED, null, 1),
        )
        SelectionScene(800, 300) { SplitDiff(wideLines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            s.drag(Offset(10f, 297f), Offset(785f, 297f))
            val right = s.copyDrag(Offset(2f, rowY(1)), Offset(780f, rowY(1))).orEmpty()
            assertTrue(right.endsWith("RIGHT_END"), "Right line end must be reachable: $right")
        }
    }

    @Test fun `horizontal wheel reaches the line end in both diff modes`() {
        val longLines = listOf(
            DiffLine("@@ -0,0 +1 @@", DiffLineKind.HUNK),
            DiffLine("+" + "long value ".repeat(100) + "WHEEL_END", DiffLineKind.ADDED, null, 1),
        )
        for (mode in DiffMode.entries) {
            SelectionScene(800, 300) {
                if (mode == DiffMode.Unified) UnifiedDiff(longLines, Modifier.fillMaxSize())
                else SplitDiff(longLines, Modifier.fillMaxSize())
            }.use { s ->
                s.settle()
                s.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, rowY(1)), scrollDelta = Offset(10_000f, 0f))
                s.settle(60)
                val copied = s.copyDrag(Offset(2f, rowY(1)), Offset(780f, rowY(1))).orEmpty()
                assertTrue(copied.endsWith("WHEEL_END"), "$mode must support horizontal wheel scrolling: $copied")
            }
        }
    }

    @Test fun `split lines beyond the constraints limit remain scrollable at different densities`() {
        for (density in listOf(1f, 2f)) {
            for (length in listOf(20_000, 40_000)) {
                val wideLines = listOf(
                    DiffLine("@@ -1 +1 @@", DiffLineKind.HUNK),
                    DiffLine("-" + "x".repeat(length) + "LEFT_END", DiffLineKind.REMOVED, 1, null),
                    DiffLine("+" + "中\t".repeat(length / 2) + "RIGHT_END", DiffLineKind.ADDED, null, 1),
                )
                SelectionScene(800, 300, Density(density)) { SplitDiff(wideLines, Modifier.fillMaxSize()) }.use { s ->
                    s.settle()
                    s.drag(Offset(10f, 297f), Offset(775f, 297f))
                    val right = s.copyDrag(Offset(2f, rowY(1) * density), Offset(770f, rowY(1) * density)).orEmpty()
                    assertTrue(right.endsWith("RIGHT_END"), "$length chars at density $density: $right")
                    s.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, rowY(1) * density), scrollDelta = Offset(-1_000_000f, 0f))
                    s.settle(60)
                    val left = s.copyDrag(Offset(42f * density, rowY(1) * density), Offset(700f, rowY(1) * density)).orEmpty()
                    assertTrue(left.isNotEmpty() && left.all { it == 'x' }, "Must return to the left line: $left")
                }
            }
        }
    }

    @Test fun `split chunks copy as one line without breaking unicode or mixing sides`() {
        val text = "x".repeat(1023) + "🙂e\u0301" + "y".repeat(1022) + "END"
        val wideLines = listOf(
            DiffLine("-" + text, DiffLineKind.REMOVED, 1, null),
            DiffLine("+" + text, DiffLineKind.ADDED, null, 1),
        )
        SelectionScene(40_000, 80) { SplitDiff(wideLines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            assertEquals(text, s.copyDrag(Offset(42f, rowY(0)), Offset(19_980f, rowY(0))))
            assertEquals(text, s.copyDrag(Offset(20_037f, rowY(0)), Offset(39_970f, rowY(0))))
        }
    }

    @Test fun `split rows retain horizontal position when vertical scrolling loads new rows`() {
        val wideLines = (1..40).map { DiffLine(" " + "x".repeat(20_000) + "END_$it", DiffLineKind.CONTEXT, it, it) }
        SelectionScene(800, 120) { SplitDiff(wideLines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            s.drag(Offset(10f, 117f), Offset(785f, 117f))
            s.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 30f), scrollDelta = Offset(0f, 10_000f))
            s.settle(60)
            val last = s.copyDrag(Offset(2f, 97f), Offset(780f, 97f)).orEmpty()
            assertTrue(last.endsWith("END_40"), "New rows must keep the shared horizontal position: $last")
        }
    }

    @Test fun `split horizontal wheel works over the empty space below rows`() {
        val wideLines = listOf(DiffLine("+" + "x".repeat(20_000) + "RIGHT_END", DiffLineKind.ADDED, null, 1))
        SelectionScene(800, 300) { SplitDiff(wideLines, Modifier.fillMaxSize()) }.use { s ->
            s.settle()
            s.scene.sendPointerEvent(PointerEventType.Scroll, Offset(200f, 200f), scrollDelta = Offset(1_000_000f, 0f))
            s.settle(60)
            val right = s.copyDrag(Offset(2f, rowY(0)), Offset(780f, rowY(0))).orEmpty()
            assertTrue(right.endsWith("RIGHT_END"), "Horizontal wheel must work below the last row: $right")
        }
    }
}
