package gitviz.ui.history

import gitviz.*
import gitviz.git.*
import gitviz.ui.*
import kotlin.test.*

class VisualInfoTest {
    private fun commit(id: String, message: String, parents: List<String> = listOf("p"), stash: Boolean = false) = CommitInfo(
        id = id, parents = parents, shortMessage = message.lineSequence().first(), fullMessage = message, author = "A", email = "a@x",
        timeSeconds = 0, refs = if (stash) listOf(RefLabel("stash@{0}", RefKind.STASH)) else emptyList(),
    )

    private fun <T> zh(block: () -> T): T {
        val language = Settings.language
        Settings.language = Language.ZH
        try { return block() } finally { Settings.language = language }
    }

    @Test fun `marks show merges and multi line messages but not stashes or blank trailing lines`() = zh {
        assertEquals(emptyList(), commitMarks(commit("1", "Fix bug")))
        assertEquals(emptyList(), commitMarks(commit("2", "Fix bug\n\n")), "trailing blank lines are not a body")
        assertEquals(listOf("有正文"), commitMarks(commit("3", "Fix bug\n\nBecause of x")))
        assertEquals(listOf("合并"), commitMarks(commit("4", "Merge branch 'x'", parents = listOf("a", "b"))))
        assertEquals(listOf("合并", "有正文"), commitMarks(commit("5", "Merge x\n\nconflicts resolved", parents = listOf("a", "b"))))
        assertEquals(emptyList(), commitMarks(commit("6", "WIP\n\nbody", stash = true)))
    }

    @Test fun `sync label omits zero sides`() {
        assertEquals("↑2 ↓3", syncLabel(2, 3)); assertEquals("↑1", syncLabel(1, 0)); assertEquals("↓4", syncLabel(0, 4)); assertEquals("", syncLabel(0, 0))
    }

    @Test fun `branch detail shows counts, then age only for stale branches`() = zh {
        val now = 100L * 86_400
        fun sync(ahead: Int?, behind: Int?, daysAgo: Long) = GitRepo.BranchSync(ahead, behind, now - daysAgo * 86_400)
        assertEquals(BranchDetail("↑1 ↓2", stale = false, mono = true), branchDetail(sync(1, 2, 3), now))
        assertEquals(BranchDetail("", stale = false, mono = false), branchDetail(sync(0, 0, 29), now), "29 days is not stale")
        assertEquals(BranchDetail("34 天前", stale = true, mono = false), branchDetail(sync(null, null, 34), now), "no upstream: no counts")
        assertEquals(BranchDetail("↑1 · 40 天前", stale = true, mono = false), branchDetail(sync(1, 0, 40), now))
        assertEquals(BranchDetail("", stale = false, mono = false), branchDetail(GitRepo.BranchSync(null, null, 0), now), "unknown time is never stale")
    }

    @Test fun `default selection prefers HEAD, then the first real commit, then a stash`() {
        val stash = commit("s", "On main: wip", stash = true); val head = commit("h", "Head"); val other = commit("o", "Other")
        assertEquals(head, defaultSelection(listOf(stash, other, head), "h"))
        assertEquals(other, defaultSelection(listOf(stash, other), "missing"), "HEAD not loaded: skip the stash")
        assertEquals(other, defaultSelection(listOf(stash, other), null))
        assertEquals(stash, defaultSelection(listOf(stash), null))
        assertNull(defaultSelection(emptyList(), "h"))
    }

    @Test fun `branch sync reads ahead and behind from the upstream and leaves unconfigured branches empty`() = RepositoryFixture().use { r ->
        r.commit("a", "base\n")
        r.git("remote", "add", "origin", r.root.resolveSibling(r.root.name + "-origin").path)
        r.git("update-ref", "refs/remotes/origin/main", "HEAD")
        r.git("config", "branch.main.remote", "origin"); r.git("config", "branch.main.merge", "refs/heads/main")
        r.commit("b", "local 1\n"); r.commit("c", "local 2\n")
        val behindTip = r.git("rev-parse", "HEAD~2")
        r.git("switch", "-q", "-c", "side", behindTip); r.commit("d", "side\n")
        r.git("config", "branch.side.remote", "origin"); r.git("config", "branch.side.merge", "refs/heads/main")
        r.git("update-ref", "refs/remotes/origin/main", r.git("rev-parse", "main"))
        r.git("switch", "-q", "-c", "loose", "main")
        GitRepo.open(r.root).use { repo ->
            val sync = repo.branchSync()
            assertEquals(setOf("main", "side", "loose"), sync.keys)
            assertEquals(0, sync.getValue("main").ahead); assertEquals(0, sync.getValue("main").behind)
            assertEquals(1, sync.getValue("side").ahead); assertEquals(2, sync.getValue("side").behind)
            assertNull(sync.getValue("loose").ahead); assertNull(sync.getValue("loose").behind)
            assertTrue(sync.values.all { it.lastCommitSeconds > 0 })
        }
    }
}
