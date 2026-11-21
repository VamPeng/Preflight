package gitviz.git

import gitviz.*
import gitviz.logic.*
import gitviz.service.*
import gitviz.ui.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

enum class GitFailure { REJECTED, AUTH, CONFLICT, NOT_MERGED, UNKNOWN, PAUSED, OTHER }

enum class ResetMode(val flag: String) {
    SOFT("--soft"), MIXED("--mixed"), HARD("--hard");

    val label: String get() = when (this) {
        SOFT -> t("Soft", "软重置")
        MIXED -> t("Mixed", "混合重置")
        HARD -> t("Hard", "硬重置")
    }

    val hint: String get() = when (this) {
        SOFT -> t("Undo commits, keep changes staged", "撤销提交，改动保留在暂存区")
        MIXED -> t("Undo commits, keep changes in the working tree", "撤销提交，改动保留在工作区")
        HARD -> t("Move HEAD, index and working tree. Uncommitted changes are discarded.", "移动 HEAD、暂存区和工作区；未提交的改动会被丢弃")
    }
}

/** 一次网络操作的结果。failure 为 null 表示成功，output 是 git 的原始输出。 */
data class GitResult(val failure: GitFailure?, val output: String, val lessons: List<GitLesson> = emptyList()) {
    val ok: Boolean get() = failure == null
}

internal data class BranchForcePush(val branch: String, val remote: String, val remoteRef: String, val expectedRemote: String) {
    val destination: String get() = "$remote/${remoteRef.removePrefix("refs/heads/")}"
}

/** 把进程启动、磁盘等异常转换为可见的失败结果；协程取消仍交给调用者处理。 */
internal fun attemptGitOperation(op: () -> GitResult): GitResult = try {
    op()
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: Exception) {
    GitResult(GitFailure.OTHER, e.message?.takeIf { it.isNotBlank() } ?: e.toString())
}

internal typealias OperationRunner = (String, String, GitCli.() -> GitResult, (Boolean) -> Unit) -> Unit

/** 工作区里一个有改动的文件。index、worktree 是 git status 的两列状态字母。 */
data class WorkingFile(val path: String, val oldPath: String?, val index: Char, val worktree: Char) {
    val untracked: Boolean get() = index == '?'
    val hasStaged: Boolean get() = index != ' ' && index != '?'
    val hasUnstaged: Boolean get() = worktree != ' '

    /** 还没解决的冲突：UU、AA、DD，以及一边删除一边修改的 AU、UA、DU、UD。 */
    val conflicted: Boolean get() = index == 'U' || worktree == 'U' || (index == worktree && (index == 'A' || index == 'D'))
}

/** 进行到一半、等待解决冲突的操作。 */
enum class GitOperation {
    MERGE, CHERRY_PICK, REVERT, REBASE;

    val running: String get() = when (this) {
        REBASE -> t("Rebasing", "正在变基")
        MERGE -> t("Merging", "正在合并")
        CHERRY_PICK -> t("Cherry-picking", "正在Cherry-pick")
        REVERT -> t("Reverting", "正在回滚")
    }

    val finish: String get() = when (this) {
        REBASE -> t("Continue rebase", "继续变基")
        MERGE -> t("Finish merge", "完成合并")
        CHERRY_PICK -> t("Finish cherry-pick", "完成Cherry-pick")
        REVERT -> t("Finish revert", "完成回滚")
    }
}

/** 一条储藏。ref 是 stash@{n}，序号会随增删变化，id 才是它不变的身份。 */
data class StashEntry(val ref: String, val id: String, val message: String, val branch: String, val timeSeconds: Long)

/** 储藏里的一个文件。code 是 A、M、D 这样的状态字母，untracked 表示储藏时还是未跟踪的新文件。 */
data class StashFile(val path: String, val code: Char, val untracked: Boolean = false)

private const val FieldSeparator = '\u001f'

/** 解析 stash list，每行是 ref、哈希、时间、说明。说明形如 “On main: 消息” 或 “WIP on main: abc1234 消息”。 */
internal fun parseStashList(text: String): List<StashEntry> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
    val fields = line.split(FieldSeparator, limit = 4)
    if (fields.size < 4) return@mapNotNull null
    val subject = fields[3]
    val branch = subject.substringBefore(':').removePrefix("WIP on ").removePrefix("On ").trim()
    StashEntry(fields[0], fields[1], subject.substringAfter(": ", subject), branch, fields[2].toLongOrNull() ?: 0)
}.toList()

