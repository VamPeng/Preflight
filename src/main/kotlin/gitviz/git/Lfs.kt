package gitviz.git

/** Git LFS 指针文件。Diff 里遇到时只显示指针信息，不读取大对象。 */
internal data class LfsPointer(val oid: String, val size: Long)

internal fun parseLfsPointer(bytes: ByteArray): LfsPointer? {
    if (bytes.size !in 1..1023) return null
    val text = strictText(bytes) ?: return null
    if (!text.endsWith('\n') || '\r' in text) return null
    val lines = text.dropLast(1).split('\n')
    if (lines.firstOrNull() !in setOf("version https://git-lfs.github.com/spec/v1", "version https://hawser.github.com/spec/v1")) return null
    val pairs = lines.map { it.substringBefore(' ') to it.substringAfter(' ', "") }
    if (pairs.any { !it.first.matches(Regex("[a-z0-9.-]+")) || it.second.isEmpty() || it.second.startsWith(' ') } || pairs.map { it.first }.distinct().size != pairs.size) return null
    if (pairs.drop(1).map { it.first } != pairs.drop(1).map { it.first }.sorted()) return null
    val fields = pairs.toMap()
    val oid = fields["oid"]?.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }?.removePrefix("sha256:") ?: return null
    val size = fields["size"]?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }?.toLongOrNull() ?: return null
    return LfsPointer(oid, size)
}
