package gitviz.platform

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppPreferencesTest {
    private val root = Preferences.userRoot().node("preflight-tests/app-preferences-${UUID.randomUUID()}")

    @AfterTest
    fun cleanUp() = root.removeNode()

    @Test
    fun `copies legacy values the first time the new node is opened`() {
        root.node("old").apply { put("language", "ZH"); putBoolean("dark", true) }
        val node = AppPreferences.open(root, "new", "old")
        assertEquals("ZH", node.get("language", null))
        assertEquals(true, node.getBoolean("dark", false))
        assertEquals("ZH", root.node("old").get("language", null))
    }

    @Test
    fun `does not overwrite an existing new node`() {
        root.node("old").put("language", "ZH")
        root.node("new").put("rowHeight", "32")
        val node = AppPreferences.open(root, "new", "old")
        assertNull(node.get("language", null))
        assertEquals("32", node.get("rowHeight", null))
    }
}
