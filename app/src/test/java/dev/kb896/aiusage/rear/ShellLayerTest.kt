package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLayerTest {

    class Layer

    @Suppress("UNUSED_PARAMETER")
    class Both {
        fun setTrustedOverlay(layer: Layer, value: Int): Both = this
        fun setTrustedOverlay(layer: Layer, value: Boolean): Both = this
    }

    @Suppress("UNUSED_PARAMETER")
    class OnlyBoolean {
        fun setTrustedOverlay(layer: Layer, value: Boolean): OnlyBoolean = this
    }

    class Neither

    @Test fun `the int overload is preferred, with ENABLED`() {
        val call = ShellLayer.trustedOverlayCall(Both::class.java, Layer::class.java)!!
        assertEquals(Int::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(2, call.second)
    }

    @Test fun `older firmware gets the boolean overload, with true`() {
        val call = ShellLayer.trustedOverlayCall(OnlyBoolean::class.java, Layer::class.java)!!
        assertEquals(Boolean::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(true, call.second)
    }

    @Test fun `neither, and there is nothing to call`() {
        assertNull(ShellLayer.trustedOverlayCall(Neither::class.java, Layer::class.java))
    }

    @Test fun `the frame is on top, the sheet under it, the blocker under the sheet`() {
        assertTrue(ShellLayer.Z_FRAME > ShellLayer.Z_SHEET)
        assertTrue(ShellLayer.Z_SHEET > ShellLayer.Z_BLOCKER)
        assertEquals(Int.MAX_VALUE, ShellLayer.Z_FRAME)
    }
}
