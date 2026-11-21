package gitviz.git

import gitviz.*
import gitviz.ui.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 用本地裸仓库当 origin，推送不会碰到任何真实的远程。 */
class GitCliTest {
    init { Settings.language = Language.EN }


    private val root: File = createTempDirectory("gitviz-cli").toFile()
    private val origin = File(root, "origin.git")
    private val work = File(root, "work")
    private val other = File(root, "other")

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    private fun setUp(dir: File) {
        git(dir, "config", "user.name", "t")
        git(dir, "config", "user.email", "t@t")
        git(dir, "config", "commit.gpgsign", "false")
    }

    private fun commit(dir: File, file: String, content: String) {
        File(dir, file).writeText(content)
        git(dir, "add", file)
        git(dir, "commit", "-q", "-m", "edit $file")
    }

    private fun cloneTo(dir: File) {
        git(root, "clone", "-q", origin.path, dir.path)
        setUp(dir)
    }

    private fun prepare() {
        git(root, "init", "-q", "--bare", "--initial-branch=main", origin.path)
        git(root, "init", "-q", "--initial-branch=main", work.path)
        setUp(work)
        git(work, "remote", "add", "origin", origin.path)
        commit(work, "a", "one\n")
        git(work, "push", "-q", "-u", "origin", "main")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `push sends new commits to origin`() {
        prepare()
        commit(work, "a", "two\n")

        assertTrue(GitCli(work).push().ok)
        assertEquals(git(work, "rev-parse", "HEAD"), git(origin, "rev-parse", "main"))
    }

    @Test
    fun `pushBranch pushes another branch without switching and sets a missing upstream`() {
        prepare()
        git(work, "checkout", "-q", "-b", "feature")
        commit(work, "b", "x\n")
        git(work, "checkout", "-q", "main")
        File(work, "a").writeText("uncommitted\n")
        val cli = GitCli(work)

        assertTrue(cli.pushBranch("feature").ok)
        assertEquals(git(work, "rev-parse", "feature"), git(origin, "rev-parse", "feature"))
        assertEquals("origin/feature", git(work, "rev-parse", "--abbrev-ref", "feature@{upstream}"))
        assertEquals("main", git(work, "rev-parse", "--abbrev-ref", "HEAD"), "current branch is untouched")
        assertEquals("uncommitted\n", File(work, "a").readText(), "working changes are untouched")

        // 有上游后再提交，再推一次：推到上游，main 不受牵连
        git(work, "checkout", "-q", "feature")
        commit(work, "b", "y\n")
        git(work, "checkout", "-q", "main")
        val mainBefore = git(origin, "rev-parse", "main")
        assertTrue(cli.pushBranch("feature").ok)
        assertEquals(git(work, "rev-parse", "feature"), git(origin, "rev-parse", "feature"))
        assertEquals(mainBefore, git(origin, "rev-parse", "main"))
    }

    @Test
    fun `pushBranch pushes to the configured upstream name and refuses missing branch or remote`() {
        prepare()
        git(work, "branch", "local-name")
        git(work, "config", "branch.local-name.remote", "origin"); git(work, "config", "branch.local-name.merge", "refs/heads/remote-name")
        val cli = GitCli(work)
        assertTrue(cli.pushBranch("local-name").ok)
        assertEquals(git(work, "rev-parse", "local-name"), git(origin, "rev-parse", "remote-name"))
        assertFalse(cli.pushBranch("nope").ok)
        git(work, "remote", "rename", "origin", "elsewhere"); git(work, "branch", "fresh")
        val noOrigin = cli.pushBranch("fresh")
        assertFalse(noOrigin.ok); assertTrue("origin" in noOrigin.output)
    }

    @Test
    fun `pushBranch is rejected when behind and force-with-lease replaces it`() {
        prepare()
        git(work, "checkout", "-q", "-b", "feature"); commit(work, "b", "1\n")
        val cli = GitCli(work)
        assertTrue(cli.pushBranch("feature").ok)
        cloneTo(other); git(other, "checkout", "-q", "feature"); commit(other, "b", "theirs\n"); git(other, "push", "-q")
        git(work, "fetch", "-q"); git(work, "reset", "-q", "--hard", "HEAD~1"); commit(work, "b", "mine\n")
        git(work, "checkout", "-q", "main")
        assertEquals(GitFailure.REJECTED, cli.pushBranch("feature").failure)
        assertTrue(cli.pushBranch(assertNotNull(cli.prepareBranchForcePush("feature"))).ok)
        assertEquals(git(work, "rev-parse", "feature"), git(origin, "rev-parse", "feature"))
        assertEquals("main", git(work, "rev-parse", "--abbrev-ref", "HEAD"))
    }

    @Test
    fun `pushing the current branch by name ignores matching branches and follow-tags`() {
        prepare()
        git(work, "branch", "side")
        git(work, "push", "-q", "-u", "origin", "side")
        git(work, "switch", "-q", "side")
        commit(work, "side.txt", "local side\n")
        git(work, "switch", "-q", "main")
        commit(work, "a", "local main\n")
        git(work, "tag", "-a", "local-tag", "-m", "local tag")
        git(work, "config", "push.default", "matching")
        git(work, "config", "push.followTags", "true")

        val cli = GitCli(work)
        val sideBefore = git(origin, "rev-parse", "side")
        assertTrue(cli.pushBranch("main").ok)
        assertEquals(git(work, "rev-parse", "main"), git(origin, "rev-parse", "main"))
        assertEquals(sideBefore, git(origin, "rev-parse", "side"))
        assertEquals("", git(origin, "tag", "--list"))

        cloneTo(other)
        commit(other, "a", "remote main\n")
        git(other, "push", "-q", "origin", "main")
        git(work, "fetch", "-q", "origin")
        commit(work, "a", "rewritten main\n")
        val plan = assertNotNull(cli.prepareBranchForcePush("main"))
        assertTrue(cli.pushBranch(plan).ok)
        assertEquals(git(work, "rev-parse", "main"), git(origin, "rev-parse", "main"))
        assertEquals(sideBefore, git(origin, "rev-parse", "side"))
        assertEquals("", git(origin, "tag", "--list"))
    }

    @Test
    fun `force push keeps the confirmed lease after fetching a newer upstream`() {
        prepare()
        git(work, "config", "remote.origin.fetch", "+refs/heads/main:refs/remotes/origin/mapped-main")
        val cli = GitCli(work)
        assertNull(cli.prepareBranchForcePush("main"))
        git(work, "fetch", "-q", "origin")
        val plan = assertNotNull(cli.prepareBranchForcePush("main"))
        assertEquals("origin/main", plan.destination)
        assertEquals(git(work, "rev-parse", "refs/remotes/origin/mapped-main"), plan.expectedRemote)

        cloneTo(other)
        commit(other, "a", "remote main\n")
        git(other, "push", "-q", "origin", "main")
        git(work, "fetch", "-q", "origin")
        commit(work, "a", "local main\n")
        val remoteTip = git(other, "rev-parse", "main")

        assertEquals(GitFailure.REJECTED, cli.pushBranch(plan).failure)
        assertEquals(remoteTip, git(origin, "rev-parse", "main"))
        val refreshedPlan = assertNotNull(cli.prepareBranchForcePush("main"))
        assertEquals(remoteTip, refreshedPlan.expectedRemote)
        assertTrue(cli.pushBranch(refreshedPlan).ok)
    }

    @Test
    fun `push sets the upstream of a new branch`() {
        prepare()
        git(work, "checkout", "-q", "-b", "feature")
        commit(work, "b", "x\n")

        assertTrue(GitCli(work).push().ok)
        assertEquals("origin/feature", git(work, "rev-parse", "--abbrev-ref", "@{upstream}"))
    }

    @Test
    fun `push is rejected when origin has new commits and pull fixes it`() {
        prepare()
        cloneTo(other)
        commit(other, "b", "theirs\n")
        git(other, "push", "-q")
        commit(work, "c", "ours\n")

        val cli = GitCli(work)
        val rejected = cli.push()
        assertEquals(GitFailure.REJECTED, rejected.failure)

        assertTrue(cli.pull(checkNotNull(cli.savedPull())).ok)
        assertTrue(cli.push().ok)
        assertEquals(git(work, "rev-parse", "HEAD"), git(origin, "rev-parse", "main"))
    }

    @Test
    fun `pull reports a conflict`() {
        prepare()
        cloneTo(other)
        commit(other, "a", "theirs\n")
        git(other, "push", "-q")
        commit(work, "a", "ours\n")

        assertEquals(GitFailure.CONFLICT, GitCli(work).let { it.pull(checkNotNull(it.savedPull())) }.failure)
    }

    @Test
    fun `force push replaces rewritten history but refuses when origin moved since the last fetch`() {
        prepare()
        commit(work, "b", "mine\n")
        val cli = GitCli(work)
        assertTrue(cli.push().ok)
        git(work, "commit", "-q", "--amend", "-m", "rewritten")
        assertEquals(GitFailure.REJECTED, cli.push().failure)
        val forced = cli.push(force = true)
        assertTrue(forced.ok, forced.output)
        assertEquals(git(work, "rev-parse", "HEAD"), git(origin, "rev-parse", "main"))

        cloneTo(other)
        commit(other, "c", "theirs\n")
        git(other, "push", "-q")
        val theirs = git(origin, "rev-parse", "main")
        git(work, "commit", "-q", "--amend", "-m", "rewritten again")
        assertFalse(cli.push(force = true).ok)
        assertEquals(theirs, git(origin, "rev-parse", "main"))
    }

    @Test
    fun `remote branch is checked out as a tracking local branch and reused afterwards`() {
        prepare()
        cloneTo(other)
        git(other, "switch", "-q", "-c", "feature/x")
        commit(other, "x", "x\n")
        git(other, "push", "-q", "-u", "origin", "feature/x")
        val cli = GitCli(work)
        assertTrue(cli.fetch().ok)
        val first = cli.checkoutRemote("origin/feature/x")
        assertTrue(first.ok, first.output)
        assertEquals("feature/x", git(work, "branch", "--show-current"))
        assertEquals("origin/feature/x", git(work, "rev-parse", "--abbrev-ref", "@{upstream}"))
        git(work, "switch", "-q", "main")
        assertTrue(cli.checkoutRemote("origin/feature/x").ok)
        assertEquals("feature/x", git(work, "branch", "--show-current"))
        assertFalse(cli.checkoutRemote("nowhere/feature/x").ok)
    }

    @Test
    fun `batch stage and unstage cover untracked files, deletions and an unborn repository`() {
        prepare()
        commit(work, "b", "b\n")
        File(work, "a").writeText("changed\n"); File(work, "b").delete(); File(work, "dir").mkdirs(); File(work, "dir/[new].txt").writeText("new\n")
        val cli = GitCli(work)
        assertTrue(cli.stage(cli.status()).ok)
        assertTrue(cli.status().all { it.hasStaged && !it.hasUnstaged })
        assertEquals(3, cli.status().size)
        assertTrue(cli.unstage(cli.status()).ok)
        assertTrue(cli.status().none { it.hasStaged })
        assertEquals("changed\n", File(work, "a").readText())

        val fresh = File(root, "fresh").also { it.mkdirs() }
        git(fresh, "init", "-q")
        File(fresh, "one").writeText("1\n")
        File(fresh, "two").writeText("2\n")
        val unborn = GitCli(fresh)
        assertTrue(unborn.stage(unborn.status()).ok)
        assertTrue(unborn.status().all { it.hasStaged })
        assertTrue(unborn.unstage(unborn.status()).ok)
        assertTrue(unborn.status().all { it.untracked })
    }

    @Test
    fun `batch only touches the chosen files and an empty batch does nothing`() {
        prepare()
        commit(work, "keep", "base\n")
        File(work, "a").writeText("changed\n"); File(work, "keep").writeText("changed\n"); File(work, "new.txt").writeText("n\n")
        val cli = GitCli(work)

        assertTrue(cli.stage(emptyList()).ok)
        assertTrue(cli.status().none { it.hasStaged })
        assertTrue(cli.stage(listOf(file("a"), file("new.txt"))).ok)
        assertEquals(setOf("a", "new.txt"), cli.status().filter { it.hasStaged }.map { it.path }.toSet())
        assertTrue(cli.unstage(listOf(file("a"))).ok)
        assertEquals(setOf("new.txt"), cli.status().filter { it.hasStaged }.map { it.path }.toSet())
    }

    @Test
    fun `batch discard restores changed files and deletes new ones in one go`() {
        prepare()
        commit(work, "keep", "base\n")
        commit(work, "gone", "base\n")
        File(work, "a").writeText("changed\n"); File(work, "gone").delete()
        File(work, "keep").writeText("untouched change\n")
        File(work, "x1.txt").writeText("n\n"); File(work, "dir").mkdirs(); File(work, "dir/x2.txt").writeText("n\n")
        val cli = GitCli(work)

        assertTrue(cli.discard(listOf(file("a"), file("gone"), file("x1.txt"), file("dir/x2.txt"))).ok)
        assertEquals("one\n", File(work, "a").readText())
        assertEquals("base\n", File(work, "gone").readText())
        assertFalse(File(work, "x1.txt").exists())
        assertFalse(File(work, "dir/x2.txt").exists())
        assertEquals("untouched change\n", File(work, "keep").readText())
    }

    @Test
    fun `batch commands read glob characters literally and handle many files`() {
        prepare()
        commit(work, "x", "base\n")
        File(work, "[x]").writeText("mine\n")
        File(work, "x").writeText("do not touch\n")
        File(work, "sp ace é.txt").writeText("s\n")
        // 400 个新文件，比一批 git clean 能带的路径多，要分批
        File(work, "many").mkdirs()
        repeat(400) { File(work, "many/file-with-a-fairly-long-name-$it.txt").writeText("$it\n") }
        val cli = GitCli(work)

        assertTrue(cli.stage(listOf(file("[x]"), file("sp ace é.txt"))).ok)
        assertEquals(setOf("[x]", "sp ace é.txt"), cli.status().filter { it.hasStaged }.map { it.path }.toSet())
        assertTrue(cli.unstage(listOf(file("[x]"), file("sp ace é.txt"))).ok)

        val batch = cli.status().filter { it.path == "[x]" || it.path.startsWith("many/") }
        assertEquals(401, batch.size)
        assertTrue(cli.discard(batch).ok)
        assertFalse(File(work, "[x]").exists())
        assertEquals(0, File(work, "many").listFiles().orEmpty().size)
        assertEquals("do not touch\n", File(work, "x").readText())
        assertTrue(File(work, "sp ace é.txt").exists())
    }

    @Test
    fun `a failing batch reports the failure and leaves the later files alone`() {
        prepare()
        File(work, "new.txt").writeText("n\n")
        val cli = GitCli(work)
        val missing = WorkingFile("no-such-file", null, ' ', 'M')

        assertFalse(cli.discard(listOf(missing, file("new.txt"))).ok)
        assertTrue(File(work, "new.txt").exists())
    }

    @Test
    fun `interrupting the thread stops a running git process promptly`() {
        prepare()
        var result: GitExecution? = null
        val started = System.nanoTime()
        val runner = Thread { result = GitProcess.execute(work, listOf("-c", "alias.wait=!sleep 30", "wait")) }.apply { start() }
        Thread.sleep(500)
        runner.interrupt()
        runner.join(10_000)
        assertFalse(runner.isAlive)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000)
        assertTrue(checkNotNull(result).uncertain)
        assertFalse(GitProcess.hasUnfinishedProcess())
    }

