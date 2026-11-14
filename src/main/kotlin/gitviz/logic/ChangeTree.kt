package gitviz.logic

import gitviz.git.*

/** 改动列表里的一行：文件夹，或者文件。key 在同一组里唯一，列表靠它保持滚动位置。 */
sealed class ChangeRow(val key: String, val depth: Int) {
    /** path 是完整路径，以 / 结尾；label 是显示的名字，合并过的中间层写成 a/b/c/；paths 是它下面所有文件的路径。 */
    class Folder(val path: String, val label: String, depth: Int, val paths: List<String>, val collapsed: Boolean) : ChangeRow("d:$path", depth)

    class File(val file: WorkingFile, depth: Int) : ChangeRow("f:${file.path}", depth)
}

/** 勾选框的三种状态。 */
enum class CheckState { NONE, SOME, ALL }

internal fun checkState(paths: Collection<String>, checked: Set<String>): CheckState {
    val n = paths.count { it in checked }
    return when {
        n == 0 -> CheckState.NONE
        n == paths.size -> CheckState.ALL
        else -> CheckState.SOME
    }
}

private class Dir(val name: String) {
    val dirs = sortedMapOf<String, Dir>(String.CASE_INSENSITIVE_ORDER)
    val files = ArrayList<WorkingFile>()

    fun allPaths(): List<String> = files.map { it.path } + dirs.values.flatMap { it.allPaths() }
}

/**
 * 把改动文件整理成列表的行。
 * 平铺：保持原来的顺序，一个文件一行。
 * 按文件夹：一层一层展开，每层先文件夹后文件，根目录的文件排在最后；
 * 只有一个子目录、自己又没有文件的中间层，和子目录合成一行。
 * collapsed 里是收起的文件夹的完整路径（以 / 结尾），收起后不再列出它下面的行。
 */
internal fun flattenChanges(files: List<WorkingFile>, tree: Boolean, collapsed: Set<String> = emptySet()): List<ChangeRow> {
    if (!tree) return files.map { ChangeRow.File(it, 0) }
    val root = Dir("")
    for (file in files) {
        var node = root
        file.path.split('/').dropLast(1).forEach { part -> node = node.dirs.getOrPut(part) { Dir(part) } }
        node.files += file
    }
    val rows = ArrayList<ChangeRow>()
    fun walk(dir: Dir, path: String, depth: Int) {
        for (child in dir.dirs.values) {
            var node = child
            var label = child.name
            var full = "$path${child.name}/"
            while (node.files.isEmpty() && node.dirs.size == 1) {
                val only = node.dirs.values.single()
                label += "/${only.name}"
                full += "${only.name}/"
                node = only
            }
            val shut = full in collapsed
            rows += ChangeRow.Folder(full, "$label/", depth, node.allPaths(), shut)
            if (!shut) walk(node, full, depth + 1)
        }
        dir.files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path.substringAfterLast('/') }).forEach { rows += ChangeRow.File(it, depth) }
    }
    walk(root, "", 0)
    return rows
}

/** 所有文件夹的完整路径，不论有没有收起，用来一次收起全部。 */
internal fun allFolderPaths(files: List<WorkingFile>): Set<String> =
    flattenChanges(files, tree = true).filterIsInstance<ChangeRow.Folder>().map { it.path }.toSet()
