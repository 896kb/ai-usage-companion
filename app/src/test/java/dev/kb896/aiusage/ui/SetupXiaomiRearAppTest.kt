package dev.kb896.aiusage.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import dev.kb896.aiusage.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupXiaomiRearAppTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the panel is not allowed until the onboarding is done`() {
        assertEquals(false, Setup.panelAllowed(context, dev.kb896.aiusage.rear.ShizukuBridge.Status(running = false, granted = false, rearDisplayId = 1)))
    }

    @Test
    fun `a phone without the app offers no row`() {
        assertNull(Setup.xiaomiRearAppInstalled(context))
        assertTrue(Setup.steps(context, shizukuGranted = true, shizukuRunning = true).none { it.kind == Setup.Kind.XIAOMI_APP })
    }

    /** Not an onboarding step any more: it is the show/hide toggle, and it keeps the panel off the back. */
    @Test
    fun `with the app installed it is no step, and it keeps the panel off the back`() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = Panel.XIAOMI_REAR_APP
            applicationInfo = ApplicationInfo().apply { packageName = Panel.XIAOMI_REAR_APP; flags = ApplicationInfo.FLAG_SYSTEM }
        })
        assertEquals(true, Setup.xiaomiRearAppInstalled(context))
        assertTrue(Setup.steps(context, shizukuGranted = true, shizukuRunning = true).none { it.kind == Setup.Kind.XIAOMI_APP })
        assertTrue(Setup.shownByXiaomi(context))
        assertEquals(false, Setup.panelAllowed(context, dev.kb896.aiusage.rear.ShizukuBridge.Status(running = true, granted = true, rearDisplayId = 1)))
    }

    @Test
    fun `a suspended app is out of the way, so the panel may go on the back`() {
        // HyperOS 4 (Android 17) lets shell only suspend a system app, not uninstall it
        // for one user — so suspended is the state the panel gets there.
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = Panel.XIAOMI_REAR_APP
            applicationInfo = ApplicationInfo().apply {
                packageName = Panel.XIAOMI_REAR_APP
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_SUSPENDED
            }
        })
        assertEquals(false, Setup.xiaomiRearAppInstalled(context))
        assertEquals(false, Setup.shownByXiaomi(context))
    }
}
