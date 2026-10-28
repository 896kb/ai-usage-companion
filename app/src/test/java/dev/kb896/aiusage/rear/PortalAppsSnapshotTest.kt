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
}
