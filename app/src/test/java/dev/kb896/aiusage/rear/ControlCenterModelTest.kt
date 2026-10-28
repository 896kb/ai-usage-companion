package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.ControlCenterFinger.Action
import dev.kb896.aiusage.rear.ControlCenterModel.Effect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's control centre as a state: what a pull, a finger and a reading do to it. */
class ControlCenterModelTest {

    private val travel = ControlCenter.TRAVEL

    /** Pulled down from the top edge and let go open, settled by [at]. */
    private fun opened(at: Long = 10_000L): ControlCenterModel = ControlCenterModel().apply {
        beginPull(downY = 10f, y = 30f, atMs = 0L)
        movePull(10f + travel * 0.8f, 100L)
        endPull(10f + travel * 0.8f, 200L)
        assertEquals(1f, progress(at), 0f)
    }

    @Test fun `a pull from the top follows the finger from where it landed`() {
        val m = ControlCenterModel()
        m.beginPull(downY = 10f, y = 30f, atMs = 0L)
        assertEquals(20f / travel, m.progress(0L), 1e-4f)
        m.movePull(10f + travel / 2f, 50L)
        assertEquals(0.5f, m.progress(50L), 1e-4f)
        assertTrue(m.shown(50L))
    }

    @Test fun `let go past halfway it opens, short of it it shuts and says when its layers may go`() {
        val open = ControlCenterModel()
        open.beginPull(10f, 30f, 0L)
        open.movePull(10f + travel * 0.7f, 300L)
        assertEquals(emptyList<Effect>(), open.endPull(10f + travel * 0.7f, 500L))
        assertEquals(1f, open.progress(5_000L), 0f)

        val shut = ControlCenterModel()
        shut.beginPull(10f, 30f, 0L)
        shut.movePull(10f + travel * 0.3f, 300L)
        val effects = shut.endPull(10f + travel * 0.3f, 500L)
        val at = (effects.single() as Effect.ShutAt).atMs
        assertTrue(at > 500L)
        assertEquals(0f, shut.progress(at), 0f)
        assertTrue(shut.isShut(at))
        assertFalse(shut.shown(at))
    }

    @Test fun `a flick down opens it however short`() {
        val m = ControlCenterModel()
        m.beginPull(10f, 30f, 0L)
        m.movePull(60f, 10L)
        m.movePull(90f, 20L)
        m.endPull(90f, 25L)
        assertEquals(1f, m.progress(5_000L), 0f)
    }

    @Test fun `open, a drag up past halfway closes it`() {
        val m = opened()
        m.finger(Action.Down(ControlCenterLayout.NONE), 20_000L)
        m.finger(Action.Pull(-travel * 0.6f), 20_100L)
        assertEquals(0.4f, m.progress(20_100L), 1e-4f)
        val effects = m.finger(Action.Release(-travel * 0.6f, 0f, lifted = true), 20_400L)
        assertTrue(effects.single() is Effect.ShutAt)
    }

    @Test fun `a lost finger leaves it as it was`() {
        val m = opened()
        m.finger(Action.Pull(-travel * 0.9f), 20_100L)
        m.finger(Action.Release(-travel * 0.9f, 0f, lifted = false), 20_200L)
        assertEquals(1f, m.progress(30_000L), 0f)
    }

    @Test fun `Back shuts it`() {
        val m = opened()
        assertTrue(m.finger(Action.Back, 20_000L).single() is Effect.ShutAt)
    }

    @Test fun `a tap off the controls leaves it open`() {
        val m = opened()
        assertEquals(emptyList<Effect>(), m.finger(Action.Tap(ControlCenterLayout.NONE), 20_000L))
        assertEquals(1f, m.progress(30_000L), 0f)
    }

    @Test fun `a tile is switched at once, then in the phone, Shizuku's three only when reachable`() {
        val m = opened()
        m.quickStates(booleanArrayOf(false, false, false, false), reachable = false, nowMs = 0L)
        assertEquals(emptyList<Effect>(), m.finger(Action.Tap(1), 20_000L))
        assertEquals(listOf(Effect.Quick("silent", true)), m.finger(Action.Tap(0), 20_100L))
        m.quickStates(booleanArrayOf(true, true, false, false), reachable = true, nowMs = 20_200L)
        assertEquals(listOf(Effect.Quick("bluetooth", false)), m.finger(Action.Tap(1), 20_300L))
        // Eased to off over a quarter second.
        assertEquals(0f, m.look(25_000L).tiles[1], 0f)
        assertEquals(1f, m.look(25_000L).tiles[0], 0f)
    }

    @Test fun `a finger on a tile shows it pressed until it lifts or moves`() {
        val m = opened()
        m.finger(Action.Down(3), 20_000L)
        assertEquals(3, m.look(20_000L).pressed)
        m.finger(Action.Pull(30f), 20_050L)
        assertEquals(ControlCenterLayout.NONE, m.look(20_050L).pressed)
    }

    @Test fun `the round button switches automatic brightness`() {
        val m = opened()
        m.reading(128, auto = false, nowMs = 0L)
        assertEquals(listOf(Effect.Auto(true)), m.finger(Action.Tap(ControlCenterLayout.AUTO), 20_000L))
        assertEquals(1f, m.look(21_000L).auto, 0f)
    }

    @Test fun `a drag on the cell sets the brightness as it goes, at most every SEND_MS, and once more let go`() {
        val m = opened()
        m.reading(128, auto = true, nowMs = 0L)
        val from = ControlCenter.level(128)
        m.finger(Action.Down(ControlCenterLayout.CELL), 20_000L)
        val first = m.finger(Action.Bright(-36f), 20_010L)
        val want = ControlCenter.drag(from, -36f)
        assertEquals(listOf(Effect.Brightness(ControlCenter.setting(want))), first)
        // Set by hand: no longer automatic.
        assertEquals(0f, m.look(30_000L).auto, 0f)
        assertEquals(emptyList<Effect>(), m.finger(Action.Bright(-40f), 20_020L))
        assertEquals(1, m.finger(Action.Bright(-72f), 20_010L + ControlCenter.SEND_MS).size)
        val end = m.finger(Action.BrightEnd, 20_100L)
        assertEquals(listOf(Effect.Brightness(ControlCenter.setting(ControlCenter.drag(from, -72f)))), end)
        assertEquals(ControlCenter.drag(from, -72f), m.look(20_100L).level, 1e-5f)
        // The figure is up while it changes.
        assertTrue(m.look(20_100L + 500L).figureGrow > 0.9f)
    }

    @Test fun `a new reading pours in rather than jumping`() {
        val m = opened()
        m.reading(255, auto = false, nowMs = 0L)
        assertEquals(1f, m.look(1L).level, 0f)
        m.reading(1, auto = true, nowMs = 1_000L)
        val mid = m.look(1_000L + (ControlCenter.POUR_MS / 2).toLong()).level
        assertTrue("mid-pour: $mid", mid > ControlCenter.MIN_LEVEL && mid < 1f)
        assertEquals(ControlCenter.MIN_LEVEL, m.look(5_000L).level, 0f)
    }

    @Test fun `a finger on a settling centre puts it where it was going`() {
        val m = ControlCenterModel()
        m.beginPull(10f, 30f, 0L)
        m.movePull(10f + travel * 0.7f, 300L)
        m.endPull(10f + travel * 0.7f, 500L)
        m.finger(Action.Down(ControlCenterLayout.NONE), 510L)
        assertEquals(1f, m.progress(510L), 0f)
    }
}
