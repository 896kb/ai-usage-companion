package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FontFileInfoTest {
    private fun shipped(name: String) = File("src/main/res/font/$name")

    @Test
    fun `Outfit reads as a variable family`() {
        val info = FontFileInfo.read(shipped("outfit.ttf"))!!
        assertEquals("Outfit", info.family)
        assertTrue(info.variable)
        assertFalse(info.italic)
    }

    @Test
    fun `Departure Mono reads as a static family`() {
        val info = FontFileInfo.read(shipped("departure_mono.otf"))!!
        assertEquals("Departure Mono", info.family)
        assertFalse(info.variable)
    }

    @Test
    fun `anything that is not a font is null`() {
        val junk = File.createTempFile("not-a-font", ".ttf").apply { writeText("hello, this is not a font file at all") }
        assertNull(FontFileInfo.read(junk))
        junk.delete()
    }
}
