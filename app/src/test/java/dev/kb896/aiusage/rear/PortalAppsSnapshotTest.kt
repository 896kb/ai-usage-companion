package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalAppsSnapshotTest {
    @Test fun iconKeyNaming() {
        assertEquals("icon:com.a", PortalAppsSnapshot.iconKey("com.a"))
    }

    @Test fun capsAtTheSheetsMaximumAndDropsDuplicates() {
        val pkgs = (1..20).map { "p$it" } + "p1"
        val s = PortalAppsSnapshot.of(pkgs, pkgs, emptyMap())
        assertEquals(PortalSheet.MAX_APPS, s.size)
        assertEquals("p1", s.packages.first())
        assertEquals(s.packages, s.labels)
    }

    @Test fun missingLabelFallsBackToThePackage() {
        val s = PortalAppsSnapshot.of(listOf("a", "b"), listOf("Alpha"), emptyMap())
        assertEquals(listOf("Alpha", "b"), s.labels)
    }

    @Test fun iconsPastTheBudgetGoWithoutAndTheRestKeepTheirPlaces() {
        val icons = listOf(ByteArray(40), null, ByteArray(50), ByteArray(20), ByteArray(5))
        val kept = PortalAppsSnapshot.withinBudget(icons, 100)
        assertEquals(listOf(40, null, 50, null, null), kept.map { it?.size })
    }

    @Test fun iconsWithinTheBudgetAreAllKept() {
        val icons = listOf(ByteArray(40), ByteArray(60))
        assertEquals(icons, PortalAppsSnapshot.withinBudget(icons, 100))
    }
}
