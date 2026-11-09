package dev.kb896.aiusage.rear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockFiguresTest {
    @Test fun `a day of twenty-four hours, zero-padded, no marker`() {
        assertEquals("09:05:03", ClockFigures.of(9, 5, 3, twelve = false).figures)
        assertEquals("23:59:59", ClockFigures.of(23, 59, 59, twelve = false).figures)
        assertNull(ClockFigures.of(9, 5, 3, twelve = false).marker)
    }

    @Test fun `twelve hours, the American way`() {
        assertEquals(" 9:05:03", ClockFigures.of(9, 5, 3, twelve = true).figures)
        assertEquals("AM", ClockFigures.of(9, 5, 3, twelve = true).marker)
        assertEquals("12:00:00", ClockFigures.of(0, 0, 0, twelve = true).figures)
        assertEquals("AM", ClockFigures.of(0, 0, 0, twelve = true).marker)
        assertEquals("12:30:00", ClockFigures.of(12, 30, 0, twelve = true).figures)
        assertEquals("PM", ClockFigures.of(12, 30, 0, twelve = true).marker)
        assertEquals(" 1:15:00", ClockFigures.of(13, 15, 0, twelve = true).figures)
        assertEquals("11:59:59", ClockFigures.of(23, 59, 59, twelve = true).figures)
        assertEquals("PM", ClockFigures.of(23, 59, 59, twelve = true).marker)
    }
}