    @Test
    fun `detached head cannot push`() {
        prepare()
        git(work, "checkout", "-q", "--detach")

        val result = GitCli(work).push()
        assertFalse(result.ok)
        assertEquals(GitFailure.OTHER, result.failure)
    }

    private fun file(name: String) = GitCli(work).status().single { it.path == name }

    @Test
    fun `status lists staged, unstaged and untracked files`() {
        prepare()
        File(work, "b").writeText("x\n")
        git(work, "add", "b")
        File(work, "a").writeText("changed\n")
        File(work, "new file.txt").writeText("y\n")

        val cli = GitCli(work).status().associateBy { it.path }
        assertTrue(cli.getValue("b").hasStaged)
        assertFalse(cli.getValue("b").hasUnstaged)
        assertTrue(cli.getValue("a").hasUnstaged)
        assertTrue(cli.getValue("new file.txt").untracked)
        assertFalse(cli.getValue("new file.txt").hasStaged)
    }

    @Test
    fun `status keeps the old path of a rename`() {
        val files = parseStatus("R  new name.txt\u0000old name.txt\u0000 M a\u0000?? b\u0000")
        assertEquals(listOf("new name.txt", "a", "b"), files.map { it.path })
        assertEquals("old name.txt", files[0].oldPath)
    }

