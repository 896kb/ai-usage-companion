package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalFrameTrustTest {

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
        val call = PortalFrame.trustedOverlayCall(Both::class.java, Layer::class.java)!!
        assertEquals(Int::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(2, call.second)
    }

    @Test fun `older firmware gets the boolean overload, with true`() {
        val call = PortalFrame.trustedOverlayCall(OnlyBoolean::class.java, Layer::class.java)!!
        assertEquals(Boolean::class.javaPrimitiveType, call.first.parameterTypes[1])
        assertEquals(true, call.second)
    }

    @Test fun `neither, and there is nothing to call`() {
        assertNull(PortalFrame.trustedOverlayCall(Neither::class.java, Layer::class.java))
    }
}
