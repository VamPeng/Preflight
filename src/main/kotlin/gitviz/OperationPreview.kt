package gitviz

import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal enum class PreviewKind { MERGE, REBASE, CHERRY_PICK, RESET }
internal data class OperationIntent(val kind: PreviewKind, val target: String, val mode: ResetMode = ResetMode.MIXED,
    val mainline: Int = 1, val switchBranch: String? = null, val ffOnly: Boolean = false, val expectedHead: String? = null,
    val interactive: InteractiveRebasePlan? = null) {
    fun command(parentCount: Int): List<String> = when (kind) {
        PreviewKind.MERGE -> listOf("merge", "--no-edit", "--no-overwrite-ignore", "--no-autostash") + (if (ffOnly) listOf("--ff-only") else emptyList()) + target
        PreviewKind.REBASE -> listOf("-c", "core.editor=true", "rebase") + (if (interactive == null) rebaseFlags else interactiveRebaseFlags) + target
        PreviewKind.CHERRY_PICK -> listOf("cherry-pick") + (if (parentCount > 1) listOf("-m", mainline.toString()) else emptyList()) + target
        PreviewKind.RESET -> listOf("reset", mode.flag, target)
    }
    fun request() = OperationRequest(OperationKind.valueOf(kind.name), switchBranch?.let { listOf("switch", it, "then") }.orEmpty() + listOf(kind.name.lowercase(), target))
}
internal data class PreviewFile(val path: String, val diff: String)
internal data class OperationPlan(val intent: OperationIntent, val fingerprint: String, val parentCount: Int,
    val before: List<CommitInfo>, val after: List<CommitInfo>, val beforeHead: String, val afterHead: String,
    val added: List<String>, val removed: List<String>, val files: List<PreviewFile>, val workingImpact: List<String>,
    val indexChanged: Boolean, val branch: String?, val outcome: GitResult, val warnings: List<String>, val paused: Boolean,
    val projected: List<String>, val repository: String, val expected: List<CommitInfo> = emptyList()) {
    val verified: Boolean get() = warnings.isEmpty() && outcome.ok && !paused
}

private val semanticConfig = setOf("core.autocrlf", "core.eol", "core.safecrlf", "core.filemode", "core.ignorecase", "core.symlinks",
    "merge.ff", "merge.renormalize", "merge.diffalgorithm", "merge.renames", "merge.renamelimit", "merge.directoryrenames", "merge.conflictstyle", "diff.renamelimit")

/** Worktree paths a rehearsal copies: tracked and untracked, not ignored. Listed by Git, never by walking the tree. */
private fun GitCli.worktreePaths(): Set<String> = (read("ls-files", "-z") + "\u0000" + read("ls-files", "--others", "--exclude-standard", "-z"))
    .split('\u0000').filter { it.isNotEmpty() }.toSortedSet()

/**
 * Ignored files the operation may overwrite: paths changed between HEAD and the targets, plus every path a rebase
 * replays (intermediate commits may add and later delete a path). Computed by Git diff, not by scanning the tree.
 */
private fun GitCli.ignoredTouchedPaths(intent: OperationIntent, listed: Set<String>): Set<String> {
    fun names(vararg args: String) = raw(args.toList()).let { if (it.exitCode == 0) it.text.split('\u0000').filter { n -> n.isNotEmpty() } else emptyList() }
    val tips = listOfNotNull(intent.target, intent.switchBranch)
    val touched = tips.flatMap { names("diff", "--name-only", "--no-renames", "-z", "HEAD", it) }.toMutableSet()
    if (intent.kind == PreviewKind.REBASE) touched += names("log", "--name-only", "--format=", "--no-renames", "-z", "${intent.target}..${intent.switchBranch ?: "HEAD"}")
    if (intent.kind == PreviewKind.CHERRY_PICK) touched += names("log", "-1", "-m", "--name-only", "--format=", "--no-renames", "-z", intent.target)
    return touched.filter { it !in listed && Files.exists(workTree.toPath().resolve(it), NOFOLLOW_LINKS) }.toSet()
}

/** Kind, size, mtime, permissions and link target. Enough to notice a change without reading file contents. */
private fun fileStat(root: File, name: String): String {
    val path = root.toPath().resolve(name)
    val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull() ?: return "missing"
    return when {
        attrs.isSymbolicLink -> "link " + Files.readSymbolicLink(path)
        attrs.isDirectory -> "directory"
        attrs.isRegularFile -> "file ${attrs.size()} ${attrs.lastModifiedTime().toMillis()} ${Files.isExecutable(path)}"
        else -> "other"
    }
}

