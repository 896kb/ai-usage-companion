package dev.rgarjoaba.claudestatus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitAlertsTest {

    private fun state(percent: Int, health: Health = Health.OK, kind: WindowKind = WindowKind.WEEKLY_ALL) =
        DashboardState(
            fetchedAt = 0L,
            statuses = listOf(
                AccountStatus(
                    Account.PERSONAL, health,
                    UsageSnapshot(listOf(UsageWindow(kind, "7D", percent / 100f, 123L)), 0L),
                )
            ),
        )

    @Test fun `nothing fires below the halfway mark`() {
        assertNull(LimitAlerts.bucketFor(49))
        assertTrue(LimitAlerts.evaluate(state(49), emptyMap()).alerts.isEmpty())
    }

    @Test fun `the halfway mark fires once and is a milestone, not a warning`() {
        val out = LimitAlerts.evaluate(state(50), emptyMap())
        assertEquals(1, out.alerts.size)
        assertTrue(out.alerts[0].isMilestone)
    }

    /** 50..89 is ONE piece of news, not a running commentary. */
    @Test fun `everything from 50 to 89 collapses into the single halfway band`() {
        assertEquals(50, LimitAlerts.bucketFor(50))
        assertEquals(50, LimitAlerts.bucketFor(73))
        assertEquals(50, LimitAlerts.bucketFor(89))
        var thresholds = LimitAlerts.evaluate(state(50), emptyMap()).thresholds
        for (p in 51..89) {
            val out = LimitAlerts.evaluate(state(p), thresholds)
            assertTrue("$p% must not re-fire the halfway band", out.alerts.isEmpty())
            thresholds = out.thresholds
        }
    }

    @Test fun `the 90 band is a warning rather than a milestone`() {
        val out = LimitAlerts.evaluate(state(90), mapOf("personal:weekly_all" to 50))
        assertEquals(1, out.alerts.size)
        assertTrue(!out.alerts[0].isMilestone)
    }

    @Test fun `buckets round down so one crossing fires once`() {
        assertEquals(90, LimitAlerts.bucketFor(90))
        assertEquals(90, LimitAlerts.bucketFor(91))
        assertEquals(92, LimitAlerts.bucketFor(92))
        assertEquals(92, LimitAlerts.bucketFor(93))
        assertEquals(98, LimitAlerts.bucketFor(99))
        assertEquals(100, LimitAlerts.bucketFor(100))
    }

    @Test fun `over 100 is clamped rather than producing a runaway bucket`() {
        assertEquals(100, LimitAlerts.bucketFor(140))
    }

    @Test fun `the same band does not fire twice`() {
        val first = LimitAlerts.evaluate(state(90), emptyMap())
        assertEquals(1, first.alerts.size)
        val second = LimitAlerts.evaluate(state(91), first.thresholds)
        assertTrue("91 is still the 90 band", second.alerts.isEmpty())
    }

    @Test fun `each further step fires once`() {
        var thresholds = mapOf("personal:weekly_all" to LimitAlerts.HALF_PERCENT)
        val fired = ArrayList<Int>()
        for (p in 88..100) {
            val out = LimitAlerts.evaluate(state(p), thresholds)
            out.alerts.forEach { fired += it.percent }
            thresholds = out.thresholds
        }
        assertEquals(listOf(90, 92, 94, 96, 98, 100), fired)
    }

    /** A reset must re-arm, or the limit warns once ever and is silent every week after. */
    @Test fun `dropping below the halfway mark re-arms the next climb`() {
        val high = LimitAlerts.evaluate(state(96), emptyMap())
        assertEquals(1, high.alerts.size)
        val reset = LimitAlerts.evaluate(state(2), high.thresholds)
        assertTrue(reset.alerts.isEmpty())
        assertTrue("history cleared", reset.thresholds.isEmpty())
        val again = LimitAlerts.evaluate(state(55), reset.thresholds)
        assertEquals(1, again.alerts.size)
        assertTrue("a fresh cycle starts at the halfway milestone", again.alerts[0].isMilestone)
    }

    /**
     * A degraded account keeps its last good numbers on screen by design. Alerting on
     * them would raise a warning about a possibly-hours-old reading at exactly the
     * moment the app has stopped trusting it.
     */
    @Test fun `a degraded account never alerts`() {
        for (h in listOf(Health.STALE, Health.ERROR, Health.AUTH)) {
            assertTrue("$h must not alert", LimitAlerts.evaluate(state(99, h), emptyMap()).alerts.isEmpty())
        }
    }

    @Test fun `the alert names the window duration, not just the label`() {
        assertEquals("5-hour", LimitAlerts.windowName(WindowKind.SESSION))
        assertEquals("7-day", LimitAlerts.windowName(WindowKind.WEEKLY_ALL))
        // A scoped window is labelled with a model name, so without this it would carry
        // no time period at all.
        assertEquals("7-day", LimitAlerts.windowName(WindowKind.WEEKLY_SCOPED))
    }

    @Test fun `alerts are keyed per account and window so they do not collide`() {
        val a = LimitAlerts.evaluate(state(95, kind = WindowKind.WEEKLY_ALL), emptyMap())
        val b = LimitAlerts.evaluate(state(95, kind = WindowKind.SESSION), a.thresholds)
        assertEquals("a different window still fires", 1, b.alerts.size)
        assertEquals(2, b.thresholds.size)
    }
}
