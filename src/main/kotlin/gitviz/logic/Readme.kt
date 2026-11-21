package gitviz.logic

import java.io.File

/**
 * 预览区显示的一份文本文件。markdown 为空表示按纯文本显示。
 * README 是第一个用到的地方，以后文件预览也用它。
 */
class TextDocument(
    val file: File,
    /** 相对工作区的路径，预览区标题栏显示用。 */
    val path: String,
    val text: String,
    val markdown: List<MdBlock>?,
    /** 文件超过 [TEXT_PREVIEW_MAX_BYTES]，只读了开头。 */
    val truncated: Boolean,
)

/** 文本预览最多读这么多字节，再大的只预览开头。 */
const val TEXT_PREVIEW_MAX_BYTES = 512 * 1024

private val MarkdownExtensions = setOf("md", "markdown", "mdown", "mkdn", "mkd")
private val ReadmeName = Regex("""(?i)readme(?:\.(md|markdown|mdown|mkdn|mkd|txt|rst|adoc|asciidoc|org))?""")
private val ChineseReadme = Regex("""(?i)readme[._-]zh(?:[-_](?:cn|hans|sc))?\.(md|markdown)""")

/**
 * 读取一个文本文件用于预览，Markdown 后缀的顺便解析。文件是空的或读不出来返回 null。
 * 会读磁盘，在 IO 线程调用。
 */
fun loadTextDocument(file: File, workTree: File): TextDocument? = runCatching {
    val bytes = file.inputStream().use { it.readNBytes(TEXT_PREVIEW_MAX_BYTES + 1) }
    val truncated = bytes.size > TEXT_PREVIEW_MAX_BYTES
    // 截断处可能切开一个多字节字符，解码时会变成替换字符，去掉它
    var text = String(bytes, 0, minOf(bytes.size, TEXT_PREVIEW_MAX_BYTES), Charsets.UTF_8).removePrefix("﻿")
    if (truncated) text = text.trimEnd('�')
    if (text.isBlank()) return null
    val markdown = if (file.extension.lowercase() in MarkdownExtensions) parseMarkdown(text) else null
    TextDocument(file, file.relativeTo(workTree).invariantSeparatorsPath, text, markdown, truncated)
}.getOrNull()

/**
 * 找工作区的 README：按 GitHub 的顺序看根目录、.github、docs，同一处 Markdown 优先。
 * preferChinese 时先找根目录的 README.zh-CN.md 这类中文版。
 */
fun findReadme(workTree: File, preferChinese: Boolean = false): File? {
    fun files(dir: File) = dir.listFiles()?.filter { it.isFile }.orEmpty()
    if (preferChinese) files(workTree).filter { ChineseReadme.matches(it.name) }.minByOrNull { it.name.length }?.let { return it }
    for (dir in listOf(workTree, File(workTree, ".github"), File(workTree, "docs"))) {
        files(dir).filter { ReadmeName.matches(it.name) }.minByOrNull { readmeRank(it.name) }?.let { return it }
    }
    return null
}

private fun readmeRank(name: String): Int = when (val ext = name.substringAfter('.', "").lowercase()) {
    "md" -> 0
    in MarkdownExtensions -> 1
    "" -> 2
    "txt" -> 3
    else -> 4 + ext.length
}

/** 打开仓库时要预览的 README，没有就返回 null。在 IO 线程调用。 */
fun loadReadme(workTree: File, preferChinese: Boolean = false): TextDocument? =
    findReadme(workTree, preferChinese)?.let { loadTextDocument(it, workTree) }
