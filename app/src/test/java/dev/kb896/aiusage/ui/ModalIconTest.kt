package dev.kb896.aiusage.ui

import dev.kb896.aiusage.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModalIconTest {
    @Test fun `yes is a tick, no a cross, delete a bin`() {
        for (word in listOf("Use it", "Done", "OK", "Continue")) assertEquals(R.drawable.ic_check, Modal.iconFor(word))
        for (word in listOf("Cancel", "Close", "Keep")) assertEquals(R.drawable.ic_close, Modal.iconFor(word))
        assertEquals(R.drawable.ic_trash, Modal.iconFor("Remove"))
    }

    @Test fun `words an icon would only guess at stay words`() {
        for (word in listOf("Sign out", "Import", "Reset", "Reset (5)", "Default", "Privacy policy", "")) assertNull(Modal.iconFor(word))
        assertNull(Modal.iconFor(null))
    }
}
