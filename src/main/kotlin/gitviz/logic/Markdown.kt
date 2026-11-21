package gitviz.logic

/**
 * README 预览用的轻量 Markdown 解析：覆盖 README 常见写法（标题、段落、列表、引用、代码块、表格、
 * 分隔线、强调、行内代码、链接）。HTML 标签只保留文字，图片只显示替代文字，不读取任何外部资源。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: List<MdSpan>) : MdBlock
    data class Paragraph(val text: List<MdSpan>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Rule : MdBlock
}

/** checked 为空表示普通列表项，否则是任务列表的勾选状态。 */
data class MdListItem(val blocks: List<MdBlock>, val checked: Boolean? = null)

/** 一段同样式的文字。link 是原始地址；image 表示这段是图片的替代文字。 */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
    val image: Boolean = false,
)

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
    val references = HashMap<String, String>()
    for (line in lines) ReferenceDefinition.matchEntire(line)?.let { references.putIfAbsent(normalizeLabel(it.groupValues[1]), it.groupValues[2]) }
    return BlockParser(references).parse(lines)
}

private val ReferenceDefinition = Regex("""^ {0,3}\[([^\]]+)]:\s*<?(\S+?)>?(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*$""")
private val AtxHeading = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
private val ThematicBreak = Regex("""^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$""")
private val Fence = Regex("""^( {0,3})(`{3,}|~{3,})\s*([^`\s]*).*$""")
private val ListMarker = Regex("""^( {0,3})([-*+]|\d{1,9}[.)])( +|$)(.*)$""")
private val TableDelimiter = Regex("""^ {0,3}\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
private val SetextUnderline = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
private val HtmlBlockStart = Regex("""^ {0,3}</?([A-Za-z][A-Za-z0-9-]*)(\s|/?>|$)""")
private val HtmlHeading = Regex("""(?i)<h([1-6])(?:\s[^>]*)?>((?:(?!</h\1>).)*)</h\1>""")
private val TaskBox = Regex("""^\[([ xX])]\s+(.*)$""", RegexOption.DOT_MATCHES_ALL)

private fun normalizeLabel(label: String) = label.trim().lowercase().replace(Regex("\\s+"), " ")

private class BlockParser(private val references: Map<String, String>) {
    fun parse(lines: List<String>): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isEmpty()) return
            blocks += MdBlock.Paragraph(InlineParser(references).parse(joinParagraph(paragraph)))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flush(); i++; continue }
            if (paragraph.isEmpty() && ReferenceDefinition.matches(line)) { i++; continue }

            val fenceMatch = Fence.matchEntire(line)
            if (fenceMatch != null) {
                val m = fenceMatch
                flush()
                val indent = m.groupValues[1].length
                val fence = m.groupValues[2]
                val body = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val closing = lines[i].trimStart()
                    if (lines[i].length - closing.length <= 3 && closing.startsWith(fence) && closing.trimEnd().all { it == fence[0] }) { i++; break }
                    body += lines[i].removeIndent(indent)
                    i++
                }
                blocks += MdBlock.Code(m.groupValues[3], body.joinToString("\n"))
                continue
            }

            if (paragraph.isNotEmpty() && SetextUnderline.matches(line)) {
                val level = if (line.trim().startsWith("=")) 1 else 2
                blocks += MdBlock.Heading(level, InlineParser(references).parse(joinParagraph(paragraph)))
                paragraph.clear(); i++; continue
            }
            val heading = AtxHeading.matchEntire(line)
            if (heading != null) {
                flush()
                blocks += MdBlock.Heading(heading.groupValues[1].length, InlineParser(references).parse(heading.groupValues[2].trim()))
                i++; continue
            }
            if (ThematicBreak.matches(line)) { flush(); blocks += MdBlock.Rule; i++; continue }

            if (line.trimStart().startsWith("<!--")) {
                flush()
                while (i < lines.size && !lines[i].contains("-->")) i++
                i++
                continue
            }
            if (paragraph.isEmpty() && HtmlBlockStart.containsMatchIn(line)) {
                val html = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) { html += lines[i]; i++ }
                // HTML 里的换行和缩进只是空白，真正换行靠 <br> 和块级标签
                val source = html.joinToString(" ") { it.trim() }
                val heading = HtmlHeading.matchEntire(source)
                val spans = InlineParser(references).parse(heading?.groupValues?.get(2) ?: source).trimLineBreaks()
                if (spans.any { it.text.isNotBlank() }) blocks += if (heading != null) MdBlock.Heading(heading.groupValues[1].toInt(), spans) else MdBlock.Paragraph(spans)
                continue
            }

            if (line.trimStart().startsWith(">") && line.indexOf('>') <= 3) {
                flush()
                val quoted = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val l = lines[i]
                    val gt = l.indexOf('>')
                    quoted += if (gt in 0..3 && l.substring(0, gt).isBlank()) l.substring(gt + 1).removePrefix(" ") else l
                    i++
                }
                blocks += MdBlock.Quote(parse(quoted))
                continue
            }

            val marker = ListMarker.matchEntire(line)
            // 段落中间的数字开头（比如年份加句号）只在 1 开头时才打断段落，和 CommonMark 一致
            if (marker != null && (paragraph.isEmpty() || marker.groupValues[2].let { !it[0].isDigit() || it.dropLast(1) == "1" }) &&
                !(paragraph.isNotEmpty() && marker.groupValues[4].isBlank())) {
                flush()
                i = parseList(lines, i, blocks)
                continue
            }

            if (paragraph.isEmpty() && '|' in line && i + 1 < lines.size && '|' in lines[i + 1] && TableDelimiter.matches(lines[i + 1])) {
                val header = splitRow(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && '|' in lines[i]) { rows += splitRow(lines[i]); i++ }
                val inline = InlineParser(references)
                blocks += MdBlock.Table(header.map { inline.parse(it) }, rows.map { row -> List(header.size) { c -> inline.parse(row.getOrElse(c) { "" }) } })
                continue
            }

            if (paragraph.isEmpty() && line.startsWith("    ")) {
                val body = ArrayList<String>()
                while (i < lines.size && (lines[i].startsWith("    ") || lines[i].isBlank())) { body += lines[i].removeIndent(4); i++ }
                blocks += MdBlock.Code("", body.joinToString("\n").trimEnd('\n'))
                continue
            }

            paragraph += line
            i++
        }
        flush()
        return blocks
    }

    /** 从 start 开始读一整个列表，返回列表之后的那一行。 */
    private fun parseList(lines: List<String>, start: Int, blocks: MutableList<MdBlock>): Int {
        val first = ListMarker.matchEntire(lines[start])!!
        val ordered = first.groupValues[2][0].isDigit()
        val delimiter = first.groupValues[2].last()
        val items = ArrayList<MdListItem>()
        var i = start
        while (i < lines.size) {
            val m = ListMarker.matchEntire(lines[i]) ?: break
            val mark = m.groupValues[2]
            if (mark[0].isDigit() != ordered || mark.last() != delimiter) break
            val contentIndent = m.groupValues[1].length + mark.length + m.groupValues[3].length.coerceIn(1, 4)
            val body = arrayListOf(m.groupValues[4])
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    // 空行之后还缩进着，才算同一项；否则列表到此为止或换下一项
                    val next = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() } ?: break
                    if (lines[next].indentWidth() >= contentIndent) { body += ""; i++; continue }
                    break
                }
                if (l.indentWidth() >= contentIndent) { body += l.removeIndent(contentIndent); i++; continue }
                // 懒惰续行：没有缩进的文字接在上一段后面
                if (ListMarker.matches(l) || ThematicBreak.matches(l) || AtxHeading.matches(l) || Fence.matches(l) || l.trimStart().startsWith(">")) break
                if (body.last().isBlank()) break
                body += l.trim(); i++
            }
            // 列表项结尾的空行不属于它
            while (body.size > 1 && body.last().isBlank()) body.removeAt(body.lastIndex)
            val task = TaskBox.matchEntire(body[0])
            if (task != null) body[0] = task.groupValues[2]
            items += MdListItem(parse(body), task?.groupValues?.get(1)?.let { it != " " })
            // 两项之间的空行
            while (i < lines.size && lines[i].isBlank() && (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }?.let { ListMarker.matches(lines[it]) && lines[it].indentWidth() < contentIndent } == true) i++
        }
        val startNumber = if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1
        blocks += MdBlock.ListBlock(ordered, startNumber, items)
        return i
    }
}

private fun String.indentWidth() = length - trimStart(' ').length

private fun String.removeIndent(count: Int): String {
    var n = 0
    while (n < count && n < length && this[n] == ' ') n++
    return substring(n)
}

/** 段落里的换行：行尾两个空格或反斜杠是硬换行，其余合成一个空格。 */
private fun joinParagraph(lines: List<String>): String = buildString {
    lines.forEachIndexed { index, raw ->
        val line = raw.trimStart()
        val last = index == lines.lastIndex
        when {
            last -> append(line.trimEnd())
            line.endsWith("  ") -> append(line.trimEnd()).append('\n')
            line.endsWith("\\") && !line.endsWith("\\\\") -> append(line.dropLast(1)).append('\n')
            else -> append(line.trimEnd()).append(' ')
        }
    }
}

private fun splitRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith("|")) row = row.substring(1)
    if (row.endsWith("|") && !row.endsWith("\\|")) row = row.dropLast(1)
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var code = false
    var k = 0
    while (k < row.length) {
        val c = row[k]
        when {
            c == '\\' && k + 1 < row.length && row[k + 1] == '|' -> { cell.append('|'); k++ }
            c == '`' -> { code = !code; cell.append(c) }
            c == '|' && !code -> { cells += cell.toString().trim(); cell.clear() }
            else -> cell.append(c)
        }
        k++
    }
    cells += cell.toString().trim()
    return cells
}

private val SpacesAroundBreak = Regex(" *\n *")

/** HTML 块去掉首尾空白，换行两边的缩进空格也去掉。 */
private fun List<MdSpan>.trimLineBreaks(): List<MdSpan> {
    val list = map { it.copy(text = it.text.replace(SpacesAroundBreak, "\n")) }.toMutableList()
    // 换行在上一段末尾时，下一段开头的缩进也要去掉
    for (k in 1 until list.size) if (list[k - 1].text.endsWith("\n")) list[k] = list[k].copy(text = list[k].text.trimStart(' '))
    list.removeAll { it.text.isEmpty() }
    while (list.isNotEmpty() && list.first().text.isBlank()) list.removeAt(0)
    while (list.isNotEmpty() && list.last().text.isBlank()) list.removeAt(list.size - 1)
    if (list.isNotEmpty()) list[0] = list[0].copy(text = list[0].text.trimStart())
    if (list.isNotEmpty()) list[list.lastIndex] = list.last().copy(text = list.last().text.trimEnd())
    return list
}

private val Punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private val HtmlTag = Regex("""<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s+[^\s"'>/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*)\s*/?>""")
private val HtmlAttribute = Regex("""([^\s"'>/=]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""")
private val Autolink = Regex("""<((?:https?|mailto|ftp):[^\s<>]+|[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+)>""")
private val BareUrl = Regex("""https?://[^\s<]*[^\s<.,;:!?)'"*_~]""")
private val Entity = Regex("""&(#\d{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z]{2,8});""")
private val NamedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "copy" to "©", "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "times" to "×")

private data class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false, val link: String? = null, val code: Boolean = false)

