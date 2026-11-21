package gitviz.ui.preview

import gitviz.logic.*
import gitviz.ui.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** 正文最宽多少，太宽的行读起来累。 */
private val DocumentMaxWidth = 860.dp

/** 纯文本按这么多行一段排版，单个 Text 不至于太大。 */
private const val PLAIN_LINES_PER_CHUNK = 200

private val CellPadding = 6.dp

/**
 * 一份文本文件的预览：Markdown 渲染成富文本，其余按等宽纯文本。
 * 整篇在一个 SelectionContainer 里，可以跨段落拖选，⌘C／Ctrl+C 或右键复制。
 */
@Composable
internal fun TextDocumentView(doc: TextDocument, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        SelectionContainer(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = DocumentMaxWidth).fillMaxWidth().padding(horizontal = 32.dp)) {
                    val markdown = doc.markdown
                    if (markdown != null) MarkdownBlocks(markdown, spacing = 12.dp)
                    else PlainText(doc.text)
                    if (doc.truncated) DisableSelection {
                        Text(t("The file is larger than ${TEXT_PREVIEW_MAX_BYTES / 1024} KB; only the beginning is shown.",
                            "文件超过 ${TEXT_PREVIEW_MAX_BYTES / 1024} KB，只显示开头部分。"),
                            fontSize = TypeScale.Small, color = Palette.Muted, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/**
 * 复制时插在两段文字之间的分隔符。SelectionContainer 把选中的各段文字直接拼在一起，
 * 段落、单元格之间要靠它补上换行或制表符。不占空间、看不见；位置决定它排在哪两段之间
 * （按左上角先上后左排序）。
 */
@Composable
internal fun SelectionBreak(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 1.sp, lineHeight = 1.sp, color = Color.Transparent, modifier = modifier.height(0.dp))
}

private const val TAB_COLUMNS = 4

/** 量字符宽度时重复这么多次再平均，抵消排版结果取整到像素的误差。 */
private const val TAB_SAMPLE = 64

private val CodeTextStyle = TextStyle(fontFamily = CodeFont, fontSize = 12.5.sp, lineHeight = 19.sp, letterSpacing = 0.sp)

/**
 * Compose 把 Tab 画成一个字符宽，Makefile 缩进和 TSV 列都挤在一起。显示时给每个 Tab 加字距，
 * 补到下一个四列 Tab stop；文字本身不变，选中复制出来仍是 Tab。只用于等宽文字。
 */
internal fun alignTabs(text: String, style: TextStyle, measurer: TextMeasurer, density: Density): AnnotatedString {
    if ('\t' !in text) return AnnotatedString(text)
    val fixed = style.copy(letterSpacing = 0.sp)
    fun advance(c: Char) = measurer.measure(c.toString().repeat(TAB_SAMPLE), fixed).size.width / TAB_SAMPLE.toFloat()
    val column = advance('0')
    val tab = advance('\t')
    return buildAnnotatedString {
        append(text)
        var col = 0
        text.forEachIndexed { i, c ->
            when {
                c == '\n' -> col = 0
                c == '\t' -> {
                    val span = TAB_COLUMNS - col % TAB_COLUMNS
                    addStyle(SpanStyle(letterSpacing = with(density) { (span * column - tab).toSp() }), i, i + 1)
                    col += span
                }
                !c.isLowSurrogate() -> col++
            }
        }
    }
}

@Composable
private fun CodeText(text: String, softWrap: Boolean = true) {
    val style = LocalTextStyle.current.merge(CodeTextStyle)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val shown = remember(text, style, measurer, density) { alignTabs(text, style, measurer, density) }
    Text(shown, style = style, softWrap = softWrap)
}

@Composable
private fun PlainText(text: String) {
    val chunks = remember(text) { text.lines().chunked(PLAIN_LINES_PER_CHUNK).map { it.joinToString("\n") } }
    chunks.forEachIndexed { index, chunk ->
        Box {
            CodeText(chunk)
            if (index < chunks.lastIndex) SelectionBreak("\n", Modifier.align(Alignment.BottomStart))
        }
    }
}

/** 一串块，之间留 spacing 的空；复制时块与块之间空一行。 */
@Composable
private fun MarkdownBlocks(blocks: List<MdBlock>, spacing: androidx.compose.ui.unit.Dp = 10.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        blocks.forEachIndexed { index, block ->
            Box {
                MarkdownBlock(block)
                if (index < blocks.lastIndex) SelectionBreak("\n\n", Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Column(Modifier.fillMaxWidth().padding(top = if (block.level <= 2) 6.dp else 2.dp)) {
            val size = when (block.level) { 1 -> 24.sp; 2 -> 20.sp; 3 -> 17.sp; else -> TypeScale.Body }
            Text(inlineText(block.text), fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = size * 1.3f)
            if (block.level <= 2) HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        is MdBlock.Paragraph -> Text(inlineText(block.text), fontSize = TypeScale.Body, lineHeight = 22.sp)
        is MdBlock.Code -> {
            val shape = RoundedCornerShape(6.dp)
            Box(Modifier.fillMaxWidth().clip(shape).background(Palette.Panel).border(1.dp, Palette.Line, shape)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                CodeText(block.text, softWrap = false)
            }
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.Line, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalContentColor provides Palette.Muted) { MarkdownBlocks(block.blocks) }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Box {
                    Row {
                        val marker = when {
                            item.checked == true -> "☑"
                            item.checked == false -> "☐"
                            block.ordered -> "${block.start + index}."
                            else -> "•"
                        }
                        // 项目符号只是装饰，复制时不带
                        DisableSelection {
                            Text(marker, fontSize = TypeScale.Body, lineHeight = 22.sp, color = Palette.Muted, modifier = Modifier.widthIn(min = 18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.weight(1f)) { MarkdownBlocks(item.blocks) }
                    }
                    if (index < block.items.lastIndex) SelectionBreak("\n", Modifier.align(Alignment.BottomStart))
                }
            }
        }
        is MdBlock.Table -> MarkdownTable(block)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), thickness = 2.dp, color = Palette.Line)
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    // 列宽按每列最长的文字估算，表头和各行对得齐；太宽的表可以左右滑动
    val widths = remember(table) {
        table.header.indices.map { c ->
            val longest = (listOf(table.header[c]) + table.rows.map { it[c] }).maxOf { cell -> cell.sumOf { it.text.length } }
            (longest.coerceIn(4, 40) * 7.6f + 24).dp
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.horizontalScroll(rememberScrollState()).clip(shape).border(1.dp, Palette.Line, shape)) {
        val rows = listOf(table.header) + table.rows
        rows.forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider()
            Row(Modifier.background(if (r == 0) Palette.Panel else Color.Transparent).height(IntrinsicSize.Min)) {
                row.forEachIndexed { c, cell ->
                    Box(Modifier.width(widths[c])) {
                        Text(inlineText(cell), fontSize = TypeScale.Small, lineHeight = 19.sp,
                            fontWeight = if (r == 0) FontWeight.SemiBold else null,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = CellPadding))
                        // 和单元格文字同一高度、在它右边，复制成制表符分隔的表格
                        val last = c == row.lastIndex
                        if (!last || r < rows.lastIndex) SelectionBreak(if (last) "\n" else "\t", Modifier.align(Alignment.TopEnd).padding(top = CellPadding))
                    }
                }
            }
        }
    }
}

/** 只有网页和邮件地址能点；仓库内的相对链接和锚点按普通文字显示。 */
internal fun isExternalLink(url: String) = url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("mailto:", true)

@Composable
private fun inlineText(spans: List<MdSpan>): AnnotatedString {
    val link = Palette.Link
    val codeBackground = Palette.Raised
    val muted = Palette.Muted
    return remember(spans, link, codeBackground, muted) {
        buildAnnotatedString {
            for (span in spans) {
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) CodeFont else null,
                    fontSize = if (span.code) 0.9.em else TextUnit.Unspecified,
                    background = if (span.code) codeBackground else Color.Unspecified,
                    color = if (span.image) muted else Color.Unspecified,
                )
                // 图片不加载，只显示替代文字
                val text = if (span.image) "[${span.text}]" else span.text
                val url = span.link?.takeIf(::isExternalLink)
                if (url != null) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link)))) { withStyle(style) { append(text) } }
                else withStyle(style) { append(text) }
            }
        }
    }
}
