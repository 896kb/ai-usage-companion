package dev.kb896.aiusage.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPickerListTest {
    private val b = AppPicker.App("com.b", "Brave")
    private val c = AppPicker.App("com.c", "Calculator")
    private val m = AppPicker.App("com.m", "Maps")
    private val all = listOf(b, c, m)

    @Test fun `pinned first in their order on the back, the rest as they came`() {
        assertEquals(listOf(m, b, c), AppPicker.arranged(all, listOf("com.m", "com.b"), ""))
    }

    @Test fun `a pinned app no longer installed isn't listed`() {
        assertEquals(listOf(b, c, m), AppPicker.arranged(all, listOf("com.gone"), ""))
    }

    @Test fun `search matches any part of the name, in any case, and ignores spaces round it`() {
        assertEquals(listOf(c), AppPicker.arranged(all, emptyList(), " CALC "))
        assertEquals(listOf(b, m), AppPicker.arranged(all, listOf("com.b"), "a").filter { it != c })
    }

    @Test fun `nothing matching is an empty list`() {
        assertEquals(emptyList<AppPicker.App>(), AppPicker.arranged(all, emptyList(), "zzz"))
    }
}
