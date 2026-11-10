package gitviz

internal enum class PullStrategy { MERGE, REBASE, FF_ONLY }
internal data class PullSettings(val strategy: PullStrategy, val remote: String, val branch: String, val setUpstream: Boolean, val expectedHead: String? = null, val expectedBranch: String? = null)
internal data class PullOptions(val strategy: PullStrategy, val remotes: List<String>, val current: String,
    val upstreamRemote: String?, val upstreamBranch: String?, val branches: Map<String, List<String>>, val head: String)
internal data class PreparedPull(val result: GitResult, val intent: OperationIntent?)

internal fun GitCli.pullOptions(): PullOptions {
    val current = read("symbolic-ref", "--short", "HEAD").trim()
    val remotes = read("remote").lines().filter { it.isNotBlank() }
    val remote = config("branch.$current.remote")
    val branch = config("branch.$current.merge")?.removePrefix("refs/heads/")
    val refs = read("for-each-ref", "--format=%(refname)", "refs/remotes/").lines()
    val branches = remotes.associateWith { name ->
        val specs = raw(listOf("config", "--get-all", "remote.$name.fetch")).text.lines().filter { it.isNotBlank() && !it.startsWith('^') }
            .mapNotNull { runCatching { org.eclipse.jgit.transport.RefSpec(it) }.getOrNull() }
        refs.filter { !it.endsWith("/HEAD") }.flatMap { ref -> specs.filter { it.destination != null && it.matchDestination(ref) }
            .mapNotNull { it.expandFromDestination(ref).source?.takeIf { source -> source.startsWith("refs/heads/") }?.removePrefix("refs/heads/") } }.distinct().sorted()
    }
    val strategy = runCatching { PullStrategy.valueOf(config("gitviz.pullstrategy").orEmpty()) }.getOrDefault(PullStrategy.MERGE)
    return PullOptions(strategy, remotes + listOfNotNull(remote?.takeIf { it == "." }), current, remote, branch, branches, headIdentity().also { check(it.startsWith("ref: refs/heads/$current|")) { "Current branch changed" } })
}

/** Fetches the chosen branch and returns the integration to run on the fetched commit. A failed integration keeps the fetched refs. */
internal fun GitCli.preparePull(settings: PullSettings): PreparedPull {
    var intent: OperationIntent? = null
    val result = coordinator.execute(OperationRequest(OperationKind.FETCH, listOf("fetch", settings.remote), network = true)) {
        check(currentOperation() == null) { "Finish the current Git operation first" }
        val current = read("symbolic-ref", "--short", "HEAD").trim()
        val sourceHead = headIdentity()
        check((settings.expectedHead == null || settings.expectedHead == sourceHead) && (settings.expectedBranch == null || settings.expectedBranch == current)) { "Pull dialog expired: current branch changed" }
        check(settings.remote == "." || settings.remote in read("remote").lines()) { "Unknown remote" }
        check(raw(listOf("check-ref-format", "refs/heads/${settings.branch}")).exitCode == 0) { "Invalid branch" }
        val source = "refs/heads/${settings.branch}"
        val specs = raw(listOf("config", "--get-all", "remote.${settings.remote}.fetch")).text.lines().filter { it.isNotBlank() && !it.startsWith('^') }
        val mapped = specs.mapNotNull { text -> runCatching { org.eclipse.jgit.transport.RefSpec(text) }.getOrNull() }
            .firstOrNull { it.matchSource(source) && it.destination != null }?.expandFromSource(source)?.destination
        val tracking = if (settings.remote == ".") source else mapped ?: "refs/remotes/${settings.remote}/${settings.branch}"
        check(settings.remote == "." || tracking.startsWith("refs/remotes/")) { "Pull requires a remote-tracking fetch destination" }
        val fetched = if (settings.remote == ".") GitResult(null, "Local upstream") else execute(OperationRequest.command(listOf("fetch", "--no-tags", "--", settings.remote, "+$source:$tracking"), network = true))
        if (!fetched.ok) return@execute fetched
        check(sourceHead == headIdentity()) { "Fetch completed, but current branch changed; integration was not started" }
        val oid = read("rev-parse", "--verify", "--end-of-options", "$tracking^{commit}").trim()
        // Remember the chosen strategy and upstream as part of the fetch step.
        raw(listOf("config", "--local", "gitviz.pullstrategy", settings.strategy.name), teach = true).requireSuccess()
        if (settings.setUpstream) {
            if (settings.remote != "." && mapped == null) raw(listOf("config", "--local", "--add", "remote.${settings.remote}.fetch", "+$source:$tracking"), teach = true).requireSuccess()
            raw(listOf("config", "--local", "branch.$current.remote", settings.remote), teach = true).requireSuccess()
            raw(listOf("config", "--local", "branch.$current.merge", "refs/heads/${settings.branch}"), teach = true).requireSuccess()
        }
        intent = OperationIntent(if (settings.strategy == PullStrategy.REBASE) PreviewKind.REBASE else PreviewKind.MERGE, oid, ffOnly = settings.strategy == PullStrategy.FF_ONLY, expectedHead = sourceHead)
        fetched
    }
    return PreparedPull(result, intent.takeIf { result.ok })
}

/** Pull in one step: fetch the chosen branch, then merge, rebase or fast-forward onto exactly what was fetched. */
internal fun GitCli.pull(settings: PullSettings): GitResult = coordinator.execute(OperationRequest(OperationKind.PULL, listOf("pull", settings.remote, settings.branch), network = true)) {
    val prepared = preparePull(settings)
    val intent = prepared.intent ?: return@execute prepared.result
    val plan = planOperation(intent)
    if (plan.commits == 0 && intent.kind == PreviewKind.MERGE) return@execute GitResult(null, t("Already up to date.", "已是最新。"))
    val integrated = executePlan(plan)
    integrated.copy(output = listOf(prepared.result.output, integrated.output).filter { it.isNotBlank() }.joinToString("\n"))
}

/** Saved pull choice for the current branch, or null when it has no upstream yet and the user must choose one. */
internal fun GitCli.savedPull(): PullSettings? {
    val options = pullOptions()
    return PullSettings(options.strategy, options.upstreamRemote ?: return null, options.upstreamBranch ?: return null, false, options.head, options.current)
}

internal fun GitCli.headIdentity(): String = ownDirectory.resolve("HEAD").readText().trim() + "|" + read("rev-parse", "HEAD").trim()