/**
 * Cheap staleness check: HEAD, references, index bytes, status, plus stat data of dirty paths and the rules that affect
 * a rehearsal. Clean tracked files are covered by the index; their contents are never hashed.
 */
internal fun GitCli.previewFingerprint(intent: OperationIntent): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fun add(bytes: ByteArray) { hash.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array()); hash.update(bytes) }
    add(ownDirectory.resolve("HEAD").readBytes())
    add(bytes("for-each-ref", "--format=%(refname) %(objectname) %(symref)"))
    add(indexStamp().toByteArray())
    val status = bytes("--no-optional-locks", "status", "--porcelain=v2", "-z", "--untracked-files=all")
    add(status)
    status.decodeToString().split('\u0000').filter { it.isNotEmpty() }.forEach { entry ->
        val name = when (entry[0]) { '1' -> entry.split(' ', limit = 9).getOrNull(8); '2' -> entry.split(' ', limit = 10).getOrNull(9); 'u' -> entry.split(' ', limit = 11).getOrNull(10); '?' -> entry.drop(2); else -> null }
        if (name != null) add(fileStat(workTree, name).toByteArray())
    }
    ignoredTouchedPaths(intent, emptySet()).sorted().forEach { add(it.toByteArray()); add(fileStat(workTree, it).toByteArray()) }
    intent.interactive?.let { add(it.todo().toByteArray()); add(it.head.toByteArray()) }
    add(bytes("config", "--null", "--list"))
    add(bytes("worktree", "list", "--porcelain"))
    val extra = listOf(ownDirectory.resolve("info/attributes"), commonDirectory.resolve("info/attributes"), commonDirectory.resolve("info/exclude")) +
        listOf("core.attributesfile", "core.excludesfile").mapNotNull { key -> config(key)?.let { File(it.replaceFirst(Regex("^~/"), System.getProperty("user.home") + "/")).let { f -> if (f.isAbsolute) f else workTree.resolve(f.path) } } } +
        File(System.getenv("XDG_CONFIG_HOME") ?: System.getProperty("user.home") + "/.config", "git/attributes")
    extra.distinct().forEach { file -> add(file.absolutePath.toByteArray()); add(if (file.isFile) file.readBytes() else byteArrayOf()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal fun GitCli.planOperation(requested: OperationIntent): OperationPlan {
    requested.interactive?.let { check(requested.kind == PreviewKind.REBASE && requested.switchBranch == null && requested.target == it.base); checkInteractivePlan(it) }
    check(requested.expectedHead == null || requested.expectedHead == headIdentity()) { "Current branch changed after fetch; fetch or choose the integration target again" }
    check(currentOperation() == null) { t("Finish or abort the current operation first", "请先完成或中止当前操作") }
    val target = read("rev-parse", "--verify", "--end-of-options", "${requested.target}^{commit}").trim()
    val intent = requested.copy(target = target)
    val parentCount = read("rev-list", "--parents", "-n", "1", target).trim().split(' ').size - 1
    require(parentCount <= 1 || intent.mainline in 1..parentCount) { "Choose a valid mainline parent" }
    if (intent.kind == PreviewKind.REBASE || intent.switchBranch != null) check(status().isEmpty()) { t("Commit or stash local changes first", "请先提交或储藏本地改动") }
    check(intent.kind != PreviewKind.REBASE || intent.switchBranch != null || raw(listOf("symbolic-ref", "-q", "HEAD")).exitCode == 0) { "Rebase requires a local branch" }
    intent.switchBranch?.let { branch ->
        read("show-ref", "--verify", "refs/heads/$branch")
        val occupied = read("worktree", "list", "--porcelain").split("\n\n").filter { !it.startsWith("worktree ${workTree.canonicalPath}\n") }
        check(occupied.none { "\nbranch refs/heads/$branch\n" in "$it\n" }) { "Branch is checked out in another worktree" }
    }
    val fingerprint = previewFingerprint(intent)
    val headText = ownDirectory.resolve("HEAD").readText().trim()
    val beforeHead = raw(listOf("rev-parse", "--verify", "-q", "HEAD^{commit}")).let { if (it.exitCode == 0) it.text.trim() else "" }
    check(intent.expectedHead == null || intent.expectedHead == "$headText|$beforeHead") { "Current branch changed after fetch" }
    require(beforeHead.matches(Regex("[0-9a-f]{40}"))) { "Preview requires an existing commit" }
    val root = Files.createTempDirectory("gitviz-preview-").toFile()
    try {
        fun git(vararg args: String): GitExecution = GitProcess.execute(root, listOf("-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "protocol.allow=never", "-c", "core.fsmonitor=false") + args,
            environment = mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_NO_LAZY_FETCH" to "1", "GIT_ATTR_NOSYSTEM" to "1"), isolated = true)
        git("init", "-q", "--template=", "--initial-branch=preview").requireSuccess()
        git("config", "user.name", "GitViz Preview").requireSuccess(); git("config", "user.email", "preview@gitviz.invalid").requireSuccess()
        git("config", "core.attributesFile", "/dev/null").requireSuccess()
        git("config", "gc.auto", "0").requireSuccess()
        semanticConfig.forEach { key -> config(key)?.let { git("config", key, it).requireSuccess() } }
        val configText = read("config", "--null", "--list")
        val warnings = mutableListOf<String>()
        if (configText.split('\u0000').any { it.startsWith("filter.") || it.substringBefore('\n').matches(Regex("merge\\..+\\.driver")) || it.startsWith("extensions.partialclone" ) })
            warnings += t("Custom filters, merge drivers or partial-clone configuration: rehearsal cannot fully verify the result.", "存在自定义过滤器、合并驱动或部分克隆配置：演练无法完整验证结果。")
        if (config("core.attributesfile")?.let { it != "/dev/null" } == true || commonDirectory.resolve("info/attributes").isFile || File(System.getenv("XDG_CONFIG_HOME") ?: System.getProperty("user.home") + "/.config", "git/attributes").isFile)
            warnings += t("External attributes are excluded from rehearsal; the result is not fully verified.", "演练未采用外部 attributes，结果未完整验证。")
        if (configText.split('\u0000').any { it.startsWith("branch.") && ".mergeoptions\n" in it }) warnings += t("Branch merge options are excluded from rehearsal.", "演练未采用分支自定义合并参数。")
        val coveredRebase = setOf("rebase.backend", "rebase.rebasemerges", "rebase.updaterefs", "rebase.autostash", "rebase.autosquash")
        val omitted = configText.split('\u0000').map { it.substringBefore('\n') }.filter { key ->
            (key.startsWith("merge.") || key.startsWith("rebase.") || key.startsWith("rerere.")) && key !in semanticConfig && key !in coveredRebase
        }.distinct()
        if (omitted.isNotEmpty()) warnings += t("Settings not rehearsed: ", "演练未采用的设置：") + omitted.joinToString()
        if (listOf("pre-merge-commit", "pre-commit", "commit-msg", "post-commit", "pre-rebase", "post-rewrite", "post-checkout").any { commonDirectory.resolve("hooks/$it").canExecute() } || config("core.hookspath")?.let { it != "/dev/null" } == true)
            warnings += t("Repository hooks are not run in rehearsal.", "演练不会执行仓库 hooks。")
        // Borrow the source objects read-only instead of copying history. New rehearsal objects stay in the sandbox.
        root.resolve(".git/objects/info").mkdirs()
        root.resolve(".git/objects/info/alternates").writeText(commonDirectory.resolve("objects").absolutePath + "\n")
        val refs = read("for-each-ref", "--format=%(objectname) %(refname) %(symref)").lineSequence().filter { it.isNotBlank() }
            .map { it.split(' ', limit = 3) }.filter { it.getOrNull(2).isNullOrBlank() }.joinToString("") { "update ${it[1]} ${it[0]}\n" }
        GitProcess.execute(root, listOf("update-ref", "--stdin"), refs.toByteArray(), isolated = true).requireSuccess()
        if (headText.startsWith("ref: ")) git("symbolic-ref", "HEAD", headText.removePrefix("ref: ")).requireSuccess()
        else git("update-ref", "--no-deref", "HEAD", headText).requireSuccess()
        (listOf(ownDirectory.resolve("index")) + ownDirectory.listFiles().orEmpty().filter { it.name.startsWith("sharedindex.") })
            .filter { it.isFile }.forEach { it.copyTo(root.resolve(".git/${it.name}"), overwrite = true) }
        val sourcePaths = worktreePaths().let { listed -> listed + ignoredTouchedPaths(intent, listed) }
        sourcePaths.forEach { name ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val source = safeWorkingPath(name)
            val path = root.toPath().resolve(name)
            val attrs = runCatching { Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull() ?: return@forEach
            Files.createDirectories(path.parent)
            when {
                attrs.isSymbolicLink -> Files.createSymbolicLink(path, Files.readSymbolicLink(source))
                attrs.isRegularFile -> Files.copy(source, path, NOFOLLOW_LINKS, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        val sandbox = GitCli(root, isolated = true)
        val before = GitRepo.open(root).use { it.loadCommits(200).commits }
        val beforeIndex = git("write-tree").requireSuccess().decodeToString().trim()
        val beforeIndexStamp = sandbox.indexStamp()
        val beforeStat = sourcePaths.associateWith { fileStat(root, it) }
        val switched = intent.switchBranch?.let { git("switch", it) }
        val result = if (switched != null && switched.exitCode != 0) switched else intent.interactive?.let { sandbox.runInteractiveCommands(it) } ?: git(*intent.command(parentCount).toTypedArray())
        val paused = sandbox.currentOperation() != null
        val afterHead = git("rev-parse", "HEAD").requireSuccess().decodeToString().trim()
        val afterIndex = git("write-tree").let { if (it.exitCode == 0) it.text.trim() else null }
        val afterPaths = sandbox.worktreePaths() + sourcePaths
        val afterStat = afterPaths.associateWith { fileStat(root, it) }
        val after = GitRepo.open(root).use { it.loadCommits(200).commits }
        fun ids(vararg args: String) = git(*args).requireSuccess().decodeToString().lineSequence().filter { it.isNotBlank() }.toList()
        val added = ids("rev-list", afterHead, "^$beforeHead")
        val removed = ids("rev-list", beforeHead, "^$afterHead")
        // A stat change alone may be a same-content rewrite; confirm against the untouched source file.
        fun sameContent(path: String) = beforeStat[path]?.startsWith("file ") == true && afterStat[path]?.startsWith("file ") == true &&
            Files.isExecutable(workTree.toPath().resolve(path)) == Files.isExecutable(root.toPath().resolve(path)) &&
            Files.mismatch(safeWorkingPath(path), root.toPath().resolve(path)) == -1L
        val workingImpact = afterPaths.filter { (beforeStat[it] ?: "missing") != afterStat[it] && !sameContent(it) }
        val files = GitRepo.open(root).use { repo -> repo.changesBetween(beforeHead, afterHead).map { PreviewFile("HEAD · ${it.displayPath}", repo.diffText(it)) } }.toMutableList()
        if (afterIndex != null && beforeIndex != afterIndex) GitRepo.open(root).use { repo ->
            files += repo.changesBetween(beforeIndex, afterIndex).map { PreviewFile(t("Index · ", "暂存区 · ") + it.displayPath, repo.diffText(it)) }
        }
        workingImpact.forEach { path ->
            val old = beforeStat[path] ?: "missing"; val new = afterStat.getValue(path)
            fun content(stat: String, file: java.nio.file.Path): ByteArray? = when {
                stat == "missing" -> byteArrayOf()
                stat.startsWith("link ") -> stat.removePrefix("link ").toByteArray()
                stat.startsWith("file ") -> file.takeIf { Files.size(it) <= 1024 * 1024 }?.let(Files::readAllBytes)
                else -> null
            }
            val detail = previewContentDiff(path, content(old, workTree.toPath().resolve(path)), content(new, root.toPath().resolve(path))) +
                "\n${old.substringBefore(' ')} → ${new.substringBefore(' ')}"
            files += PreviewFile(t("Working tree · ", "工作区 · ") + path, detail)
        }
        if (paused) sandbox.status().forEach { f -> files += PreviewFile(f.path, sandbox.diff(f, f.hasStaged && !f.hasUnstaged)) }
        val replayDirectory = root.resolve(".git/rebase-merge")
        val remaining = if (paused && intent.kind == PreviewKind.REBASE) replayDirectory.resolve("git-rebase-todo").takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() && !it.startsWith('#') }.orEmpty() else emptyList()
        val stopped = if (paused && intent.kind == PreviewKind.REBASE) replayDirectory.resolve("done").takeIf { it.isFile }?.readLines()?.lastOrNull { it.startsWith("pick ") || it.startsWith("merge ") } else null
        val projected = if (intent.interactive != null) emptyList() else listOfNotNull(stopped) + remaining
        if (paused && intent.interactive != null) warnings += t("Edited commits after this pause have not been rehearsed; their resulting messages and files are not yet verified.", "暂停点之后的编辑计划尚未演练，后续说明与文件结果未验证。")
        check(fingerprint == previewFingerprint(intent)) { t("Repository changed during preview. Preview again.", "演练期间仓库发生变化，请重新预览。") }
        val addedSet = GitRepo.open(workTree).use { source -> added.filterNot { source.repository.objectDatabase.has(ObjectId.fromString(it)) }.toSet() }
        fun virtual(id: String) = if (id in addedSet) "preview:$id" else id
        val virtualAfter = after.map { c -> c.copy(id = virtual(c.id), parents = c.parents.map(::virtual), graphParents = c.graphParents.map(::virtual), shortMessage = (if (c.id in addedSet) t("[simulated] ", "[模拟] ") else "") + c.shortMessage) }
        val expected = if (projected.isEmpty()) emptyList() else runCatching {
            val labels = git("for-each-ref", "--format=%(refname) %(objectname)", "refs/rewritten/").requireSuccess().decodeToString().lineSequence()
                .filter { it.isNotBlank() }.associate { it.substringBefore(' ').removePrefix("refs/rewritten/") to virtual(it.substringAfter(' ')) }
            GitRepo.open(root).use { repo -> expectedTopology(projected, virtual(afterHead), labels + ("onto" to intent.target), repo::commit) }
        }.getOrElse { warnings += t("Remaining topology could not be projected: ", "无法推导剩余拓扑：") + it.message; emptyList() }
        return OperationPlan(intent, fingerprint, parentCount, before, virtualAfter, beforeHead, virtual(afterHead), added, removed, files,
            workingImpact, beforeIndexStamp != sandbox.indexStamp(), root.resolve(".git/HEAD").readText().trim().let { head -> head.removePrefix("ref: refs/heads/").takeIf { head.startsWith("ref: refs/heads/") } },
            result.result(), warnings, paused, projected, workTree.canonicalPath, expected)
    } finally {
        // GitProcess waits for its children before returning, including interruption. Never delete beneath a live child.
        if (!GitProcess.hasUnfinishedProcess()) deleteTemporaryTree(root.toPath())
    }
}

internal fun GitCli.executePlan(plan: OperationPlan): GitResult = coordinator.execute(plan.intent.request()) {
    plan.intent.interactive?.let { checkInteractivePlan(it) }
    check(plan.repository == workTree.canonicalPath && plan.fingerprint == previewFingerprint(plan.intent)) { t("Preview expired. Preview the operation again.", "预览已过期，请重新预览操作。") }
    val origin = ownDirectory.resolve("HEAD").readText().trim()
    val switched = plan.intent.switchBranch?.let { raw(listOf("switch", it), teach = true).result() }
    if (switched != null && !switched.ok) switched else {
        val result = plan.intent.interactive?.let { runInteractiveCommands(it, teach = true) } ?: raw(plan.intent.command(plan.parentCount), teach = true)
        if (switched != null) rememberSwitchOrigin(origin)
        if (plan.intent.kind == PreviewKind.REBASE) rebaseResult(result) else result.result()
    }
}

private fun previewContentDiff(path: String, before: ByteArray?, after: ByteArray?): String {
    if (before == null || after == null || strictText(before) == null || strictText(after) == null) return t("Binary, directory or large content changed: $path", "二进制、目录或大文件内容改变：$path")
    val old = org.eclipse.jgit.diff.RawText(before)
    val new = org.eclipse.jgit.diff.RawText(after)
    val edits = org.eclipse.jgit.diff.HistogramDiff().diff(org.eclipse.jgit.diff.RawTextComparator.DEFAULT, old, new)
    val output = java.io.ByteArrayOutputStream()
    org.eclipse.jgit.diff.DiffFormatter(output).use { it.format(edits, old, new) }
    return ("--- $path\n+++ $path\n" + output.toString(Charsets.UTF_8)).take(GitRepo.MAX_DIFF_CHARS)
}