/** 解析 git diff --name-status -z：状态和路径用 NUL 分隔，重命名和复制有两段路径。 */
internal fun parseNameStatus(text: String): List<StashFile> {
    val parts = text.split('\u0000').filter { it.isNotEmpty() }
    val files = ArrayList<StashFile>()
    var i = 0
    while (i < parts.size) {
        val code = parts[i++].first()
        if (code == 'R' || code == 'C') i++ // 跳过旧路径
        parts.getOrNull(i++)?.let { files += StashFile(it, code) }
    }
    return files
}

/** 解析 git status --porcelain -z：用 NUL 分隔，路径不转义；重命名多带一段旧路径。 */
internal fun parseStatus(text: String): List<WorkingFile> {
    val parts = text.split('\u0000')
    val files = ArrayList<WorkingFile>()
    var i = 0
    while (i < parts.size) {
        val entry = parts[i++]
        if (entry.length < 4) continue
        val index = entry[0]
        val worktree = entry[1]
        val moved = index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C'
        val oldPath = if (moved) parts.getOrNull(i++) else null
        files += WorkingFile(entry.substring(3), oldPath, index, worktree)
    }
    return files
}

/**
 * 从 git 的输出里挑一行说明失败原因。git 常在 error: 后面再跟几行 hint:，
 * 只取最后一行会拿到 “Disable this message with …” 这种和原因无关的提示。
 * 优先取最后一条 error: 或 fatal:，没有的话取最后一行不是 hint: 的。
 */
internal fun errorSummary(output: String): String {
    val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    lines.lastOrNull { it.startsWith("error:") || it.startsWith("fatal:") }?.let {
        return it.removePrefix("error:").removePrefix("fatal:").trim()
    }
    return lines.lastOrNull { !it.startsWith("hint:") } ?: lines.lastOrNull().orEmpty()
}

/** 从 git 的输出判断失败原因，界面据此决定显示什么、提供什么操作。 */
internal fun classifyFailure(output: String): GitFailure = when {
    "is not fully merged" in output -> GitFailure.NOT_MERGED
    "CONFLICT" in output || "Automatic merge failed" in output || "after resolving the conflicts" in output -> GitFailure.CONFLICT
    "[rejected]" in output || "non-fast-forward" in output || "fetch first" in output -> GitFailure.REJECTED
    listOf(
        "Permission denied", "Authentication failed", "could not read Username", "could not read Password",
        "Host key verification failed", "terminal prompts disabled",
    ).any { it in output } -> GitFailure.AUTH
    else -> GitFailure.OTHER
}

/**
 * 用系统的 git 做网络操作，SSH 配置、钥匙串和 credential helper 都沿用用户现有的。
 * 所有方法都会阻塞，请在 IO 线程调用。
 */
class GitCli(val workTree: File, private val isolated: Boolean = false) {
    internal fun atPath(path: File): GitCli = GitCli(path, isolated)

    /**
     * 推送指定的本地分支，不切换当前分支：有上游就推到上游（可以是别的远程、别的名字），没有就推到 origin 并设为上游。
     * 只推这一个分支的引用，不依赖 push.default。
     */
    fun pushBranch(branch: String): GitResult {
        if (capture("rev-parse", "-q", "--verify", "refs/heads/$branch") == null)
            return GitResult(GitFailure.OTHER, t("Branch $branch doesn't exist", "分支 $branch 不存在"))
        val remote = config("branch.$branch.remote")?.takeIf { it.isNotBlank() && it != "." }
        val merge = config("branch.$branch.merge")?.takeIf { it.startsWith("refs/heads/") }
        if (remote != null && merge != null) return network("push", "--no-follow-tags", "--", remote, "refs/heads/$branch:$merge")
        if ("origin" !in read("remote").lines()) return GitResult(GitFailure.OTHER, t("This repository has no remote named origin to push to", "这个仓库没有名为 origin 的远程，无法推送"))
        return network("push", "--no-follow-tags", "-u", "--", "origin", "refs/heads/$branch:refs/heads/$branch")
    }

    /** 在展示确认框前固定目标与预期远端提交，之后的 fetch 不会改变这次强推的 lease。 */
    internal fun prepareBranchForcePush(branch: String): BranchForcePush? {
        val source = "refs/heads/$branch"
        val upstream = capture("for-each-ref", "--format=%(refname)%00%(upstream:remotename)%00%(upstream:remoteref)%00%(upstream)", "--", source)
            ?.lineSequence()?.firstOrNull { it.startsWith("$source\u0000") } ?: return null
        val (_, remote, remoteRef, trackingRef) = upstream.split('\u0000', limit = 4)
        if (remote.isEmpty() || remote == "." || !remoteRef.startsWith("refs/heads/") || trackingRef.isEmpty()) return null
        val expectedRemote = capture("rev-parse", "--verify", "--end-of-options", "$trackingRef^{commit}") ?: return null
        return BranchForcePush(branch, remote, remoteRef, expectedRemote)
    }

