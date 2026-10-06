package dev.kb896.aiusage.rear

import dev.kb896.aiusage.data.GlassReactivity
import dev.kb896.aiusage.data.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlassTintTest {
    private val red = 0xFFFF0000.toInt()

    @Test fun `the tint is the chosen colour at half the intensity's opacity`() {
        assertEquals(0x80FF0000.toInt() ushr 24, GlassStyle(red, 1f, GlassReactivity.NONE).tint() ushr 24)
        assertEquals(0, GlassStyle(red, 0f, GlassReactivity.NONE).tint() ushr 24)
        assertEquals(0xFF0000, GlassStyle(red, 0.5f, GlassReactivity.NONE).tint() and 0xFFFFFF)
    }

    @Test fun `no reactivity keeps one tint whatever is behind`() {
        val s = GlassStyle(red, 0.6f, GlassReactivity.NONE)
        assertEquals(s.tint(), GlassTint.of(s, local = 0.05f, mean = 0.9f))
        assertEquals(s.tint(), GlassTint.of(s, local = 0.95f, mean = 0.1f))
    }

    @Test fun `global reacts to the whole background, not to what is behind the piece`() {
        val s = GlassStyle(red, 0.6f, GlassReactivity.GLOBAL)
        assertEquals(GlassTint.of(s, local = 0.05f, mean = 0.9f), GlassTint.of(s, local = 0.95f, mean = 0.9f))
        assertNotEquals(GlassTint.of(s, local = 0.5f, mean = 0.05f), GlassTint.of(s, local = 0.5f, mean = 0.95f))
    }

    @Test fun `shard reacts to what is right behind the piece`() {
        val s = GlassStyle(red, 0.6f, GlassReactivity.SHARD)
        assertEquals(GlassTint.of(s, local = 0.2f, mean = 0.05f), GlassTint.of(s, local = 0.2f, mean = 0.95f))
        assertNotEquals(GlassTint.of(s, local = 0.05f, mean = 0.5f), GlassTint.of(s, local = 0.95f, mean = 0.5f))
    }

    @Test fun `the editor opens on today's look — white, about fifteen per cent, shard`() {
        val d = GlassStyle.DEFAULT
        assertEquals(0xFFFFFF, d.tint() and 0xFFFFFF)
        assertEquals(Glass.TINT ushr 24, d.tint() ushr 24)
        assertEquals(GlassReactivity.SHARD, d.reactivity)
    }
}
