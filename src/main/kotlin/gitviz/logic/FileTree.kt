package gitviz.logic

import gitviz.git.*
import gitviz.ui.t
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 文件在 git 眼里的状态。CLEAN：已跟踪且没改，或者是目录。
 * REMOVED：git rm --cached 过，已从版本库移出、文件还留在磁盘上。
 */
enum class FileMark { CLEAN, MODIFIED, ADDED, DELETED, RENAMED, UNTRACKED, IGNORED, CONFLICTED, REMOVED }

/**
 * 目录里的一项。path 相对工作区，用 / 分隔。
 * ignoredRoot：被忽略规则直接匹配到的那一项（比如 build/），它下面的都跟着被忽略，只在这一项上标出来。
 * dirty：目录里有改动。missing：已删除、磁盘上没有了，只在 git 里还有记录。
 * modified：最后修改时间（毫秒），missing 时为 0。
 */
class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val mark: FileMark,
    val ignoredRoot: Boolean = false,
    val dirty: Boolean = false,
    val missing: Boolean = false,
    val size: Long = 0,
    val modified: Long = 0,
) {
    val ignored: Boolean get() = mark == FileMark.IGNORED
}

/**
 * 一次 git status --ignored=matching 的结果，按路径查状态。
 * 目录结构从磁盘读，这里只负责标状态，以及补上磁盘上已经没有的已删除文件。
 * 被忽略的目录 git 只报一行 build/，不会列出里面的文件，所以要看祖先目录。
 */
class TreeStatus(files: List<WorkingFile>) {
    private val byPath = HashMap<String, WorkingFile>()
    private val removed = HashSet<String>()
    private val ignoredDirs = HashSet<String>()
    private val ignoredFiles = HashSet<String>()
    private val changedDirs = HashSet<String>()
    /** 每个目录下 git 有记录的子项：目录 → 子项名字 → 是不是目录。 */
    private val knownChildren = HashMap<String, HashMap<String, Boolean>>()

    init {
        for (f in files) {
            if (f.index == '!') {
                if (f.path.endsWith('/')) ignoredDirs += f.path.trimEnd('/') else ignoredFiles += f.path
                continue
            }
            val path = f.path.trimEnd('/')
            val earlier = byPath[path]
            // git rm --cached 之后同一个路径报两行：D（从版本库删除）和 ??（磁盘上的文件没人跟踪了）
            if (earlier != null && (earlier.untracked || f.untracked)) {
                removed += path
                if (f.untracked) continue // 留着 D 那一行，查看改动时看的是这次删除
            }
            byPath[path] = f
            var dir = parentOf(path)
            var child = path
            while (true) {
                knownChildren.getOrPut(dir) { HashMap() }.merge(child.substringAfterLast('/'), child != path) { a, b -> a || b }
                if (dir.isEmpty()) break
                changedDirs += dir
                child = dir
                dir = parentOf(dir)
            }
        }
    }

    /** 有改动的文件数，git status 里每个文件一行（不含被忽略的）。 */
    val changeCount: Int get() = byPath.size

    /** 本地改动里能找到的那一项，用来跳过去看改动。 */
    fun working(path: String): WorkingFile? = byPath[path]

    /** path 本身被忽略规则匹配到。 */
    fun isIgnoredRoot(path: String): Boolean = path in ignoredFiles || path in ignoredDirs

    fun isIgnored(path: String): Boolean {
        if (isIgnoredRoot(path)) return true
        var dir = parentOf(path)
        while (dir.isNotEmpty()) {
            if (dir in ignoredDirs) return true
            dir = parentOf(dir)
        }
        return false
    }

    fun hasChanges(dir: String): Boolean = dir in changedDirs

    fun mark(path: String, isDir: Boolean): FileMark {
        if (isIgnored(path)) return FileMark.IGNORED
        if (isDir) return FileMark.CLEAN
        if (path in removed) return FileMark.REMOVED
        val f = byPath[path] ?: return FileMark.CLEAN
        return when {
            f.conflicted -> FileMark.CONFLICTED
            f.untracked -> FileMark.UNTRACKED
            else -> when (if (f.worktree != ' ') f.worktree else f.index) {
                'A' -> FileMark.ADDED
                'D' -> FileMark.DELETED
                'R', 'C' -> FileMark.RENAMED
                else -> FileMark.MODIFIED
            }
        }
    }

    /** dir 下 git 有记录的子项（名字 → 是不是目录），用来补上已经从磁盘删掉的文件。 */
    fun knownChildren(dir: String): Map<String, Boolean> = knownChildren[dir].orEmpty()

    companion object {
        val Empty = TreeStatus(emptyList())
    }
}

internal fun parentOf(path: String): String = path.substringBeforeLast('/', "")

