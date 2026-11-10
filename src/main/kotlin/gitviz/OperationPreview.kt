package gitviz

internal enum class PreviewKind { MERGE, REBASE, CHERRY_PICK, RESET }
internal data class OperationIntent(val kind: PreviewKind, val target: String, val mode: ResetMode = ResetMode.MIXED,
    val mainline: Int = 1, val switchBranch: String? = null, val ffOnly: Boolean = false, val expectedHead: String? = null,
    val interactive: InteractiveRebasePlan? = null) {
    fun command(parentCount: Int, target: String = this.target): List<String> = when (kind) {
        PreviewKind.MERGE -> listOf("merge", "--no-edit", "--no-overwrite-ignore", "--no-autostash") + (if (ffOnly) listOf("--ff-only") else emptyList()) + target
        PreviewKind.REBASE -> listOf("-c", "core.editor=true", "rebase") + (if (interactive == null) rebaseFlags else interactiveRebaseFlags) + target
        PreviewKind.CHERRY_PICK -> listOf("cherry-pick") + (if (parentCount > 1) listOf("-m", mainline.toString()) else emptyList()) + target
        PreviewKind.RESET -> listOf("reset", mode.flag, target)
    }
    fun request() = OperationRequest(OperationKind.valueOf(kind.name), switchBranch?.let { listOf("switch", it, "then") }.orEmpty() + listOf(kind.name.lowercase(), target))
}

internal enum class FactTone { NOTE, GOOD, WARN, DANGER }
internal data class PlanFact(val text: String, val tone: FactTone = FactTone.NOTE)

/**
 * What the confirmation dialog shows before an operation runs. Computed with read-only commands: nothing is rehearsed
 * and no file is copied. [intent] carries the target resolved to a commit ID, so the commit the user confirmed is the
 * one that runs; [requested] is the name they picked, kept so a merge message can still say "Merge branch 'x'".
 * [conflicts] is null when conflicts were not predicted, empty when none are expected.
 */
internal data class OperationPlan(val intent: OperationIntent, val requested: String, val parentCount: Int,
    val facts: List<PlanFact>, val commits: Int, val conflicts: List<String>?, val discarded: List<String> = emptyList())

/** Preconditions Git does not enforce by itself, or reports poorly. Everything else is left to Git. */
private fun GitCli.checkIntent(intent: OperationIntent) {
    intent.interactive?.let { check(intent.kind == PreviewKind.REBASE && intent.switchBranch == null); checkInteractivePlan(it) }
    check(intent.expectedHead == null || intent.expectedHead == headIdentity()) { t("Current branch changed. Start the operation again.", "当前分支已变化，请重新发起操作。") }
    check(currentOperation() == null) { t("Finish or abort the current operation first", "请先完成或中止当前操作") }
    if (intent.kind == PreviewKind.REBASE || intent.switchBranch != null) check(status().isEmpty()) { t("Commit or stash local changes first", "请先提交或储藏本地改动") }
    check(intent.kind != PreviewKind.REBASE || intent.switchBranch != null || raw(listOf("symbolic-ref", "-q", "HEAD")).exitCode == 0) { t("Rebase requires a local branch", "变基需要先切换到本地分支") }
}

/** Conflicted paths of a three-way merge computed without a working tree (Git 2.38+). Null when it cannot be predicted. */
private fun GitCli.predictConflicts(ours: String, theirs: String, base: String? = null): List<String>? {
    val result = raw(listOf("merge-tree", "--write-tree", "--name-only", "--no-messages", "-z") + listOfNotNull(base?.let { "--merge-base=$it" }) + listOf(ours, theirs))
    return when {
        result.uncertain -> null
        result.exitCode == 0 -> emptyList()
        result.exitCode == 1 -> result.text.split('\u0000').drop(1).filter { it.isNotEmpty() }.distinct()
        else -> null
    }
}

private fun conflictFacts(conflicts: List<String>?): List<PlanFact> = when {
    conflicts == null -> listOf(PlanFact(t("Conflicts could not be predicted", "无法预判是否冲突")))
    conflicts.isEmpty() -> listOf(PlanFact(t("No conflicts expected", "预计没有冲突"), FactTone.GOOD))
    else -> listOf(
        PlanFact(t("Expected conflicts: ${countOf(conflicts.size, "file")} · ", "预计冲突：${conflicts.size} 个文件 · ") + conflicts.take(3).joinToString() + if (conflicts.size > 3) " …" else "", FactTone.WARN),
        PlanFact(t("The operation stops at conflicts; resolve them under “Resolve conflicts” or abort.", "冲突时会停下，到「解决冲突」里处理或放弃")))
}