    internal fun pushBranch(plan: BranchForcePush): GitResult =
        network("push", "--no-follow-tags", "--force-with-lease=${plan.remoteRef}:${plan.expectedRemote}",
            "--", plan.remote, "refs/heads/${plan.branch}:${plan.remoteRef}")

    /**
     * 推送当前分支；没有上游时设到 origin，以后直接 git push 也能用。
     * force 用 --force-with-lease：只有远程分支还是上次获取时的样子才覆盖，别人新推的提交不会被冲掉。
     */
    fun push(force: Boolean = false): GitResult {
        val branch = capture("symbolic-ref", "--short", "-q", "HEAD")
            ?: return GitResult(GitFailure.OTHER, t("Not on any branch, so there's nothing to push", "现在不在任何分支上，无法推送"))
        val hasUpstream = capture("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}") != null
        val flags = if (force) arrayOf("--force-with-lease") else emptyArray()
        return if (hasUpstream) network("push", *flags) else network("push", *flags, "-u", "origin", branch)
    }

    /** 检出远程分支：已有同名本地分支就切过去，没有就新建一个跟踪它的本地分支。remoteBranch 形如 origin/feature/x。 */
    fun checkoutRemote(remoteBranch: String): GitResult {
        val remote = read("remote").lines().filter { it.isNotBlank() && remoteBranch.startsWith("$it/") }.maxByOrNull { it.length }
            ?: return GitResult(GitFailure.OTHER, t("Unknown remote for $remoteBranch", "找不到 $remoteBranch 所属的远程"))
        val local = remoteBranch.removePrefix("$remote/")
        return if (capture("rev-parse", "-q", "--verify", "refs/heads/$local") != null) run("switch", local)
            else run("switch", "-c", local, "--track", "refs/remotes/$remoteBranch")
    }

    fun fetch(remote: String? = null): GitResult = if (remote == null) network("fetch") else {
        if (remote != "." && remote !in read("remote").lines()) GitResult(GitFailure.OTHER, "Unknown remote") else network("fetch", "--", remote)
    }

    /** 把 url 克隆到当前目录下的 folder。 */
    fun clone(url: String, folder: String): GitResult = network("clone", "--", url, folder)

    fun config(key: String): String? = capture("config", "--get", key)