/** 换行的块级 HTML 标签：开始和结束处各断一行。 */
private val HtmlBreakTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "td", "th", "table", "ul", "ol", "blockquote", "details", "summary", "center")

private class InlineParser(private val references: Map<String, String>) {
    private val out = ArrayList<MdSpan>()
    private val text = StringBuilder()
    private var style = Style()
    /** 还没闭合的 HTML 样式标签和进入它之前的样式。 */
    private val tags = ArrayList<Pair<String, Style>>()

    fun parse(source: String): List<MdSpan> {
        out.clear(); text.clear(); style = Style(); tags.clear()
        run(source)
        flushText()
        return merge(out)
    }

    private fun flushText() {
        if (text.isEmpty()) return
        out += MdSpan(text.toString(), style.bold, style.italic, style.strike, code = style.code, link = style.link)
        text.clear()
    }

    private fun withStyle(next: Style, body: () -> Unit) {
        flushText()
        val saved = style
        style = next
        body()
        flushText()
        style = saved
    }

    private fun run(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in Punctuation -> { text.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val ticks = s.countRun(i, '`')
                    val close = findTicks(s, i + ticks, ticks)
                    if (close < 0) { text.append("`".repeat(ticks)); i += ticks }
                    else {
                        var code = s.substring(i + ticks, close).replace('\n', ' ')
                        if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
                        flushText()
                        out += MdSpan(code, style.bold, style.italic, style.strike, code = true, link = style.link)
                        i = close + ticks
                    }
                }
                c == '!' && s.startsWith("![", i) -> {
                    val link = parseLink(s, i + 1)
                    if (link == null) { text.append(c); i++ }
                    else {
                        if (link.label.isNotBlank()) { flushText(); out += MdSpan(stripMarkup(link.label), link = style.link, image = true) }
                        i = link.end
                    }
                }
                c == '[' -> {
                    val link = parseLink(s, i)
                    if (link == null) { text.append(c); i++ }
                    else {
                        withStyle(style.copy(link = style.link ?: link.url)) { run(link.label) }
                        i = link.end
                    }
                }
                c == '<' -> i = html(s, i)
                c == '&' -> {
                    val m = Entity.matchAt(s, i)
                    if (m == null) { text.append(c); i++ } else { text.append(decodeEntity(m.groupValues[1]) ?: m.value); i += m.value.length }
                }
                (c == 'h') && style.link == null && (i == 0 || !s[i - 1].isLetterOrDigit()) && BareUrl.matchAt(s, i) != null -> {
                    val url = BareUrl.matchAt(s, i)!!.value
                    withStyle(style.copy(link = url)) { text.append(url) }
                    i += url.length
                }
                c == '~' && s.startsWith("~~", i) -> i = emphasis(s, i, "~~") { it.copy(strike = true) }
                c == '*' || c == '_' -> {
                    val run = s.countRun(i, c)
                    val left = if (i > 0) s[i - 1] else ' '
                    // 下划线夹在字母中间（snake_case）不算强调
                    if (c == '_' && left.isLetterOrDigit()) { text.append(c.toString().repeat(run)); i += run }
                    else if (run >= 2) i = emphasis(s, i, "$c$c") { it.copy(bold = true) }
                    else i = emphasis(s, i, "$c") { it.copy(italic = true) }
                }
                else -> { text.append(c); i++ }
            }
        }
    }

    /** 找到配对的结束符就加样式，找不到就当普通字符。 */
    private fun emphasis(s: String, i: Int, delimiter: String, apply: (Style) -> Style): Int {
        val from = i + delimiter.length
        if (from >= s.length || s[from].isWhitespace()) { text.append(delimiter); return from }
        val mark = delimiter[0]
        var k = from
        while (true) {
            var close = s.indexOf(delimiter, k)
            if (close < 0) { text.append(delimiter); return from }
            // ***x*** 里的 ** 取最后两个，留下中间的 *x* 做斜体
            if (delimiter.length == 2) while (s.getOrNull(close + 2) == mark) close++
            val inner = s.substring(from, close)
            val after = s.getOrNull(close + delimiter.length)
            val valid = inner.isNotEmpty() && !inner.last().isWhitespace() &&
                // 单个 * 不要吃掉 ** 的一半
                !(delimiter.length == 1 && (after == mark || s[close - 1] == mark)) &&
                !(delimiter[0] == '_' && after?.isLetterOrDigit() == true)
            if (valid && balancedCode(inner)) {
                withStyle(apply(style)) { run(inner) }
                return close + delimiter.length
            }
            k = close + 1
        }
    }

    private fun balancedCode(inner: String) = inner.count { it == '`' } % 2 == 0

    private fun html(s: String, i: Int): Int {
        Autolink.matchAt(s, i)?.let { m ->
            val target = m.groupValues[1]
            withStyle(style.copy(link = if ('@' in target && ':' !in target) "mailto:$target" else target)) { text.append(target) }
            return i + m.value.length
        }
        if (s.startsWith("<!--", i)) {
            val end = s.indexOf("-->", i + 4)
            return if (end < 0) s.length else end + 3
        }
        val m = HtmlTag.matchAt(s, i) ?: run { text.append('<'); return i + 1 }
        val closing = m.groupValues[1].isNotEmpty()
        val name = m.groupValues[2].lowercase()
        val attributes = HtmlAttribute.findAll(m.groupValues[3]).associate { a ->
            a.groupValues[1].lowercase() to (a.groups[2] ?: a.groups[3] ?: a.groups[4])!!.value
        }
        val styled: ((Style) -> Style)? = when (name) {
            "b", "strong" -> { st -> st.copy(bold = true) }
            "i", "em" -> { st -> st.copy(italic = true) }
            "s", "del", "strike" -> { st -> st.copy(strike = true) }
            "code", "kbd" -> { st -> st.copy(code = true) }
            "a" -> { st -> st.copy(link = st.link ?: attributes["href"]) }
            "h1", "h2", "h3", "h4", "h5", "h6" -> { st -> st.copy(bold = true) }
            else -> null
        }
        if (name in HtmlBreakTags) lineBreak()
        when {
            name == "br" -> text.append('\n')
            name == "img" && !closing -> {
                val alt = attributes["alt"].orEmpty().trim()
                if (alt.isNotEmpty()) { flushText(); out += MdSpan(decodeEntities(alt), link = style.link, image = true) }
            }
            styled != null && !closing && !m.value.endsWith("/>") -> { flushText(); tags += name to style; style = styled(style) }
            styled != null && closing -> {
                val open = tags.indexOfLast { it.first == name }
                if (open >= 0) { flushText(); style = tags[open].second; while (tags.size > open) tags.removeAt(tags.lastIndex) }
            }
        }
        return i + m.value.length
    }

    /** 已经在行首就不再换行，免得块级标签叠出空行。 */
    private fun lineBreak() {
        val previous = if (text.isNotEmpty()) text else out.lastOrNull()?.text ?: return
        if (previous.isNotBlank() && !previous.trimEnd(' ').endsWith("\n")) text.append('\n')
    }

    private class Link(val label: String, val url: String?, val end: Int)

    /** s[open] 是 '['。支持 [文字](地址)、[文字][引用]、[引用]。 */
    private fun parseLink(s: String, open: Int): Link? {
        var depth = 0
        var k = open
        var close = -1
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '`' -> { val t = s.countRun(k, '`'); val e = findTicks(s, k + t, t); if (e > 0) k = e + t - 1 }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { close = k; break } }
            }
            k++
        }
        if (close < 0) return null
        val label = s.substring(open + 1, close)
        if (close + 1 < s.length && s[close + 1] == '(') {
            var p = close + 2
            var parens = 1
            while (p < s.length) {
                when (s[p]) {
                    '\\' -> p++
                    '(' -> parens++
                    ')' -> { parens--; if (parens == 0) break }
                }
                p++
            }
            if (p >= s.length) return null
            val inside = s.substring(close + 2, p).trim()
            val url = if (inside.startsWith("<")) inside.substringAfter('<').substringBefore('>') else inside.split(Regex("\\s+"), 2)[0]
            return Link(label, url, p + 1)
        }
        if (close + 1 < s.length && s[close + 1] == '[') {
            val end = s.indexOf(']', close + 2)
            if (end > 0) {
                val ref = s.substring(close + 2, end).ifBlank { label }
                references[normalizeLabel(ref)]?.let { return Link(label, it, end + 1) }
            }
        }
        references[normalizeLabel(label)]?.let { return Link(label, it, close + 1) }
        return null
    }
}

