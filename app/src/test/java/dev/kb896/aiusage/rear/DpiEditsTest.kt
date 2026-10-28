package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class DpiEditsTest {

    @Test fun `a run of taps is handed on once, with the last value, after the pause`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 470, 0)
        e.put("a", 490, 300)
        assertEquals(emptyMap<String, Int?>(), e.due(800))
        assertEquals(900L, e.nextDueAt())
        assertEquals(mapOf("a" to 490), e.due(900))
        assertEquals(emptyMap<String, Int?>(), e.due(2000))
        assertEquals(null, e.nextDueAt())
    }

    @Test fun `Default is handed on as null`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", null, 0)
        assertEquals(mapOf<String, Int?>("a" to null), e.due(600))
    }

    @Test fun `an older payload does not undo a nudge`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 490, 0)
        assertEquals(mapOf("a" to 490, "b" to 300), e.overlay(mapOf("a" to 450, "b" to 300), 100))
        e.due(600)
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 450), 1000))
    }

    @Test fun `a cleared app stays cleared over an older payload`() {
        val e = DpiEdits()
        e.put("a", null, 0)
        assertEquals(mapOf("b" to 300), e.overlay(mapOf("a" to 450, "b" to 300), 10))
    }

    @Test fun `once the payload has agreed for the grace the edit is gone, and the config wins after`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000, graceMs = 3_000)
        e.put("a", 490, 0)
        e.due(600)
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 490), 700))
        // The front changes it later: the back follows.
        assertEquals(mapOf("a" to 400), e.overlay(mapOf("a" to 400), 3_700))
    }

    @Test fun `agree, then an older payload within the grace keeps the value`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000, graceMs = 3_000)
        e.put("a", 490, 0)
        e.due(600)
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 490), 700))
        // The minute's push, encoded before the save, delivered after it.
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 450), 900))
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 450), 3_699))
    }

    @Test fun `an edit that never got there is due again`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 490, 0)
        assertEquals(mapOf("a" to 490), e.due(600))
        e.unsend("a", 490)
        assertEquals(600L, e.nextDueAt())
        assertEquals(mapOf("a" to 490), e.due(5_000))
        // Not sent, so not held either: an older payload does not undo it.
        e.unsend("a", 490)
        assertEquals(mapOf("a" to 490), e.overlay(mapOf("a" to 450), 60_000))
    }

    @Test fun `unsend leaves a newer edit alone`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 490, 0)
        e.due(600)
        e.put("a", 510, 700)
        e.unsend("a", 490)
        assertEquals(1_300L, e.nextDueAt())
    }

    @Test fun `a handed-on edit nobody saved gives way after the hold`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 490, 0)
        e.due(600)
        assertEquals(mapOf("a" to 490), e.overlay(emptyMap(), 5_000))
        assertEquals(emptyMap<String, Int>(), e.overlay(emptyMap(), 10_601))
    }

    @Test fun `an edit not yet handed on is never dropped by the hold`() {
        val e = DpiEdits(delayMs = 600, holdMs = 10_000)
        e.put("a", 490, 0)
        assertEquals(mapOf("a" to 490), e.overlay(emptyMap(), 60_000))
    }

    @Test fun `the main process applies a value, clamped, or clears it on 0`() {
        assertEquals(mapOf("a" to 490, "b" to 300), DpiEdits.applied(mapOf("b" to 300), "a", 488))
        assertEquals(mapOf("b" to 300), DpiEdits.applied(mapOf("a" to 490, "b" to 300), "a", 0))
        assertEquals(mapOf("a" to RearDensity.MAX), DpiEdits.applied(emptyMap(), "a", 9000))
    }
}
