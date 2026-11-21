package gitviz.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownTest {
    private fun text(spans: List<MdSpan>) = spans.joinToString("") { it.text }
    private fun paragraph(block: MdBlock) = assertIs<MdBlock.Paragraph>(block).text

    @Test fun `headings in both styles`() {
        val blocks = parseMarkdown("# Title #\n\nIntro\n===\n\nSub\n---\n### Small")
        assertEquals(listOf(1, 1, 2, 3), blocks.map { assertIs<MdBlock.Heading>(it).level })
        assertEquals(listOf("Title", "Intro", "Sub", "Small"), blocks.map { text((it as MdBlock.Heading).text) })
    }

    @Test fun `paragraph lines join and hard breaks stay`() {
        val blocks = parseMarkdown("one\ntwo  \nthree\\\nfour\n\nnext")
        assertEquals(2, blocks.size)
        assertEquals("one two\nthree\nfour", text(paragraph(blocks[0])))
    }

    @Test fun `emphasis nests and ignores snake case`() {
        val spans = paragraph(parseMarkdown("**bold *both*** and _it_ but snake_case_name, ~~gone~~, 2 * 3 * 4").single())
        assertEquals("bold both and it but snake_case_name, gone, 2 * 3 * 4", text(spans))
        assertTrue(spans.first { it.text == "bold " }.bold)
        assertTrue(spans.first { it.text == "both" }.let { it.bold && it.italic })
        assertTrue(spans.first { it.text == "it" }.italic)
        assertTrue(spans.first { it.text == "gone" }.strike)
        assertTrue(spans.none { it.text.contains("snake") && it.italic })
    }

    @Test fun `code spans and fences keep their text`() {
        val blocks = parseMarkdown("Run `./gradlew *test*` now\n\n```kotlin\nfun main() {\n    **not bold**\n}\n```\n\n    indented\n    code")
        val spans = paragraph(blocks[0])
        assertEquals("./gradlew *test*", spans.single { it.code }.text)
        val fence = assertIs<MdBlock.Code>(blocks[1])
        assertEquals("kotlin", fence.language)
        assertEquals("fun main() {\n    **not bold**\n}", fence.text)
        assertEquals("indented\ncode", assertIs<MdBlock.Code>(blocks[2]).text)
    }

    @Test fun `unclosed fence runs to the end`() {
        val code = assertIs<MdBlock.Code>(parseMarkdown("```\na\nb").single())
        assertEquals("a\nb", code.text)
    }

    @Test fun `fenced and inline code retain tabs`() {
        val blocks = parseMarkdown("```makefile\nall:\n\techo hello\n```\n\n  ```tsv\n  A\tB\n  ```\n\nUse `A\tB`.")
        assertEquals("all:\n\techo hello", assertIs<MdBlock.Code>(blocks[0]).text)
        assertEquals("A\tB", assertIs<MdBlock.Code>(blocks[1]).text)
        assertEquals("A\tB", paragraph(blocks[2]).single { it.code }.text)
    }

    @Test fun `indented code consumes structural tabs but retains content tabs`() {
        val code = assertIs<MdBlock.Code>(parseMarkdown("\t\tcmd\tvalue\n  \t\tmore\tdata").single())
        assertEquals("\tcmd\tvalue\n\tmore\tdata", code.text)
    }

    @Test fun `tab indented list fences retain code tabs`() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("-\titem\n\t```tsv\n\tA\tB\n\t```").single())
        assertEquals("item", text(paragraph(list.items.single().blocks[0])))
        assertEquals("A\tB", assertIs<MdBlock.Code>(list.items.single().blocks[1]).text)
    }

    @Test fun `lists nest, number and check`() {
        val blocks = parseMarkdown("- a\n  - nested\n- [x] done\n- [ ] todo\n\n3. three\n4. four\n   continued\n")
        val bullets = assertIs<MdBlock.ListBlock>(blocks[0])
        assertEquals(false, bullets.ordered)
        assertEquals(3, bullets.items.size)
        assertIs<MdBlock.ListBlock>(bullets.items[0].blocks[1])
        assertEquals(listOf(null, true, false), bullets.items.map { it.checked })
        assertEquals("done", text(paragraph(bullets.items[1].blocks.single())))
        val numbers = assertIs<MdBlock.ListBlock>(blocks[1])
        assertEquals(3, numbers.start)
        assertEquals("four continued", text(paragraph(numbers.items[1].blocks.single())))
    }

    @Test fun `loose list items separated by blank lines stay one list`() {
        val list = assertIs<MdBlock.ListBlock>(parseMarkdown("- a\n\n- b\n\n  more b\n\nafter").first())
        assertEquals(2, list.items.size)
        assertEquals(2, list.items[1].blocks.size)
    }

    @Test fun `quotes and rules`() {
        val blocks = parseMarkdown("> quoted\n> **line**\n\n---\n\n***")
        val quote = assertIs<MdBlock.Quote>(blocks[0])
        assertEquals("quoted line", text(paragraph(quote.blocks.single())))
        assertEquals(listOf(MdBlock.Rule, MdBlock.Rule), blocks.drop(1))
    }

    @Test fun `tables keep columns and escaped pipes`() {
        val table = assertIs<MdBlock.Table>(parseMarkdown("| Key | Value |\n|:---|---:|\n| `a\\|b` | **x** |\n| short |").single())
        assertEquals(listOf("Key", "Value"), table.header.map(::text))
        assertEquals(listOf("a|b", "x"), table.rows[0].map(::text))
        assertEquals(listOf("short", ""), table.rows[1].map(::text))
    }

    @Test fun `links of every kind`() {
        val spans = paragraph(parseMarkdown(
            "See [docs](docs/a.md \"title\"), [site][home], <https://x.dev>, mail <me@x.dev> and https://example.com/path.\n\n[home]: https://home.dev"
        ).single())
        assertEquals("See docs, site, https://x.dev, mail me@x.dev and https://example.com/path.", text(spans))
        assertEquals("docs/a.md", spans.first { it.text == "docs" }.link)
        assertEquals("https://home.dev", spans.first { it.text == "site" }.link)
        assertEquals("https://x.dev", spans.first { it.text == "https://x.dev" }.link)
        assertEquals("mailto:me@x.dev", spans.first { it.text == "me@x.dev" }.link)
        assertEquals("https://example.com/path", spans.first { it.text == "https://example.com/path" }.link)
    }

    @Test fun `images show alt text and badges keep their link`() {
        val spans = paragraph(parseMarkdown("[![CI](https://ci/badge.svg)](https://ci) ![](x.png) ![Logo **big**](logo.png)").single())
        val badge = spans.first { it.image && it.text == "CI" }
        assertEquals("https://ci", badge.link)
        assertTrue(spans.any { it.image && it.text == "Logo big" })
        assertEquals(2, spans.count { it.image })
    }

    @Test fun `html blocks keep only text`() {
        val blocks = parseMarkdown("<!-- hidden\nstill hidden -->\n<p align=\"center\">\n  <img src=\"logo.png\" alt=\"Preflight &amp; co\">\n  <br>\n  <b>Fast</b> viewer\n</p>\n\nText &copy; 2026 a&b")
        assertEquals(2, blocks.size)
        val html = paragraph(blocks[0])
        assertEquals("Preflight & co", html.first { it.image }.text)
        assertEquals("Preflight & co\nFast viewer", text(html))
        assertEquals("Text © 2026 a&b", text(paragraph(blocks[1])))
    }

    @Test fun `inline html keeps styles, links and cell breaks`() {
        val blocks = parseMarkdown(
            "<h1 align=\"center\">Preflight</h1>\n\n<p><b>Bold</b> and <a href=\"https://x.dev\"><code>code</code></a> <i>it</b></i></p>\n\n" +
                "<table>\n  <tr>\n    <td>\n      <h3>One</h3>\n      first\n    </td>\n    <td>second</td>\n  </tr>\n</table>"
        )
        assertEquals("Preflight", text(assertIs<MdBlock.Heading>(blocks[0]).text))
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        val inline = paragraph(blocks[1])
        assertEquals("Bold and code it", text(inline))
        assertTrue(inline.first { it.text == "Bold" }.bold)
        assertTrue(inline.first { it.text == "code" }.let { it.code && it.link == "https://x.dev" })
        assertTrue(inline.first { it.text == "it" }.italic)
        val table = paragraph(blocks[2])
        assertEquals("One\nfirst\nsecond", text(table))
        assertTrue(table.first { it.text.trim() == "One" }.bold)
    }

    @Test fun `escapes and stray markers stay literal`() {
        val spans = paragraph(parseMarkdown("\\*not\\* [not a link] `unclosed *a* **b").single())
        assertEquals("*not* [not a link] `unclosed a **b", text(spans))
        assertTrue(spans.first { it.text == "a" }.italic)
    }

    @Test fun `windows line endings and bom`() {
        val blocks = parseMarkdown("﻿# Hi\r\n\r\ntext\r\n")
        assertEquals("Hi", text(assertIs<MdBlock.Heading>(blocks[0]).text))
        assertEquals("text", text(paragraph(blocks[1])))
    }
}