internal fun GitCli.planOperation(requested: OperationIntent): OperationPlan {
    checkIntent(requested)
    val target = read("rev-parse", "--verify", "--end-of-options", "${requested.target}^{commit}").trim()
    val intent = requested.copy(target = target)
    val parentCount = read("rev-list", "--parents", "-n", "1", target).trim().split(' ').size - 1
    require(parentCount <= 1 || intent.mainline in 1..parentCount) { t("Choose a valid mainline parent", "请选择有效的主父提交") }
    // The branch the operation acts on: the dragged-to branch when GitViz switches first, otherwise HEAD.
    val ours = read("rev-parse", "--verify", "--end-of-options", (intent.switchBranch?.let { "refs/heads/$it" } ?: "HEAD") + "^{commit}").trim()
    fun count(range: String) = read("rev-list", "--count", range).trim().toInt()
    fun plan(facts: List<PlanFact>, commits: Int, conflicts: List<String>? = null, discarded: List<String> = emptyList()) =
        OperationPlan(intent, requested.target, parentCount, facts, commits, conflicts, discarded)
    return when (intent.kind) {
        PreviewKind.MERGE -> {
            val incoming = count("$ours..$target")
            val fastForward = raw(listOf("merge-base", "--is-ancestor", ours, target)).exitCode == 0
            when {
                incoming == 0 -> plan(listOf(PlanFact(t("Already up to date; nothing to merge", "已是最新，没有需要合并的提交"))), 0, emptyList())
                fastForward -> plan(listOf(
                    PlanFact(if (config("merge.ff") == "false") t("Brings in ${countOf(incoming, "commit")} and creates a merge commit", "带入 $incoming 个提交，会生成一个合并提交")
                        else t("Brings in ${countOf(incoming, "commit")}; fast-forward, no merge commit", "带入 $incoming 个提交，可以快进，不会生成合并提交")),
                    PlanFact(t("No conflicts expected", "预计没有冲突"), FactTone.GOOD)), incoming, emptyList())
                intent.ffOnly -> plan(listOf(PlanFact(t("Histories have diverged; a fast-forward-only pull will be refused", "历史已分叉，「仅快进」会被拒绝"), FactTone.DANGER)), incoming)
                else -> predictConflicts(ours, target).let { conflicts ->
                    plan(listOf(PlanFact(t("Brings in ${countOf(incoming, "commit")} and creates a merge commit", "带入 $incoming 个提交，会生成一个合并提交"))) + conflictFacts(conflicts), incoming, conflicts)
                }
            }
        }
        PreviewKind.CHERRY_PICK -> {
            // Cherry-pick is a merge whose base is the picked commit's parent.
            val base = raw(listOf("rev-parse", "--verify", "-q", "$target^${if (parentCount > 1) intent.mainline else 1}")).let { if (it.exitCode == 0) it.text.trim() else null }
            val conflicts = base?.let { predictConflicts(ours, target, it) }
            plan(conflictFacts(conflicts), 1, conflicts)
        }
        PreviewKind.REBASE -> {
            val replayed = intent.interactive?.commits?.size ?: count("$target..$ours")
            plan(listOf(
                PlanFact(t("Replays ${countOf(replayed, "commit")}; their hashes will change", "重放 $replayed 个提交，它们的哈希会改变")),
                PlanFact(t("Conflicts are not predicted; the rebase pauses on a conflict and can be continued, skipped or aborted", "不预判冲突；遇到冲突会暂停，可以继续、跳过或放弃"))), replayed)
        }
        PreviewKind.RESET -> {
            val leaving = count("$target..$ours")
            val changed = status().filterNot { it.untracked }
            val discarded = if (intent.mode == ResetMode.HARD) changed.map { it.path } else emptyList()
            plan(listOfNotNull(
                if (leaving > 0) PlanFact(t("${countOf(leaving, "commit")} will leave the branch and can then only be recovered from the reflog", "$leaving 个提交会离开当前分支，之后只能从 reflog 找回"))
                else PlanFact(t("No commits leave the branch", "没有提交离开当前分支")),
                if (intent.mode == ResetMode.MIXED && changed.any { it.hasStaged }) PlanFact(t("Staged changes become unstaged", "已暂存的改动会变回未暂存")) else null,
                if (discarded.isNotEmpty()) PlanFact(t("Uncommitted changes to ${countOf(discarded.size, "file")} will be discarded and cannot be recovered", "工作区有 ${discarded.size} 个文件的未提交改动会被丢弃，无法恢复"), FactTone.DANGER) else null,
            ), leaving, discarded = discarded)
        }
    }
}

internal fun GitCli.executePlan(plan: OperationPlan): GitResult = coordinator.execute(plan.intent.request()) {
    val intent = plan.intent
    checkIntent(intent)
    // A merge by name gives a readable message ("Merge branch 'x'"), but only while the name still means the confirmed commit.
    val named = intent.kind == PreviewKind.MERGE && plan.requested != intent.target
    fun stillConfirmed(name: String) = raw(listOf("rev-parse", "--verify", "-q", "--end-of-options", "$name^{commit}")).text.trim() == intent.target
    if (named) check(stillConfirmed(plan.requested)) { t("The target moved after you confirmed. Start the operation again.", "确认之后目标已变化，请重新发起操作。") }
    // Git writes the name as given, so prefer the short form unless a tag or other ref makes it ambiguous.
    val mergeName = targetLabel(plan.requested).takeIf { named && stillConfirmed(it) } ?: plan.requested
    val origin = ownDirectory.resolve("HEAD").readText().trim()
    val switched = intent.switchBranch?.let { raw(listOf("switch", it), teach = true).result() }
    if (switched != null && !switched.ok) switched else {
        val result = intent.interactive?.let { runInteractiveCommands(it, teach = true) }
            ?: raw(intent.command(plan.parentCount, if (named) mergeName else intent.target), teach = true)
        if (switched != null) rememberSwitchOrigin(origin)
        if (intent.kind == PreviewKind.REBASE) rebaseResult(result) else result.result()
    }
}