    @Test
    fun `stage and unstage move a file between the two lists`() {
        prepare()
        File(work, "a").writeText("changed\n")
        val cli = GitCli(work)

        assertTrue(cli.stage(file("a")).ok)
        assertTrue(file("a").hasStaged)
        assertFalse(file("a").hasUnstaged)

        assertTrue(cli.unstage(file("a")).ok)
        assertFalse(file("a").hasStaged)
        assertTrue(file("a").hasUnstaged)
    }

    @Test
    fun `unstage works before the first commit`() {
        git(root, "init", "-q", "--initial-branch=main", work.path)
        setUp(work)
        File(work, "a").writeText("x\n")
        git(work, "add", "a")

        assertTrue(GitCli(work).unstage(file("a")).ok)
        assertTrue(file("a").untracked)
    }

    @Test
    fun `discard restores only the chosen file`() {
        prepare()
        commit(work, "keep", "base\n")
        File(work, "a").writeText("changed\n")
        File(work, "keep").writeText("also changed\n")

        assertTrue(GitCli(work).discard(file("a")).ok)
        assertEquals("one\n", File(work, "a").readText())
        assertEquals("also changed\n", File(work, "keep").readText())
    }

    @Test
    fun `discard keeps what is already staged`() {
        prepare()
        File(work, "a").writeText("staged\n")
        git(work, "add", "a")
        File(work, "a").writeText("unstaged\n")

        assertTrue(GitCli(work).discard(file("a")).ok)
        assertEquals("staged\n", File(work, "a").readText())
    }

