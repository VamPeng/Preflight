package gitviz.logic

import gitviz.git.*
import gitviz.ui.*
import gitviz.ui.changes.discardMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 改动列表：平铺、按文件夹分层、合并中间层、收起，以及勾选状态。 */
class ChangeTreeTest {
    init { Settings.language = Language.EN }

    private fun file(path: String, worktree: Char = 'M') = WorkingFile(path, null, ' ', worktree)

    private fun describe(rows: List<ChangeRow>) = rows.map { row ->
        when (row) {
            is ChangeRow.Folder -> "${row.depth}:${row.label}" + if (row.collapsed) "*" else ""
            is ChangeRow.File -> "${row.depth}:${row.file.path.substringAfterLast('/')}"
        }
    }

    @Test
    fun `flat keeps the original order and shows one row per file`() {
        val files = listOf(file("src/b.kt"), file("README.md"), file("src/a.kt"))
        assertEquals(listOf("0:b.kt", "0:README.md", "0:a.kt"), describe(flattenChanges(files, tree = false)))
    }

    @Test
    fun `tree lists folders before files and puts root files last`() {
        val files = listOf(file("README.md"), file("docs/notes.md"), file("src/Main.kt"), file("src/ui/View.kt"), file("src/Util.kt"))
        assertEquals(
            listOf("0:docs/", "1:notes.md", "0:src/", "1:ui/", "2:View.kt", "1:Main.kt", "1:Util.kt", "0:README.md"),
            describe(flattenChanges(files, tree = true)),
        )
    }

    @Test
    fun `a folder with one subfolder and no files of its own merges into one row`() {
        val files = listOf(
            file("src/main/kotlin/gitviz/ui/A.kt"), file("src/main/kotlin/gitviz/git/B.kt"),
            file("src/test/kotlin/gitviz/logic/C.kt"),
        )
        assertEquals(
            listOf("0:src/", "1:main/kotlin/gitviz/", "2:git/", "3:B.kt", "2:ui/", "3:A.kt", "1:test/kotlin/gitviz/logic/", "2:C.kt"),
            describe(flattenChanges(files, tree = true)),
        )
        // 只有一条路的时候整条合成一行
        assertEquals(listOf("0:src/main/kotlin/", "1:A.kt"), describe(flattenChanges(listOf(file("src/main/kotlin/A.kt")), tree = true)))
        // 中间层自己有文件就不合并
        assertEquals(listOf("0:src/", "1:deep/", "2:A.kt", "1:Own.kt"), describe(flattenChanges(listOf(file("src/Own.kt"), file("src/deep/A.kt")), tree = true)))
    }

    @Test
    fun `a folder row knows every file below it`() {
        val rows = flattenChanges(listOf(file("a/b/1.kt"), file("a/b/c/2.kt"), file("a/3.kt"), file("z/4.kt")), tree = true)
        val folders = rows.filterIsInstance<ChangeRow.Folder>().associateBy { it.path }
        assertEquals(setOf("a/b/1.kt", "a/b/c/2.kt", "a/3.kt"), folders.getValue("a/").paths.toSet())
        assertEquals(setOf("a/b/1.kt", "a/b/c/2.kt"), folders.getValue("a/b/").paths.toSet())
        assertEquals(listOf("z/4.kt"), folders.getValue("z/").paths)
    }

    @Test
    fun `collapsing a folder hides its rows but keeps the row itself`() {
        val files = listOf(file("a/b/1.kt"), file("a/b/c/2.kt"), file("a/3.kt"), file("z/4.kt"))
        assertEquals(listOf("0:a/*", "0:z/", "1:4.kt"), describe(flattenChanges(files, tree = true, collapsed = setOf("a/"))))
        assertEquals(setOf("a/", "a/b/", "a/b/c/", "z/"), allFolderPaths(files))
    }

    @Test
    fun `renamed files sit under their new folder and root files have no folder`() {
        val moved = WorkingFile("new/dir/File.kt", "old/File.kt", 'R', ' ')
        assertEquals(listOf("0:new/dir/", "1:File.kt", "0:Root.txt"), describe(flattenChanges(listOf(file("Root.txt"), moved), tree = true)))
    }

    @Test
    fun `row keys are unique within a group`() {
        val rows = flattenChanges(listOf(file("a/x.kt"), file("a/b/x.kt"), file("b/x.kt"), file("x.kt")), tree = true)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun `check state is none, some or all`() {
        val paths = listOf("a", "b", "c")
        assertEquals(CheckState.NONE, checkState(paths, emptySet()))
        assertEquals(CheckState.NONE, checkState(paths, setOf("z")))
        assertEquals(CheckState.SOME, checkState(paths, setOf("a", "z")))
        assertEquals(CheckState.ALL, checkState(paths, setOf("a", "b", "c", "z")))
        assertEquals(CheckState.NONE, checkState(emptyList(), setOf("a")))
    }

    @Test
    fun `discard message says how many files and how many new files are deleted`() {
        val tracked = file("a.kt")
        val fresh = WorkingFile("n.kt", null, '?', '?')
        assertEquals("Unstaged changes to “a.kt” will be permanently discarded.", discardMessage(listOf(tracked)))
        assertEquals("“n.kt” is a new file. It will be permanently deleted.", discardMessage(listOf(fresh)))
        assertEquals("Unstaged changes to 2 files will be permanently discarded.", discardMessage(listOf(tracked, file("b.kt"))))
        val mixed = discardMessage(listOf(tracked, fresh, WorkingFile("m.kt", null, '?', '?')))
        assertTrue("3 files" in mixed && "2 new files will be permanently deleted" in mixed, mixed)
        assertFalse("new file" in discardMessage(listOf(tracked, file("b.kt"))))
    }
}