/** 一个目录列出来的结果。omitted：太多了没列出来的个数。 */
class DirListing(val entries: List<FileEntry>, val omitted: Int = 0)

/** 一个目录最多列多少项，node_modules 这样的目录一层就可能有上万个。 */
internal const val MAX_DIR_ENTRIES = 5000

/**
 * 列出工作区里 dir（相对路径，根目录是 ""）的直接子项，从磁盘读，包括被忽略的。
 * .git 不列。已删除、还没提交的文件磁盘上没有，按 git status 补上，标成 missing。
 * 目录在前，其余按名字排序，不分大小写。符号链接当成文件，不跟进去，免得绕圈。
 */
internal fun listDirectory(root: File, dir: String, status: TreeStatus): DirListing {
    val folder = if (dir.isEmpty()) root else File(root, dir)
    val children = folder.listFiles().orEmpty().filter { it.name != ".git" }
    val onDisk = HashSet<String>()
    val entries = ArrayList<FileEntry>()
    for (file in children) {
        onDisk += file.name
        val path = if (dir.isEmpty()) file.name else "$dir/${file.name}"
        val isDir = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        entries += FileEntry(
            name = file.name,
            path = path,
            isDir = isDir,
            mark = status.mark(path, isDir),
            ignoredRoot = status.isIgnoredRoot(path),
            dirty = isDir && status.hasChanges(path),
            size = if (isDir) 0 else file.length(),
            modified = file.lastModified(),
        )
    }
    for ((name, isDir) in status.knownChildren(dir)) {
        if (name in onDisk) continue
        val path = if (dir.isEmpty()) name else "$dir/$name"
        entries += FileEntry(name, path, isDir, if (isDir) FileMark.CLEAN else status.mark(path, false),
            dirty = isDir, missing = true)
    }
    entries.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }, { it.name }))
    return if (entries.size > MAX_DIR_ENTRIES) DirListing(entries.subList(0, MAX_DIR_ENTRIES).toList(), entries.size - MAX_DIR_ENTRIES)
    else DirListing(entries)
}

/**
 * 文件树的一行：一个文件或目录，或者展开的目录下面的提示行。
 * Item.expanded：目录是否展开，文件为 null。hit：筛选时名字里匹配的那一段。
 */
sealed class DirRow(val key: String, val depth: Int) {
    class Item(val entry: FileEntry, depth: Int, val expanded: Boolean?, val hit: IntRange? = null) : DirRow(entry.path, depth)
    /** 展开的目录还没读到。 */
    class Loading(dir: String, depth: Int) : DirRow("$dir/…loading", depth)
    /** 展开的目录里没有（看得见的）东西：真的是空的，或者只有被隐藏的忽略文件。 */
    class Note(dir: String, depth: Int, val text: String) : DirRow("$dir/…note", depth)
}

/**
 * 按展开的目录把工作区摊成一棵树，文件和目录都在里面，目录在前。还没读到的展开目录下面放一行 Loading。
 * 目录一律可以展开：不预先去看里面有没有东西，展开了才读。
 */
internal fun flattenDirTree(listings: Map<String, DirListing>, expanded: Set<String>, showIgnored: Boolean): List<DirRow> {
    val rows = ArrayList<DirRow>()
    fun visible(e: FileEntry) = showIgnored || !e.ignored
    fun walk(dir: String, depth: Int) {
        val listing = listings[dir] ?: run {
            rows += DirRow.Loading(dir, depth)
            return
        }
        val shown = listing.entries.filter(::visible)
        if (shown.isEmpty() && dir.isNotEmpty()) {
            val hidden = listing.entries.size - shown.size
            rows += DirRow.Note(dir, depth, if (hidden > 0) t("Only ignored files here", "这里只有被忽略的文件") else t("Empty folder", "空文件夹"))
        }
        for (entry in shown) {
            val open = entry.isDir && entry.path in expanded
            rows += DirRow.Item(entry, depth, if (entry.isDir) open else null)
            if (open) walk(entry.path, depth + 1)
        }
        if (listing.omitted > 0) rows += DirRow.Note(dir, depth, t("${listing.omitted} more not listed", "还有 ${listing.omitted} 项没有列出"))
    }
    walk("", 0)
    return rows
}

/** 需要读的目录：根目录，加上所有祖先都展开了的展开目录。 */
internal fun directoriesToRead(expanded: Set<String>): List<String> {
    val dirs = linkedSetOf("")
    for (dir in expanded.sorted()) {
        var parent = parentOf(dir)
        var visible = true
        while (parent.isNotEmpty()) {
            if (parent !in expanded) { visible = false; break }
            parent = parentOf(parent)
        }
        if (visible) dirs += dir
    }
    return dirs.toList()
}