    @Test
    fun `discard removes an untracked file and nothing else`() {
        prepare()
        File(work, "scratch.txt").writeText("x\n")
        File(work, "other.txt").writeText("y\n")

        assertTrue(GitCli(work).discard(file("scratch.txt")).ok)
        assertFalse(File(work, "scratch.txt").exists())
        assertTrue(File(work, "other.txt").exists())
    }

    @Test
    fun `glob characters in a file name are taken literally`() {
        prepare()
        commit(work, "x", "base\n")
        File(work, "[x]").writeText("mine\n")
        File(work, "x").writeText("do not touch\n")

        assertTrue(GitCli(work).discard(file("[x]")).ok)
        assertFalse(File(work, "[x]").exists())
        assertEquals("do not touch\n", File(work, "x").readText())
    }

    @Test
    fun `diff shows staged, unstaged and untracked changes`() {
        prepare()
        File(work, "a").writeText("staged\n")
        git(work, "add", "a")
        File(work, "a").writeText("unstaged\n")
        File(work, "new").writeText("fresh\n")
        val cli = GitCli(work)

        assertTrue("+staged" in cli.diff(file("a"), staged = true))
        assertTrue("+unstaged" in cli.diff(file("a"), staged = false))
        assertTrue("+fresh" in cli.diff(file("new"), staged = false))
    }

    @Test
    fun `commit and amend change the history`() {
        prepare()
        File(work, "a").writeText("two\n")
        val cli = GitCli(work)
        cli.stage(file("a"))

        assertTrue(cli.commit("second", amend = false).ok)
        assertEquals("second", cli.lastCommitMessage())
        val count = git(work, "rev-list", "--count", "HEAD")

        assertTrue(cli.commit("second, reworded", amend = true).ok)
        assertEquals("second, reworded", cli.lastCommitMessage())
        assertEquals(count, git(work, "rev-list", "--count", "HEAD"))
    }

    @Test
    fun `commit only takes the chosen files from disk and leaves other staged changes staged`() {
        prepare()
        commit(work, "b", "b\n")
        commit(work, "[id].tsx", "x\n")
        File(work, "a").writeText("a-staged\n")
        val cli = GitCli(work)
        cli.stage(file("a"))
        File(work, "b").delete()
        File(work, "[id].tsx").writeText("x2\n")
        File(work, "new").writeText("fresh\n")

        val picked = cli.status().filter { it.path in setOf("b", "[id].tsx", "new") }
        assertTrue(cli.commitOnly("pick", picked).ok)

        assertEquals("pick", cli.lastCommitMessage())
        assertEquals(setOf("D\tb", "M\t[id].tsx", "A\tnew"), git(work, "show", "--name-status", "--format=", "HEAD").lines().toSet())
        assertEquals(listOf("a"), cli.status().map { it.path }, "Only the staged change to a is left")
        assertTrue(file("a").hasStaged)
    }

    @Test
    fun `commit only includes both paths of a renamed and modified file`() {
        prepare()
        commit(work, "[old].txt", "one\ntwo\nthree\n")
        git(work, "mv", "[old].txt", "[new].txt")
        File(work, "[new].txt").appendText("four\n")
        File(work, "a").writeText("staged elsewhere\n")
        git(work, "add", "a")
        val cli = GitCli(work)
        val renamed = file("[new].txt")
        assertEquals("[old].txt", renamed.oldPath)
        assertTrue(renamed.hasUnstaged)

        val result = cli.commitOnly("rename and edit", listOf(renamed))

        assertTrue(result.ok, result.output)
        assertEquals(setOf("a", "[new].txt"), git(work, "ls-tree", "--name-only", "HEAD").lines().toSet())
        assertEquals("one\ntwo\nthree\nfour", git(work, "show", "HEAD:[new].txt"))
        assertEquals("one", git(work, "show", "HEAD:a"))
        assertEquals(listOf("a"), cli.status().map { it.path })
        assertTrue(file("a").hasStaged)
    }

