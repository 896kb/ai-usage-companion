package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PillClockTest {

    @Test fun `twelve is up and the hands run clockwise`() {
        val a = PillClock.angles(12, 0, 0, 0)
        assertEquals(0f, a.hour, 0.001f)
        assertEquals(0f, a.minute, 0.001f)
        assertEquals(0f, a.second, 0.001f)
        assertEquals(90f, PillClock.angles(3, 0, 0, 0).hour, 0.001f)
    }

    @Test fun `the hour hand moves with the minutes`() {
        val a = PillClock.angles(9, 30, 0, 0)
        assertEquals(285f, a.hour, 0.001f)
        assertEquals(180f, a.minute, 0.001f)
        val b = PillClock.angles(18, 15, 30, 0)
        assertEquals(187.75f, b.hour, 0.001f)
        assertEquals(93f, b.minute, 0.001f)
        assertEquals(180f, b.second, 0.001f)
    }

    @Test fun `the second hand sweeps through the millis`() {
        assertEquals(3f, PillClock.angles(0, 0, 0, 500).second, 0.001f)
    }

    @Test fun `just before twelve every hand is almost round`() {
        val a = PillClock.angles(11, 59, 59, 999)
        // 359.99999°, which a Float rounds to 360: as good as round.
        assertTrue(a.hour > 359.9f && a.hour <= 360f)
        assertTrue(a.minute > 359.9f && a.minute < 360f)
        assertTrue(a.second > 359.9f && a.second < 360f)
    }

    @Test fun `the dial fits the band it is given, right of the lenses`() {
        // The 17 Pro's usable area, between the date's row (64) and the slider's band (464).
        val d = PillClock.dial(cx = 600f, left = 296f, right = 904f, top = 64f, bottom = 464f)
        assertTrue(d.r > 150f)
        assertTrue(d.left >= 296f && d.right <= 904f)
        assertTrue(d.top >= 64f && d.bottom <= 464f)
        assertEquals(264f, d.cy, 0.001f)
        assertEquals(600f, d.cx, 0.001f)
    }

    @Test fun `a centre too near the lenses moves the dial in rather than past them`() {
        val d = PillClock.dial(cx = 320f, left = 296f, right = 904f, top = 64f, bottom = 464f)
        assertTrue(d.left >= 296f)
    }

    @Test fun `a taller rear gets a bigger dial`() {
        val pro = PillClock.dial(600f, 296f, 904f, 64f, 464f)
        val taller = PillClock.dial(640f, 296f, 976f, 64f, 494f)
        assertTrue(taller.r > pro.r)
    }

    @Test fun `a hand's box points up from the centre with its tail behind`() {
        val d = PillClock.Dial(500f, 200f, 100f)
        val b = PillClock.box(d, PillClock.MINUTE)
        assertEquals(500f - 9f, b.left, 0.001f)
        assertEquals(500f + 9f, b.right, 0.001f)
        assertEquals(200f - 92f, b.top, 0.001f)
        assertEquals(200f + 16f, b.bottom, 0.001f)
    }

    @Test fun `the second hand starts inside the minute hand and ends as itself`() {
        val start = PillClock.split(minute = 90f, second = 180f, p = 0f)
        assertEquals(90f, start.angle, 0.001f)
        assertTrue(start.thickness < PillClock.MINUTE.thickness)
        // The bottle's body too: it starts inside the minute hand.
        assertTrue(start.thickness * PillClock.BOTTLE_BODY < PillClock.MINUTE.thickness)
        assertTrue(start.length < PillClock.MINUTE.length)
        assertEquals(1f, start.web, 0.001f)
        val end = PillClock.split(90f, 180f, 1f)
        assertEquals(180f, end.angle, 0.001f)
        assertEquals(PillClock.SECOND.thickness, end.thickness, 0.0001f)
        assertEquals(PillClock.SECOND.length, end.length, 0.0001f)
        assertEquals(0f, end.web, 0.001f)
    }

    @Test fun `it peels off the shortest way round`() {
        // From 350 to 10 is twenty degrees through twelve, not three hundred and forty back
        // (a little past 10 on the way, with the overshoot).
        for (p in listOf(0.25f, 0.5f, 0.75f)) {
            val a = PillClock.split(350f, 10f, p).angle
            assertTrue("$p -> $a", a > 350f || a < 20f)
        }
    }

    @Test fun `the web stretches thin and snaps before the hand is home`() {
        val mid = PillClock.split(0f, 120f, 0.4f)
        assertTrue(mid.web > 0f && mid.web < 1f)
        assertEquals(0f, PillClock.split(0f, 120f, PillClock.WEB_SNAP).web, 0.001f)
    }

    @Test fun `the web only bridges where the hands are still close`() {
        val r = 200f
        val full = PillClock.webReach(r, minute = 0f, second = 0f, secondLength = 0.9f, secondThickness = 0.07f, web = 1f)
        // Together: out along most of the shorter hand.
        assertEquals(0.9f * PillClock.WEB_REACH * r, full, 0.5f)
        // Fifty degrees apart: a neck near the centre, nowhere near a fan.
        val apart = PillClock.webReach(r, 0f, 50f, 0.9f, 0.07f, web = 1f)
        assertTrue("$apart", apart < 0.5f * r)
        // Thinning: shorter still.
        assertTrue(PillClock.webReach(r, 0f, 20f, 0.9f, 0.07f, web = 0.5f) < PillClock.webReach(r, 0f, 20f, 0.9f, 0.07f, web = 1f))
        // The shortest way round: 350 to 10 is as close as 0 to 20.
        assertEquals(PillClock.webReach(r, 0f, 20f, 0.9f, 0.07f, 1f), PillClock.webReach(r, 350f, 10f, 0.9f, 0.07f, 1f), 0.01f)
    }
}
