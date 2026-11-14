package gitviz.ui.history

import gitviz.git.*
import gitviz.ui.*
import java.io.File

// 原「仓库工具」页里的工作树和子模块，收进侧边栏的附加分组。只有一个工作树、没有子模块时不出现。

internal fun worktreeGroup(
    trees: List<WorktreeInfo>,
    workTree: File,
    actions: GitActions,
    onOpen: (File) -> Unit,
    onReveal: (File) -> Unit,
    onMenu: (List<MenuEntry>) -> Unit,
): SidebarGroup? {
    val shown = trees.filterNot { it.bare }
    if (shown.size < 2) return null
    val here = workTree.canonicalFile
    return SidebarGroup("worktrees", t("Worktrees", "工作树"), shown.map { tree ->
        val dir = File(tree.path)
        val current = dir.canonicalFile == here
        val missing = tree.prunable != null || !dir.isDirectory
        val canOpen = !current && !missing
        val detail = when {
            missing -> t("Missing", "目录缺失")
            tree.locked != null -> t("Locked", "已锁定")
            tree.branch != null -> tree.branch.removePrefix("refs/heads/")
            else -> t("Detached ${tree.head?.take(7).orEmpty()}", "分离 ${tree.head?.take(7).orEmpty()}")
        }
        val hint = tree.path + when {
            current -> t(" (this window)", "（当前窗口）")
            missing -> t(" · directory is gone; run git worktree prune to clear the record", " · 目录已不存在，可在终端运行 git worktree prune 清理记录")
            else -> t(" · double-click to open", " · 双击打开")
        }
        val menu = listOf(
            MenuItem(t("Open", "打开"), enabled = canOpen) { onOpen(dir) },
            MenuItem(t(if (isMac) "Show in Finder" else "Show in folder", if (isMac) "在 Finder 中显示" else "在文件夹中显示"), enabled = !missing) { onReveal(dir) },
            MenuItem(t("Copy path", "复制路径")) { actions.copy(tree.path) },
            MenuSeparator,
            MenuItem(t("Remove worktree…", "移除工作树…"), enabled = !current && !tree.main && tree.locked == null && !missing, danger = true) {
                actions.ask(DialogSpec(t("Remove worktree", "移除工作树"),
                    t("Delete the directory ${tree.path}. The branch stays. Git refuses if the directory has changes or ignored files.",
                        "删除目录 ${tree.path}，分支保留。目录里有改动或被忽略的文件时会拒绝。"),
                    t("Remove", "移除"), danger = true) { _, _ ->
                    actions.run(t("Removing worktree", "正在移除工作树"), t("Worktree removed", "已移除工作树")) { removeWorktree(tree) }
                })
            },
        )
        SidebarEntry(tree.path, dir.name, detail, hint, current = current, warning = missing,
            onOpen = { if (canOpen) onOpen(dir) }, onMenu = { onMenu(menu) })
    })
}

internal fun submoduleGroup(
    modules: List<SubmoduleInfo>,
    cli: GitCli,
    actions: GitActions,
    onOpen: (File) -> Unit,
    onReveal: (File) -> Unit,
    onMenu: (List<MenuEntry>) -> Unit,
): SidebarGroup? {
    if (modules.isEmpty()) return null
    return SidebarGroup("submodules", t("Submodules", "子模块"), modules.map { item ->
        val dir = runCatching { cli.safeWorkingPath(item.path).toFile() }.getOrNull()
        val canOpen = dir != null && item.head != null && item.problem == null
        val outdated = item.head != null && item.target != null && item.head != item.target
        val detail = when {
            item.problem != null -> t("Problem", "异常")
            item.head == null -> t("Not initialized", "未初始化")
            item.dirty -> t("Changed", "有改动")
            outdated -> t("Needs update", "待更新")
            else -> item.head.take(7)
        }
        val hint = listOfNotNull(item.url, item.problem, if (outdated) t("Recorded ${item.target?.take(7)}, checked out ${item.head?.take(7)}", "记录的是 ${item.target?.take(7)}，检出的是 ${item.head?.take(7)}") else null).joinToString("\n")
        val update = item.head == null || outdated
        val menu = listOf(
            MenuItem(if (item.head == null) t("Initialize and update…", "初始化并更新…") else t("Update to recorded commit…", "更新到记录的提交…"), enabled = item.ready && update) {
                actions.ask(DialogSpec(t("Update submodule", "更新子模块"),
                    "${item.path}\n${item.head?.take(12) ?: "—"} → ${item.target?.take(12)}\n" +
                        t("Checks out the commit recorded by this repository. May download from the submodule's remote. Local changes and ignored files are protected; nested submodules are not updated.",
                            "检出本仓库记录的提交，可能从子模块的远程下载。本地改动和被忽略的文件受保护；不更新嵌套的子模块。"),
                    t("Update", "更新")) { _, _ ->
                    actions.run(t("Updating submodule", "正在更新子模块"), t("Submodule updated", "已更新子模块")) { updateSubmodule(item) }
                })
            },
            MenuItem(t("Open", "打开"), enabled = canOpen) { dir?.let(onOpen) },
            MenuItem(t(if (isMac) "Show in Finder" else "Show in folder", if (isMac) "在 Finder 中显示" else "在文件夹中显示"), enabled = dir?.isDirectory == true) { dir?.let(onReveal) },
            MenuItem(t("Copy URL", "复制地址"), enabled = item.url.isNotBlank()) { actions.copy(item.url) },
        )
        SidebarEntry(item.path, item.path, detail, hint, warning = item.problem != null || item.dirty || outdated,
            onOpen = { if (canOpen) dir?.let(onOpen) }, onMenu = { onMenu(menu) })
    })
}

/**
 * 分支右键「在新工作树中检出…」。目录默认放在当前工作树旁边。
 * remote 为 true 时 branch 是远程分支名（origin/release）：本地有同名分支就检出它，没有就新建一个跟踪它的本地分支。
 */
internal fun worktreeCheckoutItem(branch: String, isCurrent: Boolean, trees: List<WorktreeInfo>, workTree: File, actions: GitActions, remote: Boolean = false): MenuItem {
    val checkedOut = !remote && trees.any { it.branch == "refs/heads/$branch" }
    return MenuItem(t("Check out in new worktree…", "在新工作树中检出…"), enabled = !isCurrent && !checkedOut) {
        val parent = workTree.canonicalFile.parentFile
        val local = if (remote) branch.substringAfter('/') else branch
        val stem = "${workTree.name}-${local.replace('/', '-')}"
        val suggested = generateSequence(1) { it + 1 }.map { if (it == 1) File(parent, stem) else File(parent, "$stem-$it") }.first { !it.exists() }
        actions.ask(DialogSpec(t("Check out in new worktree", "在新工作树中检出"),
            (if (remote) t("Check out $branch as a local branch in another directory. ", "把 $branch 作为本地分支检出到另一个目录。")
                else t("Check out $branch in another directory. ", "把 $branch 检出到另一个目录。")) +
                t("Both can be open and changed at the same time, sharing one history.", "两边可以同时打开、各自改动，共用同一份提交历史。"),
            t("Create", "创建"), input = t("Directory", "目录"), initial = suggested.path) { text, _ ->
            val path = expandHome(text.trim())
            actions.run(t("Creating worktree", "正在创建工作树"), t("Created worktree ${File(path).name}", "已创建工作树 ${File(path).name}")) {
                if (remote) addRemoteWorktree(path, branch) else addWorktree(path, branch, newBranch = false)
            }
        })
    }
}

private fun expandHome(path: String): String =
    if (path == "~" || path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path