    @Test
    fun `partial new-file registration is rolled back when add fails`() {
        prepare()
        val cli = GitCli(work)
        for (name in listOf("[fresh]", "blocked", "already-staged")) File(work, name).writeText("$name\n")
        val picked = cli.status()
        val headBefore = head()
        // 状态显示后，外部操作暂存了一个文件，并把另一个加入忽略规则。
        git(work, "add", "already-staged")
        File(work, ".gitignore").writeText("blocked\n")

        val result = cli.commitOnly("must fail", picked)

        assertFalse(result.ok)
        assertTrue("ignored" in result.output, result.output)
        assertEquals(headBefore, head())
        assertTrue(file("[fresh]").untracked, "Successful add -N entries must be removed on partial failure")
        assertTrue(file("already-staged").hasStaged, "An existing index entry must not be removed")
        assertEquals("already-staged", git(work, "show", ":already-staged"))
        for (name in listOf("[fresh]", "blocked", "already-staged")) assertEquals("$name\n", File(work, name).readText())
    }

    @Test
    fun `a failed commit only leaves new files untracked`() {
        prepare()
        File(work, "new").writeText("fresh\n")
        File(work, ".git/hooks/pre-commit").apply {
            writeText("#!/bin/sh\nexit 1\n")
            setExecutable(true)
        }
        val cli = GitCli(work)

        assertFalse(cli.commitOnly("nope", listOf(file("new"))).ok)
        assertTrue(file("new").untracked)
    }

    @Test
    fun `a failing commit hook is reported`() {
        prepare()
        File(work, "a").writeText("two\n")
        GitCli(work).stage(file("a"))
        File(work, ".git/hooks/pre-commit").apply {
            writeText("#!/bin/sh\necho hook said no\nexit 1\n")
            setExecutable(true)
        }

        val result = GitCli(work).commit("nope", amend = false)
        assertFalse(result.ok)
        assertTrue("hook said no" in result.output)
    }

    private fun head() = git(work, "rev-parse", "HEAD")
    private fun branch() = git(work, "symbolic-ref", "--short", "HEAD")
    private fun commitInfo(id: String, parents: Int = 1) =
        CommitInfo(id, List(parents) { "p$it" }, "m", "m", "t", "t@t", 0, emptyList())

    @Test
    fun `branch can be created, switched to and renamed`() {
        prepare()
        val cli = GitCli(work)

        assertTrue(cli.createBranch("feature", head()).ok)
        assertEquals("main", branch())
        assertTrue(cli.switchTo("feature").ok)
        assertEquals("feature", branch())

        assertTrue(cli.renameBranch("feature", "renamed").ok)
        assertEquals("renamed", branch())
    }

    @Test
    fun `invalid branch names are refused by git`() {
        prepare()
        assertFalse(GitCli(work).createBranch("bad name", head()).ok)
    }

    @Test
    fun `merged branch can be deleted but unmerged work is protected`() {
        prepare()
        val cli = GitCli(work)
        cli.createBranch("done", head())
        assertTrue(cli.deleteBranch("done").ok)

        git(work, "checkout", "-q", "-b", "wip")
        commit(work, "b", "x\n")
        git(work, "checkout", "-q", "main")
        val result = cli.deleteBranch("wip")
        assertFalse(result.ok)
        assertEquals(GitFailure.NOT_MERGED, result.failure)
        assertEquals("Not deleted", describeAction("Deleting", "Deleted", result).title)
        assertTrue("wip" in git(work, "branch"))

        assertTrue(cli.deleteBranch("wip", force = true).ok)
        assertFalse("wip" in git(work, "branch"))
    }

    @Test
    fun `error summary skips trailing hints`() {
        val output = """
            error: the branch 'wip' is not fully merged
            hint: If you are sure you want to delete it, run 'git branch -D wip'
            hint: Disable this message with "git config set advice.forceDeleteBranch false"
        """.trimIndent()
        assertEquals("the branch 'wip' is not fully merged", errorSummary(output))
        assertEquals("x", errorSummary("fatal: x\nhint: y"))
        assertEquals("plain line", errorSummary("plain line\nhint: y"))
        assertEquals("hint: only", errorSummary("hint: only"))
    }

    @Test
    fun `checkout detaches head at the commit`() {
        prepare()
        val first = head()
        commit(work, "a", "two\n")

        assertTrue(GitCli(work).checkout(first).ok)
        assertEquals(first, head())
    }

    @Test
    fun `tag points at the chosen commit`() {
        prepare()
        val first = head()
        commit(work, "a", "two\n")

        assertTrue(GitCli(work).tag("v1", first).ok)
        assertEquals(first, git(work, "rev-parse", "v1"))
    }

    @Test
    fun `clean merge brings the branch in`() {
        prepare()
        git(work, "checkout", "-q", "-b", "side")
        commit(work, "b", "side\n")
        git(work, "checkout", "-q", "main")
        commit(work, "c", "main\n")

        assertTrue(GitCli(work).merge("side").ok)
        assertTrue(File(work, "b").exists())
    }