/** 筛选用的全量扫描结果：每个读过的目录一份列表。truncated：条目太多，只扫了一部分。 */
class TreeScan(val listings: Map<String, DirListing>, val truncated: Boolean)

/** 筛选最多扫描这么多项，超出的不扫，界面上说明结果不完整。 */
internal const val MAX_SCAN_ENTRIES = 200_000

/**
 * 从根目录起把工作区所有目录读一遍，给「按名称筛选」用。被忽略的目录（build、node_modules）只列它本身，不进去；
 * 已经从磁盘删掉的目录不进。在后台线程调用，cancelled 为 true 时尽快停下。
 */
internal fun scanWorkTree(root: File, status: TreeStatus, maxEntries: Int = MAX_SCAN_ENTRIES, cancelled: () -> Boolean = { false }): TreeScan {
    val listings = HashMap<String, DirListing>()
    val queue = ArrayDeque<String>().apply { add("") }
    var total = 0
    var truncated = false
    while (queue.isNotEmpty()) {
        if (cancelled()) break
        if (total >= maxEntries) { truncated = true; break }
        val dir = queue.removeFirst()
        val listing = runCatching { listDirectory(root, dir, status) }.getOrNull() ?: continue
        listings[dir] = listing
        total += listing.entries.size
        if (listing.omitted > 0) truncated = true
        for (e in listing.entries) if (e.isDir && !e.ignored && !e.missing) queue.add(e.path)
    }
    return TreeScan(listings, truncated)
}

/**
 * 按名称筛选（不分大小写，匹配名字里的任意一段）：留下匹配的项和它们的所有上级目录，上级全部展开。
 * 目录名本身匹配时只显示它自己，不把里面的东西都带出来（里面名字匹配的还是会出现）。
 */
internal fun filterTree(scan: TreeScan, query: String, showIgnored: Boolean): List<DirRow> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    fun visible(e: FileEntry) = showIgnored || !e.ignored
    // 每个目录里有没有匹配的后代，算过的记下来，免得重复遍历
    val memo = HashMap<String, Boolean>()
    fun hasMatch(dir: String): Boolean = memo.getOrPut(dir) {
        scan.listings[dir]?.entries.orEmpty().any { e -> visible(e) && (e.name.lowercase().contains(q) || (e.isDir && hasMatch(e.path))) }
    }
    val rows = ArrayList<DirRow>()
    fun walk(dir: String, depth: Int) {
        for (e in scan.listings[dir]?.entries.orEmpty()) {
            if (!visible(e)) continue
            val at = e.name.lowercase().indexOf(q)
            val inside = e.isDir && hasMatch(e.path)
            if (at < 0 && !inside) continue
            rows += DirRow.Item(e, depth, if (e.isDir) inside else null, if (at >= 0) at until at + q.length else null)
            if (inside) walk(e.path, depth + 1)
        }
    }
    walk("", 0)
    return rows
}

/** path 的所有祖先目录，不含根目录 ""，从近到远。 */
internal fun ancestorsOf(path: String): List<String> {
    val result = ArrayList<String>()
    var dir = parentOf(path)
    while (dir.isNotEmpty()) {
        result += dir
        dir = parentOf(dir)
    }
    return result
}

/** 文件大小，比如 812 B、4.2 KB、1.3 MB。 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
}

/** 忽略规则写到哪里：.gitignore 会提交、大家共用；.git/info/exclude 只在这台电脑上生效。 */
enum class IgnoreTarget { GITIGNORE, EXCLUDE }

/** 一条锚定到这一个路径的忽略规则：/ 开头，目录末尾加 /，通配符和末尾空格转义成字面。 */
internal fun ignoreRule(path: String, isDir: Boolean): String {
    // 前面有 / 了，# 和 ! 不会被当成注释或取反
    val escaped = path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[")
        .let { if (it.endsWith(" ")) it.dropLast(1) + "\\ " else it }
    return "/$escaped" + if (isDir) "/" else ""
}

/**
 * 把这些路径的规则追加到忽略文件末尾，文件没以换行结尾时先补一个。文件或所在目录不存在就创建。
 * 已经有一模一样的规则就不再加。
 */
internal fun appendIgnoreRules(file: File, paths: List<String>, isDir: Boolean) {
    val existing = if (file.isFile) file.readText() else ""
    val present = existing.lineSequence().map { it.trimEnd('\r') }.toHashSet()
    val lines = paths.map { ignoreRule(it, isDir) }.distinct().filter { it !in present }
    if (lines.isEmpty()) return
    file.parentFile?.mkdirs()
    val prefix = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
    file.appendText(prefix + lines.joinToString("") { it + "\n" })
}
