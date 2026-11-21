package gitviz.logic

import gitviz.git.*
import gitviz.ui.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 文件页：目录从磁盘读，状态和已删除的文件来自 git status。 */
class FileTreeTest {
    init { Settings.language = Language.EN }

    private val root: File = createTempDirectory("gitviz-files").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun git(vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    private fun write(path: String, text: String = "x") {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    /** 一个提交过的仓库：src/a.kt、src/b.kt、src/old/gone.kt、README.md，忽略 build/ 和 *.log。 */
    private fun repo(): GitCli {
        git("init", "-q")
        git("config", "user.name", "t")
        git("config", "user.email", "t@t")
        git("config", "commit.gpgsign", "false")
        write(".gitignore", "build/\n*.log\n")
        write("README.md")
        write("src/a.kt")
        write("src/b.kt")
        write("src/old/gone.kt")
        git("add", ".")
        git("commit", "-q", "-m", "init")
        return GitCli(root)
    }

    private fun listing(cli: GitCli, dir: String) = listDirectory(root, dir, TreeStatus(cli.treeStatus()))

    private fun names(l: DirListing) = l.entries.map { (if (it.isDir) "${it.name}/" else it.name) + if (it.missing) " (missing)" else "" }

    @Test
    fun `lists ignored files from disk and marks where the ignore rule matched`() {
        val cli = repo()
        write("build/libs/app.jar")
        write("debug.log")
        val top = listing(cli, "")
        assertEquals(listOf("build/", "src/", ".gitignore", "debug.log", "README.md"), names(top))
        val build = top.entries.first { it.name == "build" }
        assertEquals(FileMark.IGNORED, build.mark)
        assertTrue(build.ignoredRoot)
        assertEquals(FileMark.IGNORED, top.entries.first { it.name == "debug.log" }.mark)
        // 被忽略目录里面的东西 git 不会逐个报，靠祖先目录判断
        val inside = listing(cli, "build/libs").entries.single()
        assertEquals(FileMark.IGNORED, inside.mark)
        assertFalse(inside.ignoredRoot)
    }

    @Test
    fun `git directory is not listed`() {
        val cli = repo()
        assertFalse(listing(cli, "").entries.any { it.name == ".git" })
    }

    @Test
    fun `marks changes and flags folders that contain them`() {
        val cli = repo()
        write("src/a.kt", "changed")
        write("src/new.kt")
        val top = listing(cli, "")
        assertTrue(top.entries.first { it.name == "src" }.dirty)
        assertEquals(FileMark.CLEAN, top.entries.first { it.name == "README.md" }.mark)
        val src = listing(cli, "src").entries.associateBy { it.name }
        assertEquals(FileMark.MODIFIED, src.getValue("a.kt").mark)
        assertEquals(FileMark.UNTRACKED, src.getValue("new.kt").mark)
        assertEquals(FileMark.CLEAN, src.getValue("b.kt").mark)
    }

    @Test
    fun `deleted files and folders come back from git status`() {
        val cli = repo()
        File(root, "src/b.kt").delete()
        File(root, "src/old").deleteRecursively()
        val src = listing(cli, "src")
        assertEquals(listOf("old/ (missing)", "a.kt", "b.kt (missing)"), names(src))
        assertEquals(FileMark.DELETED, src.entries.first { it.name == "b.kt" }.mark)
        // 整个文件夹都删了，磁盘上没有它，也能展开看到里面删掉的文件
        val old = listing(cli, "src/old").entries.single()
        assertEquals("gone.kt", old.name)
        assertTrue(old.missing)
        assertEquals(FileMark.DELETED, old.mark)
    }

    @Test
    fun `committed deletions are gone`() {
        val cli = repo()
        git("rm", "-q", "src/b.kt")
        assertEquals(FileMark.DELETED, listing(cli, "src").entries.first { it.name == "b.kt" }.mark)
        git("commit", "-q", "-m", "drop b")
        assertEquals(listOf("old/", "a.kt"), names(listing(cli, "src")))
    }

    @Test
    fun `rm --cached shows as removed from the repository, not just untracked`() {
        val cli = repo()
        git("rm", "-q", "--cached", "src/b.kt")
        val status = TreeStatus(cli.treeStatus())
        val b = listDirectory(root, "src", status).entries.first { it.name == "b.kt" }
        assertFalse(b.missing)
        assertEquals(FileMark.REMOVED, b.mark)
        assertEquals('D', status.working("src/b.kt")?.index)
    }

    @Test
    fun `symlinked folders are not followed`() {
        val cli = repo()
        java.nio.file.Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        val link = listing(cli, "").entries.first { it.name == "loop" }
        assertFalse(link.isDir)
    }

    @Test
    fun `huge folders are cut off`() {
        val cli = repo()
        val many = File(root, "build/many").apply { mkdirs() }
        repeat(MAX_DIR_ENTRIES + 3) { File(many, "f$it").createNewFile() }
        val l = listing(cli, "build/many")
        assertEquals(MAX_DIR_ENTRIES, l.entries.size)
        assertEquals(3, l.omitted)
    }

    private fun label(r: DirRow) = "  ".repeat(r.depth) + when (r) {
        is DirRow.Item -> r.entry.name + (if (r.expanded == true) "/" else if (r.expanded == false) "›" else "")
        is DirRow.Loading -> "…"
        is DirRow.Note -> "(" + r.text + ")"
    }

    @Test
    fun `flattened tree mixes folders and files and hides ignored ones on request`() {
        val cli = repo()
        write("build/sub/out.txt"); write("debug.log")
        val status = TreeStatus(cli.treeStatus())
        val listings = listOf("", "src").associateWith { listDirectory(root, it, status) }
        fun rows(showIgnored: Boolean, expanded: Set<String>) = flattenDirTree(listings, expanded, showIgnored).map(::label)
        assertEquals(listOf("build›", "src›", ".gitignore", "debug.log", "README.md"), rows(true, emptySet()))
        assertEquals(listOf("src›", ".gitignore", "README.md"), rows(false, emptySet()))
        // 展开 src：目录在前，里面的文件跟在后面
        assertEquals(listOf("build›", "src/", "  old›", "  a.kt", "  b.kt", ".gitignore", "debug.log", "README.md"), rows(true, setOf("src")))
        // build 还没读过：先放一行“正在读取”
        assertEquals(listOf("build/", "  …", "src›", ".gitignore", "debug.log", "README.md"), rows(true, setOf("build")))
    }

    @Test
    fun `sorts by name, size or modified time with folders first`() {
        fun e(name: String, size: Long = 0, modified: Long = 0, dir: Boolean = false, missing: Boolean = false) =
            FileEntry(name, name, dir, FileMark.CLEAN, size = size, modified = modified, missing = missing)
        val entries = listOf(e("lib", modified = 5, dir = true), e("app", modified = 9, dir = true),
            e("a.txt", 30, 1), e("B.txt", 10, 3), e("c.txt", 20, 2), e("gone.txt", missing = true))
        fun order(sort: FileSort) = entries.sortedWith(sort.comparator()).map { it.name }
        assertEquals(listOf("app", "lib", "a.txt", "B.txt", "c.txt", "gone.txt"), order(FileSort()))
        assertEquals(listOf("lib", "app", "gone.txt", "c.txt", "B.txt", "a.txt"), order(FileSort(SortColumn.NAME, true)))
        // 目录没有大小，按名字；已删除的没有大小，总在最后
        assertEquals(listOf("app", "lib", "a.txt", "c.txt", "B.txt", "gone.txt"), order(FileSort(SortColumn.SIZE, true)))
        assertEquals(listOf("app", "lib", "B.txt", "c.txt", "a.txt", "gone.txt"), order(FileSort(SortColumn.SIZE, false)))
        assertEquals(listOf("app", "lib", "B.txt", "c.txt", "a.txt", "gone.txt"), order(FileSort(SortColumn.MODIFIED, true)))
        assertEquals(listOf("lib", "app", "a.txt", "c.txt", "B.txt", "gone.txt"), order(FileSort(SortColumn.MODIFIED, false)))
        // 点表头：同一列反过来，换一列时大小、时间从大到小
        assertEquals(FileSort(SortColumn.NAME, true), FileSort().clicked(SortColumn.NAME))
        assertEquals(FileSort(SortColumn.SIZE, true), FileSort().clicked(SortColumn.SIZE))
        assertEquals(FileSort(SortColumn.MODIFIED, false), FileSort(SortColumn.MODIFIED, true).clicked(SortColumn.MODIFIED))
        assertEquals(FileSort(SortColumn.NAME, false), FileSort(SortColumn.SIZE, true).clicked(SortColumn.NAME))
    }

    @Test
    fun `flattened and filtered trees follow the sort order`() {
        val cli = repo()
        write("src/big.kt", "x".repeat(500))
        val status = TreeStatus(cli.treeStatus())
        val listings = listOf("", "src").associateWith { listDirectory(root, it, status) }
        val bySize = FileSort(SortColumn.SIZE, true)
        assertEquals(listOf("src/", "  old›", "  big.kt", "  a.kt", "  b.kt", ".gitignore", "README.md"),
            flattenDirTree(listings, setOf("src"), true, bySize).map(::label))
        assertEquals(listOf("src/", "  old/", "    gone.kt", "  big.kt", "  a.kt", "  b.kt"),
            filterTree(scanWorkTree(root, status), ".kt", true, bySize).map(::label))
    }

    @Test
    fun `an open folder says when it is empty or only has hidden ignored files`() {
        val cli = repo()
        File(root, "empty").mkdirs()
        write("only/x.log")
        val status = TreeStatus(cli.treeStatus())
        val listings = listOf("", "empty", "only").associateWith { listDirectory(root, it, status) }
        val shown = flattenDirTree(listings, setOf("empty", "only"), showIgnored = true).map(::label)
        assertTrue("  (Empty folder)" in shown)
        assertFalse(shown.any { it.contains("Only ignored") })
        assertTrue("  (Only ignored files here)" in flattenDirTree(listings, setOf("only"), showIgnored = false).map(::label))
    }

    @Test
    fun `reads only folders whose parents are all open`() {
        assertEquals(listOf("", "a", "a/b"), directoriesToRead(setOf("a", "a/b", "c/d")))
        assertEquals(listOf("a/b", "a"), ancestorsOf("a/b/c"))
    }

    @Test
    fun `scan reads every folder but does not enter ignored or deleted ones`() {
        val cli = repo()
        write("build/deep/inner/x.txt"); write("src/old/deeper/y.kt")
        File(root, "src/old").renameTo(File(root, "src/moved")) // old 从磁盘消失，git 里还有记录
        val scan = scanWorkTree(root, TreeStatus(cli.treeStatus()))
        assertFalse(scan.truncated)
        assertTrue("src/moved/deeper" in scan.listings)
        assertTrue("build" !in scan.listings, "ignored folder is listed in its parent but never entered")
        assertTrue(scan.listings.getValue("").entries.any { it.name == "build" })
        assertTrue("src/old" !in scan.listings, "a folder that is only in git is not entered")
    }

    @Test
    fun `scan stops at the entry limit and says so`() {
        val cli = repo()
        repeat(4) { write("d$it/a"); write("d$it/b") }
        val scan = scanWorkTree(root, TreeStatus(cli.treeStatus()), maxEntries = 6)
        assertTrue(scan.truncated)
        assertTrue(scan.listings.size < 6)
        assertFalse(scanWorkTree(root, TreeStatus(cli.treeStatus())).truncated)
        var calls = 0
        val cancelled = scanWorkTree(root, TreeStatus(cli.treeStatus()), cancelled = { calls++ >= 2 })
        assertFalse(cancelled.listings.isEmpty()); assertTrue(cancelled.listings.size < scanWorkTree(root, TreeStatus(cli.treeStatus())).listings.size)
    }

    @Test
    fun `filter keeps matches and their parents, expands them and marks the hit`() {
        val cli = repo()
        write("src/deep/inner/Target.kt"); write("docs/readme.txt")
        val scan = scanWorkTree(root, TreeStatus(cli.treeStatus()))
        val rows = filterTree(scan, "target", showIgnored = true)
        assertEquals(listOf("src/", "  deep/", "    inner/", "      Target.kt"), rows.map(::label), "case-insensitive, all parents open, nothing else")
        val hit = (rows.last() as DirRow.Item).hit
        assertEquals(0 until 6, hit)
        // 目录名本身匹配：只带出它自己，里面名字不匹配的不出现
        assertEquals(listOf("src/", "  old›"), filterTree(scan, "old", true).map(::label))
        assertEquals(emptyList(), filterTree(scan, "zzz", true))
        assertEquals(emptyList(), filterTree(scan, "   ", true), "blank query is no filter")
    }

    @Test
    fun `filter hides ignored matches when ignored files are hidden`() {
        val cli = repo()
        write("build/target.bin"); write("notes/target.txt"); write("target.log")
        val scan = scanWorkTree(root, TreeStatus(cli.treeStatus()))
        assertEquals(listOf("notes/", "  target.txt", "target.log"), filterTree(scan, "target", showIgnored = true).map(::label).filter { !it.startsWith("build") && !it.contains("bin") })
        assertEquals(listOf("notes/", "  target.txt"), filterTree(scan, "target", showIgnored = false).map(::label))
        // 被忽略的目录不会被扫进去，里面的 target.bin 搜不到
        assertTrue(filterTree(scan, "target.bin", showIgnored = true).isEmpty())
    }

    @Test
    fun `gitignore entries are anchored and literal`() {
        val cli = repo()
        File(root, ".gitignore").writeText("build/") // 没有结尾换行
        write("src/[id].kt")
        write("src/other.kt")
        appendIgnoreRules(File(root, ".gitignore"), listOf("src/[id].kt"), isDir = false)
        appendIgnoreRules(File(root, ".gitignore"), listOf("src/[id].kt"), isDir = false) // 同一条不重复加
        assertEquals("build/\n/src/\\[id].kt\n", File(root, ".gitignore").readText())
        val src = listing(cli, "src").entries.associateBy { it.name }
        assertEquals(FileMark.IGNORED, src.getValue("[id].kt").mark)
        assertEquals(FileMark.UNTRACKED, src.getValue("other.kt").mark)
    }

    @Test
    fun `local ignore goes to info exclude and leaves gitignore alone`() {
        val cli = repo()
        write("notes/todo.md")
        write("scratch.txt")
        val before = File(root, ".gitignore").readText()
        assertTrue(cli.ignore(listOf("notes"), directory = true, target = IgnoreTarget.EXCLUDE).ok)
        assertTrue(cli.ignore(listOf("scratch.txt"), directory = false, target = IgnoreTarget.EXCLUDE).ok)
        assertEquals(before, File(root, ".gitignore").readText())
        assertTrue(File(root, ".git/info/exclude").readText().endsWith("/notes/\n/scratch.txt\n"))
        val top = listing(cli, "").entries.associateBy { it.name }
        assertEquals(FileMark.IGNORED, top.getValue("notes").mark)
        assertEquals(FileMark.IGNORED, top.getValue("scratch.txt").mark)
    }

    @Test
    fun `exclude is written to the shared git dir from a linked worktree`() {
        val cli = repo()
        val linked = File(root.parentFile, root.name + "-linked")
        try {
            git("worktree", "add", "-q", "-b", "side", linked.path)
            File(linked, "tmp.txt").writeText("x")
            assertTrue(GitCli(linked).ignore(listOf("tmp.txt"), directory = false, target = IgnoreTarget.EXCLUDE).ok)
            assertTrue(File(root, ".git/info/exclude").readText().contains("/tmp.txt\n"))
            assertTrue(cli.ignoreFile(IgnoreTarget.EXCLUDE).canonicalFile == File(root, ".git/info/exclude").canonicalFile)
        } finally {
            linked.deleteRecursively()
        }
    }

    @Test
    fun `ignoring a folder keeps tracked files unless asked to stop tracking`() {
        val cli = repo()
        write("src/new.kt")
        assertEquals(3, cli.trackedCount("src"))
        assertTrue(cli.ignore(listOf("src"), directory = true).ok)
        assertTrue(File(root, ".gitignore").readText().endsWith("/src/\n"))
        // 规则管不到已跟踪的：a.kt 还在索引里，新文件被忽略
        assertEquals(3, cli.trackedCount("src"))
        assertEquals(FileMark.IGNORED, listing(cli, "src").entries.single { it.name == "new.kt" }.mark)

        assertTrue(cli.ignore(listOf("src"), directory = true, stopTracking = true).ok)
        assertEquals(0, cli.trackedCount("src"))
        assertTrue(File(root, "src/a.kt").isFile) // 本地文件留着
        assertEquals("/src/\n", File(root, ".gitignore").readText().lines().last { it.isNotEmpty() }.let { "$it\n" })
        assertTrue(git("status", "--porcelain").lines().any { it.startsWith("D  src/a.kt") })
    }

    @Test
    fun `sizes read like a file manager`() {
        assertEquals("812 B", formatSize(812))
        assertEquals("4.2 KB", formatSize(4300))
        assertEquals("1.5 MB", formatSize(1_572_864))
    }
}
