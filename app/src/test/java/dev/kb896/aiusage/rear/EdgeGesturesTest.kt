package dev.kb896.aiusage.rear

import dev.kb896.aiusage.rear.EdgeGestures.Action
import dev.kb896.aiusage.rear.RearTouches.Down
import dev.kb896.aiusage.rear.RearTouches.Move
import dev.kb896.aiusage.rear.RearTouches.Up
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeGesturesTest {

    private fun run(vararg events: RearTouches.Event): List<Action> {
        val g = EdgeGestures()
        return events.flatMap { g.feed(it) }
    }

    @Test fun `a landing in the bottom band arms the screenshot at once`() {
        assertEquals(listOf(Action.Arm), run(Down(600f, 560f, 0)))
    }

    @Test fun `a pull up commits past the slop and is followed to the lift`() {
        val actions = run(Down(600f, 560f, 0), Move(600f, 550f, 10), Move(601f, 500f, 20), Move(602f, 400f, 30), Up(602f, 380f, 40))
        assertEquals(
            listOf(Action.Arm, Action.PullStart(500f), Action.PullMove(400f), Action.PullEnd(380f)),
            actions,
        )
    }

    @Test fun `a sideways drag from the band is not a pull`() {
        val actions = run(Down(600f, 560f, 0), Move(700f, 555f, 10), Up(800f, 555f, 20))
        assertEquals(listOf(Action.Arm, Action.Disarm), actions)
    }

    @Test fun `a swipe in from the right edge is Back`() {
        assertEquals(listOf(Action.Back), run(Down(880f, 300f, 0), Move(840f, 305f, 10), Up(780f, 310f, 20)))
    }

    @Test fun `too short, or too steep, is not`() {
        assertEquals(emptyList<Action>(), run(Down(880f, 300f, 0), Up(850f, 300f, 20)))
        assertEquals(emptyList<Action>(), run(Down(880f, 300f, 0), Up(800f, 200f, 20)))
    }

    @Test fun `a tap in the middle is nobody's`() {
        assertEquals(emptyList<Action>(), run(Down(600f, 300f, 0), Up(600f, 300f, 80)))
    }

    @Test fun `a new finger closes a pull that never lifted`() {
        val actions = run(Down(600f, 560f, 0), Move(600f, 500f, 10), Move(600f, 450f, 20), Down(100f, 300f, 30))
        assertEquals(
            listOf(Action.Arm, Action.PullStart(500f), Action.PullMove(450f), Action.PullEnd(450f)),
            actions,
        )
    }

    @Test fun `a new finger disarms a band touch that never lifted`() {
        assertEquals(
            listOf(Action.Arm, Action.Disarm, Action.Arm),
            run(Down(600f, 560f, 0), Down(610f, 560f, 10)),
        )
    }

    @Test fun `a tap in the band arms and disarms`() {
        assertEquals(listOf(Action.Arm, Action.Disarm), run(Down(600f, 560f, 0), Up(600f, 560f, 10)))
    }

    @Test fun `a committed pull stays a pull to the lift, even back down`() {
        val actions = run(Down(600f, 560f, 0), Move(600f, 500f, 10), Move(600f, 570f, 20), Up(600f, 570f, 30))
        assertEquals(
            listOf(Action.Arm, Action.PullStart(500f), Action.PullMove(570f), Action.PullEnd(570f)),
            actions,
        )
    }

    @Test fun `the bottom-right corner is the pull's, not Back's`() {
        assertEquals(
            listOf(Action.Arm, Action.Disarm),
            run(Down(880f, 540f, 0), Move(840f, 541f, 10), Up(780f, 545f, 20)),
        )
    }

    @Test fun `a scroll down the right edge is not Back`() {
        assertEquals(emptyList<Action>(), run(Down(880f, 300f, 0), Move(870f, 100f, 10), Up(860f, 100f, 20)))
    }
}