    @Test
    fun `conflicting merge can be aborted`() {
        prepare()
        git(work, "checkout", "-q", "-b", "side")
        commit(work, "a", "side\n")
        git(work, "checkout", "-q", "main")
        commit(work, "a", "main\n")
        val before = head()
        val cli = GitCli(work)

        assertEquals(GitFailure.CONFLICT, cli.merge("side").failure)
        assertTrue(cli.abortOperation().ok)
        assertEquals(before, head())
        assertEquals("main\n", File(work, "a").readText())
    }

    @Test
    fun `cherry pick copies a commit and a conflict can be aborted`() {
        prepare()
        git(work, "checkout", "-q", "-b", "side")
        commit(work, "b", "side\n")
        val clean = head()
        commit(work, "a", "side\n")
        val conflicting = head()
        git(work, "checkout", "-q", "main")
        commit(work, "a", "main\n")
        val cli = GitCli(work)

        assertTrue(cli.cherryPick(commitInfo(clean)).ok)
        assertTrue(File(work, "b").exists())

        val before = head()
        assertEquals(GitFailure.CONFLICT, cli.cherryPick(commitInfo(conflicting)).failure)
        assertTrue(cli.abortOperation().ok)
        assertEquals(before, head())
    }

    @Test
    fun `revert undoes a commit and handles merge commits`() {
        prepare()
        commit(work, "a", "two\n")
        val cli = GitCli(work)
        assertTrue(cli.revert(commitInfo(head())).ok)
        assertEquals("one\n", File(work, "a").readText())

        git(work, "checkout", "-q", "-b", "side")
        commit(work, "b", "side\n")
        git(work, "checkout", "-q", "main")
        git(work, "merge", "-q", "--no-ff", "--no-edit", "side")
        assertTrue(cli.revert(commitInfo(head(), parents = 2)).ok)
        assertFalse(File(work, "b").exists())
    }

    @Test
    fun `reset modes keep or drop changes as promised`() {
        prepare()
        val first = head()
        commit(work, "a", "two\n")
        val second = head()
        val cli = GitCli(work)

        assertTrue(cli.reset(first, ResetMode.SOFT).ok)
        assertTrue(file("a").hasStaged)
        assertEquals("two\n", File(work, "a").readText())

        git(work, "reset", "-q", "--hard", second)
        assertTrue(cli.reset(first, ResetMode.MIXED).ok)
        assertFalse(file("a").hasStaged)
        assertTrue(file("a").hasUnstaged)

        git(work, "reset", "-q", "--hard", second)
        assertTrue(cli.reset(first, ResetMode.HARD).ok)
        assertEquals("one\n", File(work, "a").readText())
        assertTrue(cli.status().isEmpty())
    }

    @Test
    fun `stash list and name status parse git output`() {
        val sep = '\u001f'
        val list = parseStashList("stash@{0}${sep}abc${sep}100${sep}On main: fix: colons stay\nstash@{1}${sep}def${sep}90${sep}WIP on dev: 1234567 last commit\n")
        assertEquals(listOf("fix: colons stay", "1234567 last commit"), list.map { it.message })
        assertEquals(listOf("main", "dev"), list.map { it.branch })

        val files = parseNameStatus("M\u0000a b.txt\u0000R100\u0000old\u0000new\u0000A\u0000c\u0000")
        assertEquals(listOf("a b.txt" to 'M', "new" to 'R', "c" to 'A'), files.map { it.path to it.code })
    }

    @Test
    fun `stash takes tracked and untracked changes and pop brings them back`() {
        prepare()
        File(work, "a").writeText("changed\n")
        File(work, "new").writeText("fresh\n")
        val cli = GitCli(work)

        assertTrue(cli.stash("my work").ok)
        assertTrue(cli.status().isEmpty())
        val entry = cli.stashList().single()
        assertEquals("my work", entry.message)
        assertEquals("main", entry.branch)

        val files = cli.stashFiles(entry)
        assertEquals(setOf("a" to false, "new" to true), files.map { it.path to it.untracked }.toSet())
        assertTrue("+changed" in cli.stashDiff(entry, files.first { it.path == "a" }))
        assertTrue("+fresh" in cli.stashDiff(entry, files.first { it.path == "new" }))

        assertTrue(cli.popStash(entry).ok)
        assertEquals("changed\n", File(work, "a").readText())
        assertEquals("fresh\n", File(work, "new").readText())
        assertTrue(cli.stashList().isEmpty())
    }

    @Test
    fun `apply keeps the stash and drop removes it`() {
        prepare()
        File(work, "a").writeText("changed\n")
        val cli = GitCli(work)
        cli.stash("")
        val entry = cli.stashList().single()

        assertTrue(cli.applyStash(entry).ok)
        assertEquals(1, cli.stashList().size)
        assertTrue(cli.dropStash(entry).ok)
        assertTrue(cli.stashList().isEmpty())
    }

    @Test
    fun `a stale stash entry is not touched`() {
        prepare()
        val cli = GitCli(work)
        File(work, "a").writeText("older\n")
        cli.stash("older")
        val stale = cli.stashList().single()
        File(work, "a").writeText("newer\n")
        cli.stash("newer") // stash@{0} 现在是另一条

        assertFalse(cli.dropStash(stale).ok)
        assertEquals(2, cli.stashList().size)
    }

    @Test
    fun `a conflicting pop keeps the stash`() {
        prepare()
        File(work, "a").writeText("stashed\n")
        val cli = GitCli(work)
        cli.stash("mine")
        commit(work, "a", "committed\n")

        val result = cli.popStash(cli.stashList().single())
        assertFalse(result.ok)
        assertEquals(1, cli.stashList().size)
    }