    fun status(): List<WorkingFile> {
        val (exit, output) = exec(listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=all"))
        check(exit == 0) { output }
        return parseStatus(output)
    }

    /**
     * 文件视图用的状态：比 status() 多了被忽略的路径（index、worktree 都是 '!'）。
     * --ignored=matching 只报规则直接匹配到的路径，被忽略的目录报一行 dir/，不展开里面成千上万个文件。
     */
    fun treeStatus(): List<WorkingFile> {
        val (exit, output) = exec(listOf("--no-optional-locks", "status", "--porcelain", "-z", "--untracked-files=all", "--ignored=matching"))
        check(exit == 0) { output }
        return parseStatus(output)
    }

    fun stage(file: WorkingFile): GitResult = stage(listOf(file))

    fun unstage(file: WorkingFile): GitResult = unstage(listOf(file))

    fun discard(file: WorkingFile): GitResult = discard(listOf(file))

    /** 暂存这些文件，包括未跟踪的文件和删除。 */
    fun stage(files: List<WorkingFile>): GitResult = onPaths(listOf("add"), files)

    fun unstage(files: List<WorkingFile>): GitResult =
        if (hasCommits()) onPaths(listOf("restore", "--staged"), files) else onPaths(listOf("rm", "--cached", "-q"), files)

    /** 丢弃工作区的改动，已暂存的部分不动。未跟踪的文件会被删除。 */
    fun discard(files: List<WorkingFile>): GitResult {
        val (fresh, tracked) = files.partition { it.untracked }
        return coordinator.execute(OperationRequest(OperationKind.FILE)) {
            val restored = onPaths(listOf("restore"), tracked)
            if (!restored.ok) return@execute restored
            // git clean 不支持从文件读路径，只能放在命令行里，分批免得超出长度上限
            fresh.chunked(CLEAN_BATCH).map { run("clean", "-f", "-q", "--", *it.map { f -> literal(f.path) }.toTypedArray()) }
                .firstOrNull { !it.ok } ?: GitResult(null, "")
        }
    }

    /**
     * 对一批文件执行同一条命令。路径用 NUL 分隔，从标准输入交给 git，
     * 不受命令行长度限制；每个路径都按字面匹配，[id].tsx 这样的文件名不会被当成通配符。
     */
    private fun onPaths(command: List<String>, files: List<WorkingFile>): GitResult {
        if (files.isEmpty()) return GitResult(null, "")
        val args = command + listOf("--pathspec-from-file=-", "--pathspec-file-nul")
        val input = files.joinToString("") { literal(it.path) + "\u0000" }.toByteArray()
        return coordinator.execute(OperationRequest.command(args)) {
            // 教学记录里写等价的命令（带上路径），不写从标准输入读路径的内部形式；太多时只列前面一部分
            val shown = files.take(TEACH_PATHS).map { literal(it.path) } + if (files.size > TEACH_PATHS) listOf("...") else emptyList()
            Teaching.command(command + "--" + shown)
            raw(args, input = input).result()
        }
    }

    fun diff(file: WorkingFile, staged: Boolean): String {
        val args = when {
            file.untracked -> listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-index", "--", "/dev/null", file.path)
            staged -> listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--cached", "--", literal(file.path))
            else -> listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--", literal(file.path))
        }
        return exec(args).second.take(GitRepo.MAX_DIFF_CHARS)
    }

    fun commit(message: String, amend: Boolean): GitResult =
        if (amend) run("commit", "--amend", "-m", message) else run("commit", "-m", message)

    /**
     * 只提交这几个文件在工作区里的样子，不经过暂存区（`git commit --only`）；别的文件已暂存的改动原样留在暂存区。
     * 新文件 git 还不认识，先 `add -N` 登记；登记或提交失败都撤回本次登记，文件回到未跟踪。
     */
    fun commitOnly(message: String, files: List<WorkingFile>): GitResult {
        require(files.isNotEmpty()) { "Nothing to commit" }
        val fresh = files.filter { it.untracked }
        // 重命名是一个逻辑文件：新路径写入和旧路径删除必须一起提交；复制则保留源路径。
        val paths = files.flatMap { file ->
            val oldPath = file.oldPath?.takeIf { file.index == 'R' || file.worktree == 'R' }
            if (oldPath == null) listOf(file) else listOf(file, file.copy(path = oldPath, oldPath = null))
        }.distinctBy { it.path }
        val checked = files.map { it.path }.toSet()
        return coordinator.execute(OperationRequest(OperationKind.COMMIT)) {
            // commit --only 按磁盘内容提交旧路径：那里若又有了文件，会把它当成修改提交，而不是删除。
            // 用户没勾选它时不能替用户提交，先停下来说明。
            val reoccupied = paths.firstOrNull { it.path !in checked && Files.exists(workTree.toPath().resolve(it.path), LinkOption.NOFOLLOW_LINKS) }
            if (reoccupied != null) return@execute GitResult(GitFailure.OTHER, t(
                "\"${reoccupied.path}\" was renamed, but something exists at its old path again. Check it too, or move it away, then commit.",
                "“${reoccupied.path}”已重命名，但原路径又有了文件。请一并勾选，或先移走它再提交。"))
            // 界面的未跟踪状态可能已过期；只清理操作开始时尚未进入索引的路径。
            val indexed = if (fresh.isEmpty()) emptySet() else read("ls-files", "--cached", "-z").split('\u0000').toSet()
            val registering = fresh.filter { it.path !in indexed }
            fun rollback(result: GitResult): GitResult {
                // add -N 可以部分成功；未登记的路径也在列表里，需要忽略未匹配项。
                val undone = onPaths(listOf("rm", "--cached", "-q", "--ignore-unmatch"), registering)
                return if (undone.ok) result else result.copy(output = result.output + "\n" +
                    t("Couldn't undo new-file registration:", "无法撤回新文件登记：") + "\n" + undone.output)
            }
            val added = onPaths(listOf("add", "-N"), registering)
            if (!added.ok) return@execute rollback(added)
            val result = onPaths(listOf("commit", "--only", "-m", message), paths)
            if (result.ok) result else rollback(result)
        }
    }

    fun lastCommitMessage(): String = capture("log", "-1", "--format=%B").orEmpty()

    fun createBranch(name: String, commitId: String): GitResult = run("branch", "--", name, commitId)

    /** 新建分支并切过去。工作区改动和起点冲突时 git 拒绝，分支不会建出来。 */
    fun createAndSwitch(name: String, start: String): GitResult = run("switch", "-c", name, start)

    fun switchTo(branch: String): GitResult = run("switch", branch)

    /** 检出某个提交，进入分离 HEAD。 */
    fun checkout(commitId: String): GitResult = run("switch", "--detach", commitId)

    fun renameBranch(from: String, to: String): GitResult = run("branch", "-m", "--", from, to)

    /**
     * 默认只删已合并的分支，没合并的 git 会拒绝，提交不会丢。
     * force 为 true 时用 -D 强制删，没合并的提交之后只能从 reflog 找回。
     */
    fun deleteBranch(name: String, force: Boolean = false): GitResult = run("branch", if (force) "-D" else "-d", "--", name)

    fun tag(name: String, commitId: String): GitResult = run("tag", "--", name, commitId)

    fun merge(ref: String): GitResult = run("merge", "--no-edit", "--no-overwrite-ignore", "--no-autostash", ref)

    fun cherryPick(commit: CommitInfo): GitResult = run("cherry-pick", *mainlineFor(commit), commit.id)

    fun revert(commit: CommitInfo, mainline: Int = 1): GitResult = run("revert", "--no-edit", *mainlineFor(commit, mainline), commit.id)

    /** 合并提交有两个父提交，要指明相对哪一个，这里取第一个。 */
    private fun mainlineFor(commit: CommitInfo, mainline: Int = 1): Array<String> {
        val parents = read("rev-list", "--parents", "-n", "1", commit.id).trim().split(' ').drop(1)
        require(parents.size <= 1 || mainline in 1..parents.size) { "Invalid mainline parent" }
        return if (parents.size > 1) arrayOf("-m", mainline.toString()) else emptyArray()
    }

    fun reset(commitId: String, mode: ResetMode): GitResult = run("reset", mode.flag, commitId)

    /**
     * 储藏列表来自 refs/stash 的 reflog（git stash 总会建它）。工作区轮询每隔几秒就问一次，
     * 文件没变就直接用上次的结果，不再起 git 进程。
     */
    fun stashList(): List<StashEntry> {
        val log = gitDirs.common.resolve("logs/refs/stash")
        if (!log.isFile) return emptyList()
        val key = log.lastModified() to log.length()
        stashCache?.let { (k, list) -> if (k == key) return list }
        val format = listOf("%gd", "%H", "%ct", "%gs").joinToString("%x1f")
        val (exit, output) = exec(listOf("stash", "list", "--format=$format"))
        check(exit == 0) { output }
        return parseStashList(output).also { stashCache = key to it }
    }

    @Volatile private var stashCache: Pair<Pair<Long, Long>, List<StashEntry>>? = null

    /** 这个工作区用到的 git 目录，自己的和共用的，可能是同一个。 */
    val gitDirectories: List<File> get() = listOf(gitDirs.own, gitDirs.common).map { it.canonicalFile }.distinct()

    /** 要监听变化的目录：工作区，加上不在工作区里面的 git 目录（链接工作区、--separate-git-dir）。 */
    val watchRoots: List<File>
        get() {
            val root = workTree.canonicalFile
            return listOf(root) + gitDirectories.filter { !it.toPath().startsWith(root.toPath()) }
        }

    /** 工作区自己的 git 目录（MERGE_HEAD 这些在这里），和多个工作区共用的目录（refs、reflog 在这里）。 */
    private class GitDirs(val own: File, val common: File)

    // 仓库位置不会变，第一次用到时问一次 git
    private val gitDirs: GitDirs by lazy {
        val lines = capture("rev-parse", "--git-dir", "--git-common-dir")?.lines().orEmpty()
        val own = workTree.resolve(lines.getOrNull(0) ?: ".git")
        GitDirs(own, lines.getOrNull(1)?.let(workTree::resolve) ?: own)
    }

    /** 收起工作区的全部改动，包括未跟踪的文件。 */
    fun stash(message: String): GitResult =
        if (message.isBlank()) run("stash", "push", "--include-untracked")
        else run("stash", "push", "--include-untracked", "-m", message)

    fun stashFiles(entry: StashEntry): List<StashFile> {
        val tracked = parseNameStatus(exec(listOf("diff", "--name-status", "-z", "${entry.id}^1", entry.id)).second)
        val untracked = if (hasUntracked(entry)) {
            exec(listOf("ls-tree", "-r", "--name-only", "-z", "${entry.id}^3")).second.split('\u0000')
                .filter { it.isNotEmpty() }.map { StashFile(it, 'A', untracked = true) }
        } else emptyList()
        return tracked + untracked
    }

    fun stashDiff(entry: StashEntry, file: StashFile): String {
        val range = if (file.untracked) listOf(EMPTY_TREE, "${entry.id}^3") else listOf("${entry.id}^1", entry.id)
        return exec(listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-renames") + range + listOf("--", literal(file.path))).second.take(GitRepo.MAX_DIFF_CHARS)
    }

    fun applyStash(entry: StashEntry): GitResult = stashChange(entry, "apply")

    /** 应用并删除。有冲突时 git 会保留储藏。 */
    fun popStash(entry: StashEntry): GitResult = stashChange(entry, "pop")

    fun dropStash(entry: StashEntry): GitResult = ifStillThere(entry, "drop") { run("stash", "drop", "-q", entry.ref) }

    private fun stashChange(entry: StashEntry, command: String) = ifStillThere(entry, command) {
        val result = run("stash", command, entry.ref)
        // 储藏没有 --abort，冲突的文件已经写进工作区，只能到本地改动里处理
        if (result.failure == GitFailure.CONFLICT) GitResult(GitFailure.OTHER, t("Conflicts. The files were written to the working tree and the stash was kept.", "有冲突，文件已写入工作区，储藏保留。")) else result
    }

    /** stash@{n} 的序号会变，动手前核对它还是用户选的那一条，不然可能删错。 */
    private fun ifStillThere(entry: StashEntry, command: String, action: () -> GitResult): GitResult = coordinator.execute(OperationRequest(OperationKind.STASH, listOf("stash", command, entry.ref))) {
        if (capture("rev-parse", "-q", "--verify", entry.ref) == entry.id) action()
        else GitResult(GitFailure.OTHER, t("The stash list changed. Please choose again.", "储藏列表已经变了，请重新选择"))
    }

    private fun hasUntracked(entry: StashEntry) = capture("rev-parse", "-q", "--verify", "${entry.id}^3") != null

    fun currentOperation(): GitOperation? {
        val gitDir = gitDirs.own
        return when {
            (gitDir.resolve("rebase-merge").isDirectory || gitDir.resolve("rebase-apply").isDirectory) -> GitOperation.REBASE
            gitDir.resolve("MERGE_HEAD").exists() -> GitOperation.MERGE
            gitDir.resolve("CHERRY_PICK_HEAD").exists() -> GitOperation.CHERRY_PICK
            gitDir.resolve("REVERT_HEAD").exists() -> GitOperation.REVERT
            else -> null
        }
    }

    /** 放弃进行到一半的合并、cherry-pick 或回滚，回到操作之前；拖拽时先切换了分支的，再切回原分支。 */
    fun abortOperation(): GitResult = coordinator.execute(OperationRequest(OperationKind.OTHER)) {
        val origin = switchOrigin()
        val result = abortNativeOperation()
        if (result.ok) clearSwitchOrigin()
        if (result.ok && origin != null) {
            val args = if (origin.startsWith("ref: refs/heads/")) listOf("switch", "--no-overwrite-ignore", origin.removePrefix("ref: refs/heads/"))
                else listOf("switch", "--no-overwrite-ignore", "--detach", origin)
            val switched = raw(args, teach = true).result()
            if (switched.ok) result else switched.copy(output = t("Operation aborted; local changes prevent returning to the original branch. Review Changes.\n", "操作已中止；本地改动阻止返回原分支，请查看本地改动。\n") + switched.output)
        } else result
    }

    /** 拖拽合并／变基时先切换了分支、又停在冲突上：记下原 HEAD 和这次暂停的操作，重启后中止也能切回。 */
    internal fun rememberSwitchOrigin(head: String) {
        val identity = operationIdentity() ?: return clearSwitchOrigin()
        ownDirectory.resolve(SWITCH_ORIGIN).writeText("$head\n$identity")
    }
    private fun switchOrigin(): String? {
        val lines = ownDirectory.resolve(SWITCH_ORIGIN).takeIf { it.isFile }?.readText()?.split('\n', limit = 2) ?: return null
        return lines[0].takeIf { lines.getOrNull(1) == operationIdentity() }
    }
    internal fun clearSwitchOrigin() { ownDirectory.resolve(SWITCH_ORIGIN).delete() }

    private fun abortNativeOperation(): GitResult = when (currentOperation()) {
        GitOperation.REBASE -> rebaseStep("--abort")
        GitOperation.MERGE -> run("merge", "--abort")
        GitOperation.CHERRY_PICK -> run("cherry-pick", "--abort")
        GitOperation.REVERT -> run("revert", "--abort")
        null -> GitResult(GitFailure.OTHER, t("No operation in progress", "没有进行中的操作"))
    }

    /** 冲突都解决后完成操作。提交说明用 git 准备好的，不打开编辑器。 */
    fun continueOperation(): GitResult = when (currentOperation()) {
        GitOperation.REBASE -> rebaseStep("--continue")
        GitOperation.MERGE -> run("commit", "--no-edit")
        GitOperation.CHERRY_PICK -> run("-c", "core.editor=true", "cherry-pick", "--continue")
        GitOperation.REVERT -> run("-c", "core.editor=true", "revert", "--continue")
        null -> GitResult(GitFailure.OTHER, t("No operation in progress", "没有进行中的操作"))
    }.also { if (currentOperation() == null) clearSwitchOrigin() }

    /** 工作区里的文件内容，用来解析冲突标记。文件不存在时（一边删除了）返回 null。 */
    fun readWorkingFile(path: String): String? = workTree.resolve(path).takeIf { it.isFile }?.readText()

    /** 写入解决后的内容，并标记这个文件已解决。 */
    fun resolveFile(path: String, content: String, expectedSignature: String? = null): GitResult = coordinator.execute(OperationRequest(OperationKind.FILE)) {
        Teaching.note("GitViz saves the edited conflict content, then stages the file.", "GitViz 保存编辑后的冲突内容，再暂存文件。")
        if (expectedSignature != null) check(conflictSources(path).let { it.signature == expectedSignature && it.stages.isNotEmpty() }) { t("Conflict inputs changed. Reload before saving.", "冲突输入已改变，请重新加载后保存。") }
        val destination = safeWorkingPath(path)
        check(!java.nio.file.Files.isSymbolicLink(destination)) { "Cannot text-edit a symbolic link" }
        java.nio.file.Files.createDirectories(destination.parent)
        val temporary = java.nio.file.Files.createTempFile(destination.parent, ".gitviz-resolution-", ".tmp")
        try {
            java.nio.file.Files.writeString(temporary, content)
            if (java.nio.file.Files.exists(destination)) runCatching { java.nio.file.Files.setPosixFilePermissions(temporary, java.nio.file.Files.getPosixFilePermissions(destination)) }
            check(safeWorkingPath(path) == destination)
            java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { java.nio.file.Files.deleteIfExists(temporary) }
        run("add", "--", literal(path))
    }

    /** 规则文件的位置。exclude 在共用的 git 目录里，链接工作区也写到那儿，git 才认。 */
    fun ignoreFile(target: IgnoreTarget): File = when (target) {
        IgnoreTarget.GITIGNORE -> File(workTree, ".gitignore")
        IgnoreTarget.EXCLUDE -> gitDirs.common.resolve("info/exclude")
    }

    /**
     * 把这些路径加进忽略规则。忽略只管未跟踪的文件；stopTracking 时再把目录里已跟踪的文件移出版本库（git rm -r --cached），
     * 本地文件留着，删除记录进暂存区。
     */
    fun ignore(paths: List<String>, directory: Boolean, target: IgnoreTarget = IgnoreTarget.GITIGNORE, stopTracking: Boolean = false): GitResult =
        coordinator.execute(OperationRequest(OperationKind.FILE)) {
            paths.forEach { safeWorkingPath(it) }
            val name = if (target == IgnoreTarget.GITIGNORE) ".gitignore" else ".git/info/exclude"
            Teaching.note("GitViz appends escaped rules to $name; no Git command performs this file edit.", "GitViz 向 $name 追加转义后的规则；这个文件编辑不对应单条 Git 命令。")
            appendIgnoreRules(ignoreFile(target), paths, directory)
            if (stopTracking) run("rm", "-r", "--cached", "-q", "--", *paths.map(::literal).toTypedArray()) else GitResult(null, "")
        }

    /** 目录里有几个文件已在版本控制中（在索引里）。 */
    fun trackedCount(dir: String): Int =
        exec(listOf("ls-files", "-z", "--", literal(dir))).let { (exit, out) -> if (exit == 0) out.split('\u0000').count { it.isNotEmpty() } else 0 }

    /** 整个文件用某一边的版本。那一边已经删除这个文件时，就删除它。 */
    fun resolveWhole(path: String, ours: Boolean, expectedSignature: String? = null): GitResult = coordinator.execute(OperationRequest(OperationKind.FILE)) {
        if (expectedSignature != null) check(conflictSources(path).let { it.signature == expectedSignature && it.stages.isNotEmpty() }) { t("Conflict inputs changed. Reload before saving.", "冲突输入已改变，请重新加载后保存。") }
        safeWorkingPath(path)
        val stage = if (ours) 2 else 3
        if (capture("rev-parse", "-q", "--verify", ":$stage:$path") != null) {
            val checkedOut = run("checkout", if (ours) "--ours" else "--theirs", "--", literal(path))
            if (checkedOut.ok) run("add", "--", literal(path)) else checkedOut
        } else {
            run("rm", "-q", "--", literal(path))
        }
    }

    private fun hasCommits(): Boolean = capture("rev-parse", "-q", "--verify", "HEAD") != null

    /** 按字面匹配路径，不然 [id].tsx 这样的文件名会被当成通配符，可能操作到别的文件。 */
    private fun literal(path: String) = ":(literal)$path"

    private fun capture(vararg args: String): String? {
        val (exit, output) = exec(args.toList())
        return output.trim().takeIf { exit == 0 && it.isNotEmpty() }
    }

    internal val coordinator by lazy { OperationCoordinator(this) }
    internal fun execute(request: OperationRequest): GitResult = coordinator.execute(request) {
        raw(request.arguments, environment = if (request.network) Askpass.environment() else emptyMap(), timeout = if (request.network) NETWORK_TIMEOUT_SECONDS else TIMEOUT_SECONDS, teach = true).result()
    }
    private fun run(vararg args: String): GitResult = execute(OperationRequest.command(args.toList()))

    /** 会连接远程的命令：可能要等用户输入，超时更长，也要接上系统的密码对话框。 */
    private fun network(vararg args: String): GitResult = execute(OperationRequest.command(args.toList(), network = true))

    internal fun raw(args: List<String>, input: ByteArray? = null, environment: Map<String, String> = emptyMap(), timeout: Long = TIMEOUT_SECONDS, teach: Boolean = false): GitExecution {
        if (teach) Teaching.command(args, input != null)
        return GitProcess.execute(workTree, if (isolated) listOf("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-c", "protocol.allow=never", "-c", "core.fsmonitor=false") + args else args, input, environment, timeout, isolated)
    }
    internal fun read(vararg args: String): String = raw(args.toList()).requireSuccess().toString(Charsets.UTF_8)
    internal fun bytes(vararg args: String): ByteArray = raw(args.toList()).requireSuccess()
    internal val ownDirectory: File get() = gitDirs.own.canonicalFile
    internal val commonDirectory: File get() = gitDirs.common.canonicalFile

    private fun exec(args: List<String>, timeoutSeconds: Long = TIMEOUT_SECONDS, extraEnvironment: Map<String, String> = emptyMap()): Pair<Int, String> {
        val result = raw(args, environment = extraEnvironment, timeout = timeoutSeconds)
        check(!result.uncertain) { result.output }
        return result.exitCode to if (result.exitCode == 0) result.text else result.output
    }

    private companion object {
        const val TIMEOUT_SECONDS = 120L
        const val NETWORK_TIMEOUT_SECONDS = 600L
        const val CLEAN_BATCH = 200
        const val TEACH_PATHS = 20
        const val SWITCH_ORIGIN = "gitviz-switch-origin"
        const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904" // git 里空目录的固定哈希
    }
}

/** 克隆地址里的仓库名，比如 git@github.com:me/repo.git 得到 repo。 */
internal fun repoNameFromUrl(url: String): String =
    url.trim().trimEnd('/').removeSuffix(".git").substringAfterLast('/').substringAfterLast(':').ifEmpty { "repo" }

/**
 * git 和 ssh 要密码时调用的小脚本，由它弹出 macOS 系统对话框。
 * 密码由系统对话框直接交给 git，不经过 GitViz，所以应用既看不到也存不了。
 * 其他系统没有这个脚本，要密码时直接失败并提示。
 */
internal object Askpass {
    private fun script(cancel: String, trust: String) = """#!/bin/sh
exec /usr/bin/osascript - "$1" <<'APPLESCRIPT'
on run argv
    set promptText to item 1 of argv
    activate
    if promptText contains "yes/no" then
        display dialog promptText buttons {"$cancel", "$trust"} default button "$cancel" cancel button "$cancel" with title "GitViz" with icon caution
        return "yes"
    end if
    if promptText contains "assword" or promptText contains "assphrase" or promptText contains "PIN" then
        return text returned of (display dialog promptText default answer "" with hidden answer with title "GitViz")
    end if
    return text returned of (display dialog promptText default answer "" with title "GitViz")
end run
APPLESCRIPT
"""

    private val path: String? by lazy {
        if (!isMac) return@lazy null
        val file = File.createTempFile("gitviz-askpass", ".sh")
        file.writeText(script(t("Cancel", "取消"), t("Trust", "信任")))
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
        file.deleteOnExit()
        file.path
    }

    fun environment(): Map<String, String> {
        val script = path ?: return emptyMap()
        return mapOf("GIT_ASKPASS" to script, "SSH_ASKPASS" to script, "SSH_ASKPASS_REQUIRE" to "force")
    }
}
