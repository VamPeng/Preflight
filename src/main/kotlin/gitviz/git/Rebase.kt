package gitviz.git

import gitviz.service.*
import gitviz.ui.*

internal val rebaseFlags = listOf("--merge", "--rebase-merges=no-rebase-cousins", "--no-fork-point",
    "--no-update-refs", "--no-autostash", "--no-autosquash", "--keep-empty", "--no-reapply-cherry-picks", "--empty=stop")

internal data class RebaseProgress(val step: Int?, val total: Int?, val commit: String?, val canKeepEmpty: Boolean)

internal fun GitCli.rebaseProgress(): RebaseProgress? {
    if (currentOperation() != GitOperation.REBASE) return null
    val dir = ownDirectory.resolve("rebase-merge").takeIf { it.isDirectory } ?: ownDirectory.resolve("rebase-apply")
    fun value(name: String) = dir.resolve(name).takeIf { it.isFile }?.readText()?.trim()
    val oid = raw(listOf("rev-parse", "--verify", "REBASE_HEAD")).let { if (it.exitCode == 0) it.text.trim() else null }
        ?: value("stopped-sha") ?: value("original-commit")
    val cleanIndex = raw(listOf("diff", "--cached", "--quiet")).exitCode == 0
    return RebaseProgress((value("msgnum") ?: value("next"))?.toIntOrNull(), (value("end") ?: value("last"))?.toIntOrNull(),
        oid, oid != null && cleanIndex && status().none { it.conflicted || it.hasUnstaged })
}

internal fun GitCli.rebase(target: String): GitResult = coordinator.execute(OperationRequest(OperationKind.REBASE, listOf("rebase", target))) {
    check(currentOperation() == null) { t("Finish the current operation first", "请先完成当前操作") }
    check(raw(listOf("symbolic-ref", "-q", "HEAD")).exitCode == 0) { t("Choose a local branch first", "请先切换到本地分支") }
    check(status().isEmpty()) { t("Commit or stash local changes before rebasing", "请先提交或储藏本地改动，再变基") }
    rebaseResult(raw(listOf("-c", "core.editor=true", "rebase") + rebaseFlags + target, teach = true))
}

internal fun GitCli.rebaseStep(step: String): GitResult = coordinator.execute(OperationRequest(OperationKind.REBASE, listOf("rebase", step))) {
    check(currentOperation() == GitOperation.REBASE) { "No rebase in progress" }
    check(step in setOf("--continue", "--skip", "--abort"))
    rebaseResult(raw(listOf("-c", "core.editor=true", "rebase", step), teach = true))
}

internal fun GitCli.keepEmptyRebaseCommit(): GitResult = coordinator.execute(OperationRequest(OperationKind.REBASE, listOf("rebase", "keep-empty"))) {
    val progress = rebaseProgress()
    check(progress?.canKeepEmpty == true) { t("The paused commit is no longer empty. Refresh first.", "暂停的提交已不为空，请先刷新。") }
    val committed = raw(listOf("-c", "core.editor=true", "commit", "--allow-empty", "--no-edit", "-C", checkNotNull(progress.commit)), teach = true).result()
    if (!committed.ok) committed else rebaseStep("--continue")
}

internal fun GitCli.rebaseResult(execution: GitExecution): GitResult {
    val result = readableRebaseExecution(execution).result()
    return if (result.failure != GitFailure.UNKNOWN && !result.ok && currentOperation() == GitOperation.REBASE && status().none { it.conflicted })
        result.copy(failure = GitFailure.PAUSED) else result
}

/** Stable for one native operation, including after a Preflight restart. Never join an unrelated later session. */
internal fun GitCli.operationIdentity(): String? {
    val operation = currentOperation() ?: return null
    val control = when (operation) {
        GitOperation.REBASE -> ownDirectory.resolve("rebase-merge").takeIf { it.isDirectory } ?: ownDirectory.resolve("rebase-apply")
        GitOperation.MERGE -> ownDirectory.resolve("MERGE_HEAD")
        GitOperation.CHERRY_PICK -> ownDirectory.resolve("CHERRY_PICK_HEAD")
        GitOperation.REVERT -> ownDirectory.resolve("REVERT_HEAD")
    }
    val attrs = java.nio.file.Files.readAttributes(control.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
    val data = if (control.isDirectory) listOf("orig-head", "head-name", "onto").joinToString("|") { name ->
        control.resolve(name).takeIf { it.isFile }?.readText().orEmpty().trim()
    } else control.readText().trim()
    return "${operation.name}|${attrs.fileKey()}|${attrs.creationTime()}|$data"
}

/** Drafts belong to a native operation and, during rebase, the particular stopped commit. */
internal fun GitCli.conflictSession(): String? = operationIdentity()?.let { identity ->
    if (currentOperation() == GitOperation.REBASE) "$identity|${rebaseProgress()?.commit}" else identity
}
