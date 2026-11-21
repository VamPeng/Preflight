package gitviz.git

import gitviz.ui.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Requests carry the actual command, not localized UI strings. */
internal enum class OperationKind { INDEX, COMMIT, BRANCH, TAG, MERGE, CHERRY_PICK, REVERT, RESET, STASH, FETCH, PULL, PUSH, CLONE, FILE, REBASE, OTHER }
internal data class OperationRequest(val kind: OperationKind, val arguments: List<String> = emptyList(), val network: Boolean = false) {
    companion object {
        fun command(args: List<String>, network: Boolean = false): OperationRequest {
            var i = 0
            while (args.getOrNull(i) == "-c") i += 2
            val kind = when (args.getOrNull(i)) {
                "add", "restore", "rm", "apply" -> OperationKind.INDEX
                "commit" -> OperationKind.COMMIT
                "branch", "switch", "checkout" -> OperationKind.BRANCH
                "tag" -> OperationKind.TAG
                "merge" -> OperationKind.MERGE
                "cherry-pick" -> OperationKind.CHERRY_PICK
                "revert" -> OperationKind.REVERT
                "reset" -> OperationKind.RESET
                "stash" -> OperationKind.STASH
                "fetch" -> OperationKind.FETCH
                "pull" -> OperationKind.PULL
                "push" -> OperationKind.PUSH
                "clone" -> OperationKind.CLONE
                "rebase" -> OperationKind.REBASE
                "clean" -> OperationKind.FILE
                else -> OperationKind.OTHER
            }
            return OperationRequest(kind, args, network)
        }
    }
}

/** Serializes writes per repository (shared by linked worktrees) and across Preflight processes. No snapshots are taken. */
internal class OperationCoordinator(private val cli: GitCli) {
    fun execute(request: OperationRequest, action: () -> GitResult): GitResult {
        val key = if (request.kind == OperationKind.CLONE) cli.workTree.canonicalPath else cli.commonDirectory.canonicalPath
        return locks.computeIfAbsent(key) { ReentrantLock() }.withLock { withLease(key, request.kind) {
            if (GitProcess.hasUnfinishedProcess()) return@withLease GitResult(GitFailure.UNKNOWN, t("A previous Git process has not stopped. Refresh after it exits.", "先前的 Git 进程尚未结束，请等待后刷新。"))
            attemptGitOperation(action)
        } }
    }
    private fun withLease(key: String, kind: OperationKind, action: () -> GitResult): GitResult {
        if (kind == OperationKind.CLONE || key in leases.get()) return action()
        return attemptGitOperation {
            // 沿用旧版锁文件，确保改名前后的应用同时打开仓库时仍互斥。
            java.nio.channels.FileChannel.open(cli.commonDirectory.resolve("gitviz-operation.lock").toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                val lease = channel.tryLock() ?: return@attemptGitOperation GitResult(GitFailure.OTHER, t("Another Preflight process is changing this repository", "另一个 Preflight 进程正在修改此仓库"))
                lease.use {
                    leases.get().add(key)
                    try { action() } finally { leases.get().remove(key) }
                }
            }
        }
    }
    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        private val leases = ThreadLocal.withInitial { HashSet<String>() }
    }
}
