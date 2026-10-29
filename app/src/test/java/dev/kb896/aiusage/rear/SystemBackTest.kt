package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemBackTest {

    @Test fun `the Back begins at the rear's right edge, halfway down, in its pixels`() {
        assertEquals(903f to 286f, SystemBack.touchPoint(RearGeometry.PRO17))
        assertEquals(975f to 298f, SystemBack.touchPoint(RearGeometry(976, 596, 296, 110, 450)))
    }
}
