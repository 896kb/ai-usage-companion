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

    @Test fun `a landing in the bottom band says nothing until it moves`() {
        assertEquals(emptyList<Action>(), run(Down(600f, 560f, 0)))
    }

    @Test fun `a pull up commits past the slop and is followed to the lift`() {
        val actions = run(Down(600f, 560f, 0), Move(600f, 550f, 10), Move(601f, 500f, 20), Move(602f, 400f, 30), Up(602f, 380f, 40))
        assertEquals(
            listOf(Action.PullStart(500f), Action.PullMove(400f), Action.PullEnd(380f)),
            actions,
        )
    }

    @Test fun `a sideways drag from the band is not a pull`() {
        val actions = run(Down(600f, 560f, 0), Move(700f, 555f, 10), Up(800f, 555f, 20))
        assertEquals(emptyList<Action>(), actions)
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
            listOf(Action.PullStart(500f), Action.PullMove(450f), Action.PullEnd(450f)),
            actions,
        )
    }

    @Test fun `a new finger after a band touch that never lifted starts afresh`() {
        assertEquals(
            listOf(Action.PullStart(500f)),
            run(Down(600f, 560f, 0), Down(610f, 560f, 10), Move(610f, 500f, 20)),
        )
    }

    @Test fun `a tap in the band is nothing`() {
        assertEquals(emptyList<Action>(), run(Down(600f, 560f, 0), Up(600f, 560f, 10)))
    }

    @Test fun `a committed pull stays a pull to the lift, even back down`() {
        val actions = run(Down(600f, 560f, 0), Move(600f, 500f, 10), Move(600f, 570f, 20), Up(600f, 570f, 30))
        assertEquals(
            listOf(Action.PullStart(500f), Action.PullMove(570f), Action.PullEnd(570f)),
            actions,
        )
    }

    @Test fun `the bottom-right corner is the pull's, not Back's`() {
        assertEquals(
            emptyList<Action>(),
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

    @Test fun `a lift past the slop is not a tap`() {
        assertEquals(listOf(Action.SheetDown(400f, 200f), Action.Cancel), sheet(Down(400f, 200f, 0), Up(430f, 200f, 60)))
    }

    @Test fun `a drag down on the sheet is a pull from where it landed`() {
        assertEquals(
            listOf(Action.SheetDown(500f, 200f), Action.PullStart(230f, onSheet = true), Action.PullMove(320f), Action.PullEnd(330f)),
            sheet(Down(500f, 200f, 0), Move(502f, 210f, 10), Move(503f, 230f, 20), Move(504f, 320f, 30), Up(504f, 330f, 40)),
        )
    }

    @Test fun `a drag up on the sheet is a pull too, so a sheet on its way shut can be caught`() {
        assertEquals(
            listOf(Action.SheetDown(500f, 400f), Action.PullStart(380f, onSheet = true), Action.PullEnd(300f)),
            sheet(Down(500f, 400f, 0), Move(500f, 380f, 10), Up(500f, 300f, 30)),
        )
    }

    @Test fun `a sideways drag on the sheet is a slide, followed to its lift`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.SlideStart(430f), Action.SlideMove(470f), Action.SlideEnd(480f)),
            sheet(Down(400f, 200f, 0), Move(410f, 202f, 10), Move(430f, 205f, 20), Move(470f, 260f, 30), Up(480f, 300f, 40)),
        )
    }

    @Test fun `a slide is a slide to its lift, however far it then goes up or down`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 500f), Action.SlideStart(380f), Action.SlideMove(370f), Action.SlideEnd(370f)),
            sheet(Down(400f, 500f, 0), Move(380f, 495f, 10), Move(370f, 300f, 20), Up(370f, 200f, 30)),
        )
    }

    @Test fun `a pull is a pull to its lift, however far it then goes sideways`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 300f), Action.PullStart(320f, onSheet = true), Action.PullMove(330f), Action.PullEnd(330f)),
            sheet(Down(400f, 300f, 0), Move(405f, 320f, 10), Move(600f, 330f, 20), Up(700f, 330f, 30)),
        )
    }

    @Test fun `a finger that wanders past the slop is a drag, not a tap`() {
        val out = sheet(Down(400f, 200f, 0), Move(440f, 205f, 40), Move(401f, 200f, 80), Up(400f, 200f, 120))
        assertEquals(Action.SlideStart(440f), out[1])
        assertEquals(Action.SlideEnd(400f), out.last())
    }

    @Test fun `a still finger held past the tap's time is nothing`() {
        assertEquals(listOf(Action.SheetDown(400f, 200f), Action.Cancel), sheet(Down(400f, 200f, 0), Up(401f, 200f, 900)))
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
            listOf(Action.PullStart(500f), Action.PullMove(400f), Action.PullEnd(380f), Action.SheetDown(400f, 200f)),
            out,
        )
    }

    @Test fun `with the sheet up the right edge is not Back`() {
        assertEquals(
            listOf(Action.SheetDown(880f, 300f), Action.SlideStart(840f), Action.SlideEnd(780f)),
            sheet(Down(880f, 300f, 0), Move(840f, 305f, 10), Up(780f, 310f, 20)),
        )
    }

    @Test fun `a new finger ends a sheet gesture that never lifted`() {
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.Cancel, Action.SheetDown(500f, 300f)),
            sheet(Down(400f, 200f, 0), Down(500f, 300f, 50)),
        )
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.PullStart(250f, onSheet = true), Action.PullEnd(250f), Action.SheetDown(500f, 300f)),
            sheet(Down(400f, 200f, 0), Move(400f, 250f, 10), Down(500f, 300f, 50)),
        )
        assertEquals(
            listOf(Action.SheetDown(400f, 200f), Action.SlideStart(450f), Action.SlideEnd(450f), Action.SheetDown(500f, 300f)),
            sheet(Down(400f, 200f, 0), Move(450f, 200f, 10), Down(500f, 300f, 50)),
        )
    }

    @Test fun `only a pull from the band may open the sheet, a drag on the sheet is marked`() {
        assertEquals(listOf(Action.PullStart(500f, onSheet = false)), run(Down(600f, 560f, 0), Move(600f, 500f, 10)))
        assertEquals(
            listOf(Action.SheetDown(600f, 560f), Action.PullStart(500f, onSheet = true)),
            sheet(Down(600f, 560f, 0), Move(600f, 500f, 10)),
        )
    }

    // ---- the top band: the control centre's pull (ControlCenter.startsPull) ----

    @Test fun `a pull down from the top edge commits past the slop and is followed to the lift`() {
        val actions = run(Down(400f, 10f, 0), Move(400f, 20f, 10), Move(401f, 60f, 20), Move(402f, 200f, 30), Up(402f, 240f, 40))
        assertEquals(
            listOf(Action.TopPullStart(60f), Action.TopPullMove(200f), Action.TopPullEnd(240f)),
            actions,
        )
    }

    @Test fun `only over the left half of the usable area, right at the top`() {
        assertEquals(emptyList<Action>(), run(Down(800f, 10f, 0), Move(800f, 100f, 10), Up(800f, 200f, 20)))
        assertEquals(emptyList<Action>(), run(Down(400f, 80f, 0), Move(400f, 150f, 10), Up(400f, 200f, 20)))
        assertEquals(emptyList<Action>(), run(Down(200f, 10f, 0), Move(200f, 100f, 10), Up(200f, 200f, 20)))
    }

    @Test fun `a sideways or upward drag from the top band is not a pull`() {
        assertEquals(emptyList<Action>(), run(Down(400f, 20f, 0), Move(460f, 25f, 10), Move(460f, 200f, 20), Up(460f, 220f, 30)))
        assertEquals(emptyList<Action>(), run(Down(400f, 40f, 0), Move(400f, 0f, 10), Up(400f, 0f, 20)))
    }

    @Test fun `a new finger closes a top pull that never lifted`() {
        assertEquals(
            listOf(Action.TopPullStart(80f), Action.TopPullEnd(80f)),
            run(Down(400f, 10f, 0), Move(400f, 80f, 10), Down(600f, 300f, 20)),
        )
    }

    @Test fun `with the sheet up the top band is the sheet's`() {
        val g = EdgeGestures().apply { sheet = true }
        val actions = listOf(Down(400f, 10f, 0), Move(400f, 80f, 10)).flatMap { g.feed(it) }
        assertEquals(listOf(Action.SheetDown(400f, 10f), Action.PullStart(80f, onSheet = true)), actions)
    }

    // ---- on a Pro Max: the bands start where its lenses end (RearGeometry.cutoutUnits) ----

    @Test fun `on a Pro Max a pull may start just right of its lenses, which the 17 Pro's band would miss`() {
        val proMax = RearGeometry(976, 596, 296, 110, 450)
        val up = arrayOf(Down(280f, 560f, 0), Move(280f, 500f, 10), Up(280f, 400f, 20))
        val down = arrayOf(Down(280f, 10f, 0), Move(280f, 60f, 10), Up(280f, 200f, 20))
        val max = EdgeGestures(proMax.cutoutUnits)
        assertEquals(listOf(Action.PullStart(500f), Action.PullEnd(400f)), up.flatMap { max.feed(it) })
        assertEquals(listOf(Action.TopPullStart(60f), Action.TopPullEnd(200f)), down.flatMap { max.feed(it) })
        assertEquals(emptyList<Action>(), run(*up))
        assertEquals(emptyList<Action>(), run(*down))
    }

    @Test fun `on the 17 Pro its geometry is the panel's own band`() {
        assertEquals(296f, RearGeometry.PRO17.cutoutUnits, 0f)
        val g = EdgeGestures(RearGeometry.PRO17.cutoutUnits)
        assertEquals(emptyList<Action>(), listOf(Down(290f, 560f, 0), Move(290f, 500f, 10), Up(290f, 400f, 20)).flatMap { g.feed(it) })
    }
}
