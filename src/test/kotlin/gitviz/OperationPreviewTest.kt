package gitviz

import kotlin.test.*

class OperationPreviewTest {
    private fun temporaryPreviews() = java.io.File(System.getProperty("java.io.tmpdir")).list().orEmpty().filter { it.startsWith("gitviz-preview-") }.toSet()

    @Test fun `summary is read only, creates no sandbox and merge runs the confirmed commit`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.git("checkout", "-b", "topic"); val tip = r.commit("new", "new\n"); r.git("switch", "main")
        r.write("outside", "untracked stays\n")
        val before = temporaryPreviews()
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
        assertEquals(before, temporaryPreviews())
        assertEquals(base, r.git("rev-parse", "HEAD"))
        assertEquals(tip, plan.intent.target)
        assertEquals(1, plan.commits)
        assertEquals(emptyList(), plan.conflicts)
        assertTrue(plan.facts.any { it.tone == FactTone.GOOD })
        val result = r.cli.executePlan(plan)
        assertTrue(result.ok, result.output)
        assertEquals(tip, r.git("rev-parse", "HEAD"))
        assertEquals("untracked stays\n", r.root.resolve("outside").readText())
    }

    @Test fun `diverged merge predicts conflicted files, keeps the branch name and stops in conflict`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.commit("b", "b\n"); r.git("switch", "topic"); r.commit("a", "topic\n"); r.git("switch", "main")
        val conflicting = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "refs/heads/topic"))
        assertEquals(listOf("a"), conflicting.conflicts)
        assertTrue(conflicting.facts.any { it.tone == FactTone.WARN && "a" in it.text })
        assertNull(r.cli.currentOperation())
        assertEquals(GitFailure.CONFLICT, r.cli.executePlan(conflicting).failure)
        assertTrue(r.cli.abortOperation().ok)

        r.git("switch", "-c", "clean", "main~2"); r.commit("c", "c\n"); r.git("switch", "main")
        val clean = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "refs/heads/clean"))
        assertEquals(emptyList(), clean.conflicts)
        val merged = r.cli.executePlan(clean)
        assertTrue(merged.ok, merged.output)
        assertEquals("Merge branch 'clean'", r.git("log", "-1", "--format=%s"))
    }

    @Test fun `merge by name is refused when the name moved after confirmation`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.git("checkout", "-b", "topic"); r.commit("new", "new\n"); r.git("switch", "main")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))
        r.git("switch", "topic"); r.commit("later", "later\n"); r.git("switch", "main")
        assertFalse(r.cli.executePlan(plan).ok)
        assertEquals(base, r.git("rev-parse", "HEAD"))
    }

    @Test fun `reset summary counts leaving commits and only hard reset lists discarded files`() = RepositoryFixture().use { r ->
        val base = r.commit(); r.commit("b", "b\n"); r.commit("c", "c\n")
        r.write("a", "staged\n"); r.git("add", "a"); r.write("a", "working\n"); r.write("untracked", "new\n")
        for (mode in ResetMode.entries) {
            val plan = r.cli.planOperation(OperationIntent(PreviewKind.RESET, base, mode))
            assertEquals(2, plan.commits)
            assertEquals(if (mode == ResetMode.HARD) listOf("a") else emptyList(), plan.discarded)
            assertEquals(mode == ResetMode.HARD, plan.facts.any { it.tone == FactTone.DANGER })
            assertEquals("staged", r.git("show", ":a")); assertEquals("working\n", r.root.resolve("a").readText())
        }
        val result = r.cli.executePlan(r.cli.planOperation(OperationIntent(PreviewKind.RESET, base, ResetMode.HARD)))
        assertTrue(result.ok, result.output)
        assertEquals(base, r.git("rev-parse", "HEAD"))
        assertEquals("first\n", r.root.resolve("a").readText())
        assertEquals("new\n", r.root.resolve("untracked").readText())
    }

    @Test fun `rebase summary counts replayed commits and does not touch the source`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "topic"); r.commit("a", "main\n"); r.git("switch", "topic"); r.commit("a", "topic\n"); val tip = r.commit("later", "later\n")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.REBASE, "main"))
        assertEquals(2, plan.commits)
        assertNull(plan.conflicts)
        assertNull(r.cli.currentOperation())
        assertEquals(tip, r.git("rev-parse", "HEAD"))
        assertTrue(r.cli.status().isEmpty())
        r.write("dirty", "local\n")
        assertFails { r.cli.planOperation(OperationIntent(PreviewKind.REBASE, "main")) }
    }

    @Test fun `git refuses a merge that would overwrite a local ignored file`() = RepositoryFixture().use { r ->
        r.commit(".gitignore", "ignored\n"); r.git("checkout", "-b", "topic")
        r.write("ignored", "tracked\n"); r.git("add", "-f", "ignored"); r.git("commit", "-qm", "add"); r.git("switch", "main"); r.write("ignored", "local\n")
        val head = r.git("rev-parse", "HEAD")
        assertFalse(r.cli.executePlan(r.cli.planOperation(OperationIntent(PreviewKind.MERGE, "topic"))).ok)
        assertEquals("local\n", r.root.resolve("ignored").readText())
        assertEquals(head, r.git("rev-parse", "HEAD"))
    }

    @Test fun `temporary cleanup never follows directory symlinks`() {
        val outside = java.nio.file.Files.createTempDirectory("gitviz-outside-")
        try {
            java.nio.file.Files.writeString(outside.resolve("sentinel"), "keep")
            val temporary = java.nio.file.Files.createTempDirectory("gitviz-cleanup-test-")
            java.nio.file.Files.createSymbolicLink(temporary.resolve("external"), outside)
            deleteTemporaryTree(temporary)
            assertEquals("keep", java.nio.file.Files.readString(outside.resolve("sentinel")))
        } finally { deleteTemporaryTree(outside) }
    }

    @Test fun `cherry-pick of a merge uses the chosen mainline parent`() = RepositoryFixture().use { r ->
        r.commit(); r.git("branch", "side"); r.commit("main", "main\n"); r.git("switch", "side"); r.commit("side", "side\n"); r.git("merge", "--no-ff", "--no-edit", "main")
        val merge = r.git("rev-parse", "HEAD"); r.git("checkout", "--detach", "HEAD^2")
        val plan = r.cli.planOperation(OperationIntent(PreviewKind.CHERRY_PICK, merge, mainline = 2))
        assertEquals(2, plan.parentCount)
        assertEquals(emptyList(), plan.conflicts)
        assertFails { r.cli.planOperation(OperationIntent(PreviewKind.CHERRY_PICK, merge, mainline = 3)) }
        val result = r.cli.executePlan(plan)
        assertTrue(result.ok, result.output)
        assertTrue(r.root.resolve("side").isFile)
    }
}