    @Test
    fun `clone copies a repository into a new folder`() {
        prepare()

        assertTrue(GitCli(root).clone(origin.path, "copy").ok)
        assertTrue(File(root, "copy/a").exists())
        assertFalse(GitCli(root).clone(origin.path, "copy").ok)
    }

    @Test
    fun `repository name comes from the url`() {
        assertEquals("repo", repoNameFromUrl("git@github.com:me/repo.git"))
        assertEquals("repo", repoNameFromUrl("https://github.com/me/repo/"))
        assertEquals("repo", repoNameFromUrl("git@host:repo.git"))
        assertEquals("origin", repoNameFromUrl("/tmp/origin.git"))
    }

    @Test
    fun `askpass script is private, valid and holds no secrets`() {
        val script = File(Askpass.environment().getValue("GIT_ASKPASS"))
        val mode = java.nio.file.Files.getPosixFilePermissions(script.toPath()).map { it.name }.toSet()
        assertEquals(setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE"), mode)

        val p = ProcessBuilder("sh", "-n", script.path).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor())
        assertEquals(Askpass.environment()["GIT_ASKPASS"], Askpass.environment()["SSH_ASKPASS"])
    }

    @Test
    fun `network commands keep working with the askpass environment`() {
        prepare()
        commit(work, "a", "two\n")
        assertTrue(GitCli(work).push().ok)
    }

    @Test
    fun `recent repositories keep the newest first without duplicates`() {
        val list = pushRecent(listOf("/a", "/b", "/c"), "/b", max = 3)
        assertEquals(listOf("/b", "/a", "/c"), list)
        assertEquals(listOf("/d", "/b", "/a"), pushRecent(list, "/d", max = 3))
    }

    @Test
    fun `repo menu cannot reopen the current repository`() {
        val items = labels(repoMenu("/x/a", listOf("/x/a", "/x/b"), {}, {}, {}))
        assertEquals(listOf("a" to false, "b" to true, "Open repository…" to true, "Clone repository…" to true), items.map { it.label to it.enabled })
    }

    private fun labels(entries: List<MenuEntry>) = entries.filterIsInstance<MenuItem>()

    private val noActions = GitActions({}, { _, _, _ -> }, {})

    @Test
    fun `head commit cannot be merged, picked or reset onto itself`() {
        val items = labels(commitMenu(commitInfo("abcdef0123"), isHead = true, "main", noActions))
        assertEquals(
            setOf("Merge into main…", "Rebase onto this commit…", "Edit commits after this…", "Cherry-pick onto main…", "Reset main to here…"),
            items.filterNot { it.enabled }.map { it.label }.toSet(),
        )
        assertTrue(labels(commitMenu(commitInfo("abcdef0123"), isHead = false, "main", noActions)).all { it.enabled })
    }

    @Test
    fun `current branch cannot be deleted or renamed from its menu`() {
        assertEquals(listOf("New branch from here…", "Push main", "Force push…"), labels(branchMenu("main", "abcdef0123", isCurrent = true, "main", noActions)).filter { it.enabled }.map { it.label })
        assertTrue(labels(branchMenu("side", "abcdef0123", isCurrent = false, "main", noActions)).all { it.enabled })
    }

    @Test
    fun `branch menu push items follow the upstream and report the force flag`() {
        val pushed = mutableListOf<Boolean>()
        val withUpstream = labels(branchMenu("side", "abcdef0123", isCurrent = false, "main", noActions, hasUpstream = true, onPush = { pushed += it }))
        withUpstream.single { it.label == "Push side" }.action()
        withUpstream.single { it.label == "Force push…" }.action()
        assertEquals(listOf(false, true), pushed)
        val noUpstream = labels(branchMenu("side", "abcdef0123", isCurrent = false, "main", noActions, hasUpstream = false))
        assertTrue(noUpstream.any { it.label == "Push and set upstream (origin)" && it.enabled })
        assertFalse(noUpstream.single { it.label == "Force push…" }.enabled, "nothing to overwrite without an upstream")
    }

    /** 点分支菜单的「从这里新建分支」，返回弹出的对话框；确认时在 work 上真的执行。 */
    private fun newBranchDialog(branch: String, changes: Int = 0): DialogSpec {
        var asked: DialogSpec? = null
        val cli = GitCli(work)
        val actions = GitActions({ asked = it }, { _, _, op -> cli.op() }, {}, pendingChanges = { changes })
        labels(branchMenu(branch, git(work, "rev-parse", branch), isCurrent = branch == branch(), "main", actions))
            .single { it.label == "New branch from here…" }.action()
        return checkNotNull(asked)
    }

    @Test
    fun `new branch from a branch starts at its tip and switches by default`() {
        prepare()
        git(work, "checkout", "-q", "-b", "side")
        commit(work, "b", "x\n")
        val sideTip = head()
        git(work, "checkout", "-q", "main")

        val dialog = newBranchDialog("side")
        val option = checkNotNull(dialog.option)
        assertTrue(option.checked)
        assertEquals(null, option.note)
        assertTrue(dialog.message.contains(sideTip.take(7)))

        option.onConfirm("topic", true)
        assertEquals("topic", branch())
        assertEquals(sideTip, head())
    }

    @Test
    fun `new branch without switching leaves the current branch alone`() {
        prepare()
        git(work, "branch", "side")
        checkNotNull(newBranchDialog("side").option).onConfirm("topic", false)
        assertEquals("main", branch())
        assertTrue("topic" in git(work, "branch"))
    }

