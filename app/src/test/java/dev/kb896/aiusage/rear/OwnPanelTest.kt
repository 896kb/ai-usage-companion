package dev.kb896.aiusage.rear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnPanelTest {

    private val release = "dev.kb896.aiusage"
    private val dev = "dev.kb896.aiusage.dev"

    @Test fun `a component in any of the spellings seen`() {
        assertTrue(OwnPanel.names("taskId=1: dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity bounds=[0,0][1,1]", release))
        assertTrue(OwnPanel.names("topActivity=ComponentInfo{dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity}", dev))
        assertTrue(OwnPanel.names(" VRI-dev.kb896.aiusage/dev.kb[...]rear.RearActivity#1187", release))
    }

    @Test fun `the release's package is not found inside the dev build's, nor the other way`() {
        assertFalse(OwnPanel.names("dev.kb896.aiusage.dev/dev.kb896.aiusage.rear.RearActivity#3", release))
        assertFalse(OwnPanel.names("dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#3", dev))
    }

    @Test fun `a longer package ending in ours is not ours`() {
        assertFalse(OwnPanel.names("x.dev.kb896.aiusage/dev.kb896.aiusage.rear.RearActivity#3", release))
    }

    @Test fun `our package with another activity is not the panel`() {
        assertFalse(OwnPanel.names("dev.kb896.aiusage/dev.kb896.aiusage.ui.MainActivity#3 RearActivity", release))
    }

    @Test fun `the other build is the family less ourselves`() {
        assertTrue(OwnPanel.isOtherBuild(dev, release))
        assertTrue(OwnPanel.isOtherBuild(release, dev))
        assertFalse(OwnPanel.isOtherBuild(release, release))
        assertFalse(OwnPanel.isOtherBuild("dev.kb896.aiusagex", release))
        assertFalse(OwnPanel.isOtherBuild("org.mozilla.firefox", release))
    }
}
