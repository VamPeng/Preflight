package gitviz.ui.history

import gitviz.*
import gitviz.git.*
import gitviz.ui.*
import java.io.File
import kotlin.test.*

class SidebarGroupsTest {
    /** 记下菜单要弹的对话框、要执行的操作，测试里再手动确认、执行。 */
    private class Recorder {
        val dialogs = mutableListOf<DialogSpec>()
        val ops = mutableListOf<GitCli.() -> GitResult>()
        val copied = mutableListOf<String>()
        val actions = GitActions(ask = { dialogs += it }, run = { _, _, op -> ops += op }, copy = { copied += it })
    }
    private fun List<MenuEntry>.item(label: String) = filterIsInstance<MenuItem>().single { it.label == label }
    private fun withChinese(block: () -> Unit) {
        val language = Settings.language
        Settings.language = Language.ZH
        try { block() } finally { Settings.language = language }
    }

    @Test fun `worktree group only appears with a second worktree and marks current and missing`() = withChinese {
        val base = java.nio.file.Files.createTempDirectory("gitviz-sidebar-").toFile()
        try {
            val here = File(base, "repo").apply { mkdirs() }
            val lockedDir = File(base, "locked").apply { mkdirs() }
            val main = WorktreeInfo(here.path, "a".repeat(40), "refs/heads/main", false, null, null, main = true)
            assertNull(worktreeGroup(listOf(main), here, Recorder().actions, {}, {}, {}))
            val gone = WorktreeInfo(File(base, "gone").path, "b".repeat(40), "refs/heads/old", false, null, "gitdir file points to non-existent location")
            val locked = WorktreeInfo(lockedDir.path, "c".repeat(40), null, false, "in use", null)
            var menu: List<MenuEntry> = emptyList()
            val group = assertNotNull(worktreeGroup(listOf(main, gone, locked), here, Recorder().actions, {}, {}, { menu = it }))
            assertEquals(listOf("repo", "gone", "locked"), group.entries.map { it.name })
            assertTrue(group.entries[0].current)
            assertEquals("目录缺失", group.entries[1].detail); assertTrue(group.entries[1].warning)
            assertEquals("已锁定", group.entries[2].detail)
            group.entries[0].onMenu()
            assertFalse(menu.item("打开").enabled); assertFalse(menu.item("移除工作树…").enabled)
            group.entries[1].onMenu()
            assertFalse(menu.item("打开").enabled, "missing directory cannot be opened")
            assertFalse(menu.item("移除工作树…").enabled, "missing directory is left to git worktree prune")
            assertTrue(menu.item("复制路径").enabled)
            group.entries[2].onMenu()
            assertTrue(menu.item("打开").enabled, "a locked worktree can still be opened")
            assertFalse(menu.item("移除工作树…").enabled, "a locked worktree cannot be removed")
        } finally { deleteTemporaryTree(base.toPath()) }
    }

    @Test fun `check out in new worktree suggests a free sibling directory and removal goes through git`() = RepositoryFixture().use { r ->
        withChinese {
            r.commit(); r.git("branch", "feature/login")
            val taken = File(r.root.parentFile, "${r.root.name}-feature-login").apply { mkdirs() }
            val created = File(r.root.parentFile, "${r.root.name}-feature-login-2")
            try {
                val rec = Recorder()
                assertFalse(worktreeCheckoutItem("main", isCurrent = true, r.cli.worktrees(), r.root, rec.actions).enabled)
                val item = worktreeCheckoutItem("feature/login", isCurrent = false, r.cli.worktrees(), r.root, rec.actions)
                assertTrue(item.enabled)
                item.action()
                val dialog = rec.dialogs.single()
                assertEquals(created.canonicalPath, File(dialog.initial).canonicalPath, "existing directory is skipped")
                dialog.onConfirm(dialog.initial, ResetMode.MIXED)
                assertTrue(rec.ops.single()(r.cli).ok)
                val trees = r.cli.worktrees()
                assertTrue(trees.any { it.branch == "refs/heads/feature/login" })
                assertFalse(worktreeCheckoutItem("feature/login", isCurrent = false, trees, r.root, rec.actions).enabled, "a branch can only be checked out once")

                var menu: List<MenuEntry> = emptyList()
                val group = assertNotNull(worktreeGroup(trees, r.root, rec.actions, {}, {}, { menu = it }))
                val entry = group.entries.single { !it.current }
                assertEquals("feature/login", entry.detail)
                entry.onMenu()
                menu.item("移除工作树…").action()
                rec.dialogs.last().onConfirm("", ResetMode.MIXED)
                assertTrue(rec.ops.last()(r.cli).ok)
                assertFalse(created.exists())
                assertEquals("feature/login", r.git("branch", "--list", "feature/login").trim().removePrefix("* ").trim(), "branch is kept")
            } finally {
                deleteTemporaryTree(taken.toPath())
                if (created.exists()) deleteTemporaryTree(created.toPath())
            }
        }
    }

    @Test fun `submodule states drive detail text and update availability`() = withChinese {
        RepositoryFixture().use { r ->
            r.commit()
            val target = "d".repeat(40)
            fun info(head: String?, dirty: Boolean = false, problem: String? = null) = SubmoduleInfo("lib", "vendor/lib", "https://example.invalid/lib.git", target, head, dirty, problem)
            fun check(module: SubmoduleInfo, detail: String, update: Boolean, open: Boolean) {
                var menu: List<MenuEntry> = emptyList()
                val entry = assertNotNull(submoduleGroup(listOf(module), r.cli, Recorder().actions, {}, {}, { menu = it })).entries.single()
                assertEquals(detail, entry.detail)
                entry.onMenu()
                val updateItem = menu.filterIsInstance<MenuItem>().first()
                assertEquals(update, updateItem.enabled, detail)
                assertEquals(open, menu.item("打开").enabled, detail)
            }
            assertNull(submoduleGroup(emptyList(), r.cli, Recorder().actions, {}, {}, {}))
            check(info(null), "未初始化", update = true, open = false)
            check(info(target), target.take(7), update = false, open = true)
            check(info("e".repeat(40)), "待更新", update = true, open = true)
            check(info("e".repeat(40), dirty = true), "有改动", update = false, open = true)
            check(info(null, problem = "bad gitlink"), "异常", update = false, open = false)
        }
    }
}
