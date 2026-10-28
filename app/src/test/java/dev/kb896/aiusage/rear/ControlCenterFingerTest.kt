package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.ControlCenterFinger.Action
import dev.kb896.aiusage.rear.RearTouches.Down
import dev.kb896.aiusage.rear.RearTouches.Move
import dev.kb896.aiusage.rear.RearTouches.Up
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A finger on the open control centre, in the shell, decided as the panel's controlCenterTouch decides it. */
class ControlCenterFingerTest {

    /** The cell left of x 400, a tile right of it, the bottom strip (y > 500) nothing. */
    private val hit: (Float, Float) -> Int = { x, y ->
        when {
            y > 500f -> ControlCenterLayout.NONE
            x < 400f -> ControlCenterLayout.CELL
            else -> 2
        }
    }

    private fun run(vararg events: RearTouches.Event): List<Action> {
        val f = ControlCenterFinger(hit)
        return events.flatMap { f.feed(it) }
    }

    @Test fun `a touch that does not move is a tap on what it landed on`() {
        assertEquals(listOf(Action.Down(2), Action.Tap(2)), run(Down(600f, 300f, 0), Move(605f, 305f, 30), Up(605f, 305f, 60)))
        assertEquals(listOf(Action.Down(ControlCenterLayout.CELL), Action.Tap(ControlCenterLayout.CELL)), run(Down(320f, 300f, 0), Up(320f, 300f, 400)))
    }

    @Test fun `up or down on the cell is the brightness, measured from the landing`() {
        assertEquals(
            listOf(Action.Down(ControlCenterLayout.CELL), Action.Bright(-20f), Action.Bright(-90f), Action.BrightEnd),
            run(Down(320f, 300f, 0), Move(322f, 280f, 10), Move(322f, 210f, 20), Up(322f, 210f, 30)),
        )
    }

    @Test fun `a drag anywhere else moves the centre, and its release carries the finger's speed`() {
        val actions = run(Down(600f, 300f, 0), Move(600f, 280f, 10), Move(600f, 200f, 20), Up(600f, 190f, 25))
        assertEquals(Action.Down(2), actions[0])
        assertEquals(Action.Pull(-20f), actions[1])
        assertEquals(Action.Pull(-100f), actions[2])
        val release = actions[3] as Action.Release
        assertEquals(-100f, release.dy, 0f)
        assertTrue("flicked up: ${release.velocity}", release.velocity < -ControlCenter.FLING)
        assertTrue(release.lifted)
    }

    @Test fun `a sideways drag on the cell moves the centre, not the brightness`() {
        assertEquals(Action.Pull(5f), run(Down(320f, 300f, 0), Move(360f, 305f, 10))[1])
    }

    @Test fun `a finger that stopped before lifting is not flicking`() {
        val release = run(Down(600f, 300f, 0), Move(600f, 200f, 10), Up(600f, 200f, 200)).last() as Action.Release
        assertEquals(0f, release.velocity, 0f)
    }

    @Test fun `a swipe in from the right edge is Back, which shuts the centre`() {
        assertEquals(
            listOf(Action.Down(2), Action.Back),
            run(Down(880f, 300f, 0), Move(840f, 305f, 10), Move(800f, 305f, 20), Up(780f, 310f, 30)),
        )
    }

    @Test fun `too short a swipe from the edge does nothing`() {
        assertEquals(listOf(Action.Down(2), Action.Cancel), run(Down(880f, 300f, 0), Move(850f, 300f, 10), Up(845f, 300f, 20)))
    }

    @Test fun `a drag down from the right edge still moves the centre`() {
        assertEquals(Action.Pull(-60f), run(Down(880f, 300f, 0), Move(882f, 240f, 10))[1])
    }

    @Test fun `a new finger closes the one that never lifted, as a cancel`() {
        assertEquals(
            listOf(Action.Down(2), Action.Pull(-50f), Action.Release(-50f, 0f, lifted = false), Action.Down(2)),
            run(Down(600f, 300f, 0), Move(600f, 250f, 10), Down(600f, 300f, 20)),
        )
        assertEquals(
            listOf(Action.Down(ControlCenterLayout.CELL), Action.Bright(-50f), Action.BrightEnd, Action.Down(2)),
            run(Down(320f, 300f, 0), Move(320f, 250f, 10), Down(600f, 300f, 20)),
        )
        assertEquals(
            listOf(Action.Down(2), Action.Cancel, Action.Down(2)),
            run(Down(600f, 300f, 0), Down(600f, 300f, 20)),
        )
    }
}
