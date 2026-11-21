package gitviz.ui.preview

import gitviz.logic.*
import gitviz.ui.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewAreaTest {
    private fun document(markdown: String) = TextDocument(File("README.md"), "README.md", markdown, parseMarkdown(markdown), truncated = false)

    @Test fun `project readme renders in both themes and closes`() {
        val doc = assertNotNull(loadReadme(File(".").absoluteFile))
        val plain = TextDocument(File("README.txt"), "README.txt", "line\n".repeat(1000), null, truncated = true)
        val dark = Palette.isDark
        try {
            repeat(2) {
                val theme = if (Palette.isDark) "dark" else "light"
                for ((name, shown) in listOf("preview-readme" to doc, "preview-plain" to plain)) {
                    var closed = 0
                    SelectionScene(1000, 700) {
                        PreviewArea(PreviewContent.Document(shown), onClose = { closed++ }, modifier = Modifier.fillMaxSize())
                    }.use { s ->
                        s.settle(20)
                        val out = File("build/screens/$name-$theme.png").apply { parentFile.mkdirs() }
                        out.writeBytes(s.scene.render(1_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                        assertTrue(out.length() > 0)
                        // 标题栏右端的关闭按钮
                        s.click(Offset(1000f - 8f - 15f, 20f))
                        assertEquals(1, closed)
                    }
                }
                Palette.toggle()
            }
        } finally {
            if (Palette.isDark != dark) Palette.toggle()
        }
    }

    @Test fun `code tabs line up with four-column tab stops but keep the tab characters`() {
        val measurer = TextMeasurer(createFontFamilyResolver(), Density(2f), LayoutDirection.Ltr)
        val style = TextStyle(fontFamily = CodeFont, fontSize = 12.5.sp)
        fun width(text: AnnotatedString) = measurer.measure(text, style).size.width
        for ((tabbed, spaced) in listOf("\tx" to "    x", "ab\tx" to "ab  x", "abcd\tx" to "abcd    x", "\t\tx" to "        x", "a\tb\tc" to "a   b   c")) {
            val aligned = alignTabs(tabbed, style, measurer, Density(2f))
            assertEquals(tabbed, aligned.text, "the copied text keeps its tabs")
            val expected = width(AnnotatedString(spaced))
            assertTrue(abs(width(aligned) - expected) <= 1, "${tabbed.replace("\t", "\\t")}: ${width(aligned)} vs $expected")
        }
        // 换行后从第 0 列重新算
        val lines = alignTabs("abc\n\tx", style, measurer, Density(2f))
        assertTrue(abs(width(lines) - width(AnnotatedString("abc\n    x"))) <= 1)
    }

    @Test fun `selecting across blocks copies text with paragraph, list and table breaks`() {
        val doc = document("# Title\n\nFirst **paragraph**.\n\n- one\n- two\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n```makefile\nall:\n\techo hello\n```")
        SelectionScene(1000, 700) { PreviewArea(PreviewContent.Document(doc), onClose = {}, modifier = Modifier.fillMaxSize()) }.use { s ->
            s.settle(20)
            val copied = s.copyDrag(Offset(103f, 75f), Offset(900f, 680f))
            assertEquals("Title\n\nFirst paragraph.\n\none\ntwo\n\nA\tB\n1\t2\n\nall:\n\techo hello", copied)
        }
    }

    @Test fun `plain text copies line breaks between chunks`() {
        val text = (1..450).joinToString("\n") { "row $it" }
        val doc = TextDocument(File("notes.txt"), "notes.txt", text, null, truncated = false)
        SelectionScene(700, 400) { TextDocumentView(doc, Modifier.fillMaxSize()) }.use { s ->
            s.settle(20)
            val copied = assertNotNull(s.copyDrag(Offset(33f, 31f), Offset(400f, 395f)))
            assertTrue(copied.startsWith("row 1\nrow 2\n"), copied.take(40))
        }
    }
}
