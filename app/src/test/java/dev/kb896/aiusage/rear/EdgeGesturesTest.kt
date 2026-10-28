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

    // -- the sheet up --

    private fun sheet(vararg events: RearTouches.Event): List<Action> {
        val g = EdgeGestures().apply { sheet = true }
        return events.flatMap { g.feed(it) }
    }

    @Test fun `with the sheet up a quick touch anywhere is a tap where it landed`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.Tap(400f, 200f)),
            sheet(Down(400f, 200f, 0), Move(405f, 204f, 50), Up(406f, 205f, 120)),
        )
    }

    @Test fun `with the sheet up the bottom band is the sheet's, not a pull to arm`() {
        assertEquals(
            listOf(Action.SheetDown(600f, 560f), Action.Tap(600f, 560f)),
            sheet(Down(600f, 560f, 0), Up(600f, 560f, 80)),
        )
    }

    @Test fun `a touch held past the tap's time but lifted before the long-press is nothing`() {
        assertEquals(listOf(Action.SheetDown(400f, 200f), Action.Cancel), sheet(Down(400f, 200f, 0), Up(400f, 200f, 380)))
    }

    @Test fun `a finger that wanders and comes back is not a tap`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.Cancel),
            sheet(Down(400f, 200f, 0), Move(440f, 205f, 40), Move(401f, 200f, 80), Up(400f, 200f, 120)),
        )
    }

    @Test fun `a lift past the slop is not a tap`() {
        assertEquals(listOf(Action.SheetDown(400f, 200f), Action.Cancel), sheet(Down(400f, 200f, 0), Up(430f, 200f, 60)))
    }

    @Test fun `a drag down on the sheet is a pull from where it landed`() {
        assertEquals(
            listOf(Action.SheetDown(500f, 200f), Action.PullStart(230f), Action.PullMove(320f), Action.PullEnd(330f)),
            sheet(Down(500f, 200f, 0), Move(502f, 210f, 10), Move(503f, 230f, 20), Move(504f, 320f, 30), Up(504f, 330f, 40)),
        )
    }

    @Test fun `a drag up on the sheet is a pull too, so a sheet on its way shut can be caught`() {
        assertEquals(
            listOf(Action.SheetDown(500f, 400f), Action.PullStart(380f), Action.PullEnd(300f)),
            sheet(Down(500f, 400f, 0), Move(500f, 380f, 10), Up(500f, 300f, 30)),
        )
    }

    @Test fun `a sideways drag on the sheet is neither a pull nor a tap`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.Cancel),
            sheet(Down(400f, 200f, 0), Move(430f, 205f, 10), Move(430f, 260f, 20), Up(430f, 300f, 30)),
        )
    }

    @Test fun `a still finger becomes a long-press once, from the tick, and its lift ends it`() {
        val g = EdgeGestures().apply { sheet = true }
        assertEquals(listOf(Action.SheetDown(400f, 200f)), g.feed(Down(400f, 200f, 1_000)))
        assertEquals(emptyList<Action>(), g.tick(1_449))
        assertEquals(listOf(Action.LongPress(400f, 200f)), g.tick(1_450))
        assertEquals(emptyList<Action>(), g.tick(1_600))
        assertEquals(listOf(Action.Cancel), g.feed(Up(401f, 200f, 1_700)))
    }

    @Test fun `a long-press can come from a jittering move`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.LongPress(400f, 200f), Action.Cancel),
            sheet(Down(400f, 200f, 0), Move(402f, 201f, 200), Move(403f, 199f, 460), Move(401f, 200f, 500), Up(401f, 200f, 600)),
        )
    }

    @Test fun `held long and lifted before the tick came, it is still a long-press`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.LongPress(400f, 200f), Action.Cancel),
            sheet(Down(400f, 200f, 0), Up(400f, 200f, 500)),
        )
    }

    @Test fun `no long-press for a finger that wandered or is dragging`() {
        val g = EdgeGestures().apply { sheet = true }
        g.feed(Down(400f, 200f, 0)); g.feed(Move(400f, 260f, 20))
        assertEquals(emptyList<Action>(), g.tick(1_000))
        val h = EdgeGestures().apply { sheet = true }
        h.feed(Down(400f, 200f, 0)); h.feed(Move(440f, 200f, 20))
        assertEquals(emptyList<Action>(), h.tick(1_000))
    }

    @Test fun `no tick does anything with the sheet shut`() {
        val g = EdgeGestures()
        g.feed(Down(400f, 200f, 0))
        assertEquals(emptyList<Action>(), g.tick(1_000))
    }

    @Test fun `the sheet's state is read when a finger lands, so the opening pull runs to its lift`() {
        val g = EdgeGestures()
        val out = mutableListOf<Action>()
        out += g.feed(Down(600f, 560f, 0))
        out += g.feed(Move(600f, 500f, 10))
        g.sheet = true
        out += g.feed(Move(600f, 400f, 20))
        out += g.feed(Up(600f, 380f, 30))
        out += g.feed(Down(400f, 200f, 100))
        assertEquals(
            listOf(Action.Arm, Action.PullStart(500f), Action.PullMove(400f), Action.PullEnd(380f), Action.SheetDown(400f, 200f)),
            out,
        )
    }

    @Test fun `with the sheet up the right edge is not Back`() {
        assertEquals(
            listOf(Action.SheetDown(880f, 300f), Action.Cancel),
            sheet(Down(880f, 300f, 0), Move(840f, 305f, 10), Up(780f, 310f, 20)),
        )
    }

    @Test fun `a new finger ends a sheet gesture that never lifted`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.Cancel, Action.SheetDown(500f, 300f)),
            sheet(Down(400f, 200f, 0), Down(500f, 300f, 50)),
        )
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.PullStart(250f), Action.PullEnd(250f), Action.SheetDown(500f, 300f)),
            sheet(Down(400f, 200f, 0), Move(400f, 250f, 10), Down(500f, 300f, 50)),
        )
    }
}
