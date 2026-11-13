package gitviz.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.*

class CommandsTest {
    @Test fun `shelved features have no regular command while core commands remain available`() {
        var calls = 0
        val hidden = listOf("ai", "replay", "forge", "reflog")
        val registry = CommandRegistry((hidden + "refresh").map { AppCommand(it, it, true) { calls++ } })
        hidden.forEach { assertFalse(registry.execute(it)) }
        assertEquals(listOf("refresh"), registry.commands.map { it.id })
        assertEquals(0, calls)
        assertTrue(registry.execute("refresh")); assertEquals(1, calls)
    }

    @Test fun `editing keeps text undo and escape never aborts Git`() {
        assertNull(shortcutCommand(Key.Z, true, false, true))
        assertNull(shortcutCommand(Key.Z, true, false, false))
        assertEquals("palette", shortcutCommand(Key.P, true, true, true))
        assertEquals("commit", shortcutCommand(Key.Enter, true, false, true))
        assertNull(shortcutCommand(Key.Escape, false, false, false))
        assertNull(shortcutCommand(Key.J, false, false, false))
    }
    @Test fun `disabled commands do not run`() {
        var calls = 0
        val enabled = CommandRegistry(listOf(AppCommand("run", "Run", true) { calls++ }))
        assertTrue(enabled.execute("run"))
        assertEquals(1, calls)
        val disabled = CommandRegistry(listOf(AppCommand("run", "Run", false) { calls++ }))
        assertTrue(disabled.execute("run"))
        assertEquals(1, calls)
    }
}