private fun String.countRun(from: Int, c: Char): Int { var n = 0; while (from + n < length && this[from + n] == c) n++; return n }

/** 从 from 开始找长度正好为 count 的反引号串。 */
private fun findTicks(s: String, from: Int, count: Int): Int {
    var k = from
    while (k < s.length) {
        if (s[k] == '`') {
            val run = s.countRun(k, '`')
            if (run == count) return k
            k += run
        } else k++
    }
    return -1
}

private fun decodeEntity(body: String): String? = when {
    body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
    body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
    else -> NamedEntities[body]
}

private fun codePoint(value: Int) = if (Character.isValidCodePoint(value) && value != 0) String(Character.toChars(value)) else "�"

private fun decodeEntities(s: String) = Entity.replace(s) { decodeEntity(it.groupValues[1]) ?: it.value }

/** 图片替代文字里的 Markdown 只保留文字。 */
private fun stripMarkup(s: String) = InlineParser(emptyMap()).parse(s).joinToString("") { it.text }

/** 相邻同样式的片段合成一个，渲染时少建几段。 */
private fun merge(spans: List<MdSpan>): List<MdSpan> {
    val result = ArrayList<MdSpan>(spans.size)
    for (span in spans) {
        if (span.text.isEmpty()) continue
        val last = result.lastOrNull()
        if (last != null && last.copy(text = "") == span.copy(text = "")) result[result.size - 1] = last.copy(text = last.text + span.text)
        else result += span
    }
    return result
}
