package gitviz.git

import gitviz.*
import kotlin.test.*

class SubtreesTest {
    @Test fun `add and update fixed remote snapshot`() = RepositoryFixture().use { r ->
        RepositoryFixture().use { remote ->
            val original = r.commit(); remote.commit("library", "v1\n")
            r.git("config", "protocol.file.allow", "always")
            assertTrue(r.cli.subtreeAvailable(), "Test Git must include subtree")
            fun request(add: Boolean) = SubtreeRequest("vendor/space name", remote.root.path, "main", add, true, r.cli.headIdentity())
            val added = r.cli.applySubtree(request(true)); assertTrue(added.ok, added.output)
            assertEquals("v1\n", r.root.resolve("vendor/space name/library").readText())
            val afterAdd = r.git("rev-parse", "HEAD")
            remote.commit("library", "v2\n")
            val updated = r.cli.applySubtree(request(false)); assertTrue(updated.ok, updated.output)
            assertEquals("v2\n", r.root.resolve("vendor/space name/library").readText())
            assertEquals(afterAdd, r.git("rev-parse", "HEAD^1")); assertNotEquals(original, afterAdd)
        }
    }

    @Test fun `conflicts use normal merge abort and invalid dirty or stale requests cannot integrate`() = RepositoryFixture().use { r ->
        RepositoryFixture().use { remote ->
            r.commit(); remote.commit("library", "base\n"); r.git("config", "protocol.file.allow", "always")
            fun request() = SubtreeRequest("vendor", remote.root.path, "main", false, true, r.cli.headIdentity())
            assertTrue(r.cli.applySubtree(request().copy(add = true)).ok)
            r.commit("vendor/library", "local\n"); remote.commit("library", "remote\n")
            val before = r.git("rev-parse", "HEAD")
            val conflict = r.cli.applySubtree(request()); assertEquals(GitFailure.CONFLICT, conflict.failure, conflict.output)
            assertEquals(GitOperation.MERGE, r.cli.currentOperation()); assertTrue(r.cli.abortOperation().ok)
            assertEquals(before, r.git("rev-parse", "HEAD")); assertEquals("local\n", r.root.resolve("vendor/library").readText())
            listOf("../outside", ".git/objects", "/absolute", "a/../b", "-option").forEach { assertFalse(r.cli.applySubtree(request().copy(prefix = it)).ok) }
            assertFalse(r.cli.applySubtree(request().copy(ref = "main:refs/heads/main")).ok)
            val stale = request(); r.commit("new", "next\n"); assertFalse(r.cli.applySubtree(stale).ok)
            r.write("dirty", "keep"); assertFalse(r.cli.applySubtree(request()).ok)
            assertEquals("keep", r.root.resolve("dirty").readText())
        }
    }
}