    @Test
    fun `switching to a new branch carries changes or refuses without creating it`() {
        prepare()
        git(work, "checkout", "-q", "-b", "side")
        commit(work, "a", "changed on side\n")
        git(work, "checkout", "-q", "main")

        File(work, "a").writeText("dirty\n")
        val dialog = newBranchDialog("side", changes = 1)
        assertTrue(checkNotNull(dialog.option?.note).contains("1"))
        val cli = GitCli(work)
        assertFalse(cli.createAndSwitch("clash", "refs/heads/side").ok)
        assertEquals("main", branch())
        assertFalse("clash" in git(work, "branch"))
        assertEquals("dirty\n", File(work, "a").readText())

        git(work, "checkout", "-q", "--", "a")
        File(work, "new.txt").writeText("keep\n")
        assertTrue(cli.createAndSwitch("carry", "refs/heads/side").ok)
        assertEquals("carry", branch())
        assertEquals("keep\n", File(work, "new.txt").readText())
    }

    @Test
    fun `conflicts offer to give up the operation`() {
        assertEquals(NetOp.ABORT, describeAction("Merging", "Merged", GitResult(GitFailure.CONFLICT, "CONFLICT")).next)
        assertEquals(NetOp.ABORT, describe(NetOp.PULL, GitResult(GitFailure.CONFLICT, "CONFLICT")).next)
    }

    @Test
    fun `rejected push offers pull as the next step`() {
        val status = describe(NetOp.PUSH, GitResult(GitFailure.REJECTED, "! [rejected] main -> main (fetch first)"))
        assertEquals(NetState.FAILED, status.state)
        assertEquals(NetOp.PULL, status.next)
    }

    @Test
    fun `failures are classified from git output`() {
        assertEquals(GitFailure.AUTH, classifyFailure("git@github.com: Permission denied (publickey)."))
        assertEquals(GitFailure.AUTH, classifyFailure("fatal: could not read Username for 'https://github.com'"))
        assertEquals(GitFailure.AUTH, classifyFailure("Host key verification failed."))
        assertEquals(GitFailure.OTHER, classifyFailure("fatal: not a git repository"))
    }

    @Test
    fun `stash list follows pushes and drops while reusing the cached read`() {
        prepare()
        val cli = GitCli(work)
        assertEquals(emptyList(), cli.stashList())
        File(work, "a").writeText("two\n")
        assertTrue(cli.stash("first").ok)
        assertEquals(listOf("first"), cli.stashList().map { it.message.substringAfter(": ") })
        assertEquals(cli.stashList(), cli.stashList())
        File(work, "a").writeText("three\n")
        assertTrue(cli.stash("second").ok)
        assertEquals(2, cli.stashList().size)
        assertTrue(cli.dropStash(cli.stashList().first()).ok)
        assertEquals(1, cli.stashList().size)
    }

    @Test
    fun `current operation is seen through the cached git dir`() {
        prepare()
        val cli = GitCli(work)
        assertEquals(null, cli.currentOperation())
        git(work, "switch", "-q", "-c", "side")
        commit(work, "a", "side\n")
        git(work, "switch", "-q", "main")
        commit(work, "a", "main\n")
        assertEquals(GitFailure.CONFLICT, cli.merge("side").failure)
        assertEquals(GitOperation.MERGE, cli.currentOperation())
        assertTrue(cli.abortOperation().ok)
        assertEquals(null, cli.currentOperation())
    }

    @Test
    fun `operation exceptions become visible failures and cancellation propagates`() {
        val result = attemptGitOperation { throw java.io.IOException("Cannot start git: directory is missing") }
        assertEquals(GitFailure.OTHER, result.failure)
        val status = describeAction("Committing", "Committed", result)
        assertEquals(NetState.FAILED, status.state)
        assertTrue("directory is missing" in status.detail)
        assertEquals(result.output, status.output)
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> {
            attemptGitOperation { throw java.util.concurrent.CancellationException("cancelled") }
        }
    }

    @Test
    fun `feedback preserves full output and distinguishes warnings from failures`() {
        val output = "error: failed to push some refs\nhint: Pull first\nhint: Then retry"
        val failure = describe(NetOp.PUSH, GitResult(GitFailure.OTHER, output))
        assertEquals(output, failure.output)
        assertEquals("failed to push some refs", failure.detail)
        assertEquals(NetOp.PUSH, failure.next)
        val conflict = describeAction("Merging", "Merged", GitResult(GitFailure.CONFLICT, "CONFLICT in a"))
        assertEquals(NetState.WARNING, conflict.state)
        assertEquals(NetOp.ABORT, conflict.next)
        val unmerged = describeAction("Deleting", "Deleted", GitResult(GitFailure.NOT_MERGED, "not fully merged"))
        assertEquals(NetState.WARNING, unmerged.state)
        assertEquals(null, unmerged.next)
        val success = describeAction("Committing", "Committed", GitResult(null, "[main abc1234] test\n 1 file changed"))
        assertEquals(NetState.DONE, success.state)
        assertTrue(success.output.contains("abc1234"))
        assertTrue(describe(NetOp.PULL, GitResult(null, "")).detail.isNotBlank())
    }

    @Test
    fun `repeated identical feedback has independent display lifetime`() {
        val first = NetStatus("Copied", "", NetState.DONE)
        val second = NetStatus("Copied", "", NetState.DONE)
        assertTrue(first.id != second.id)
    }
}
