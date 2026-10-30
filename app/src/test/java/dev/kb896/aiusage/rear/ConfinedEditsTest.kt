package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfinedEditsTest {

    @Test fun `a switch is handed on at once, and once`() {
        val e = ConfinedEdits()
        e.put("a", true, 100)
        assertEquals(mapOf("a" to true), e.due(100))
        assertEquals(emptyMap<String, Boolean>(), e.due(200))
    }

    @Test fun `an older payload does not undo a switch either way`() {
        val e = ConfinedEdits()
        e.put("a", true, 0)
        e.put("b", false, 0)
        assertEquals(setOf("a", "c"), e.overlay(setOf("b", "c"), 10))
        e.due(10)
        assertEquals(setOf("a", "c"), e.overlay(setOf("b", "c"), 20))
    }

    @Test fun `one never saved gives way to the config after the hold`() {
        val e = ConfinedEdits()
        e.put("a", true, 0)
        e.due(0)
        assertEquals(emptySet<String>(), e.overlay(emptySet(), DpiEdits.HOLD_MS + 1))
    }

    @Test fun `one that did not get there is due again`() {
        val e = ConfinedEdits()
        e.put("a", false, 0)
        e.due(0)
        e.unsend("a", false)
        assertEquals(mapOf("a" to false), e.due(10))
    }
}
