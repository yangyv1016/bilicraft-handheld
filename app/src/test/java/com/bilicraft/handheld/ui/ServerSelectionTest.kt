package com.bilicraft.handheld.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSelectionTest {
    @Test
    fun `selected index is clamped before a shortened list is rendered`() {
        assertEquals(1, validSelectedIndex(selectedIndex = 2, itemCount = 2))
        assertEquals(0, validSelectedIndex(selectedIndex = 1, itemCount = 1))
    }

    @Test
    fun `empty server list keeps a harmless zero index`() {
        assertEquals(0, validSelectedIndex(selectedIndex = 3, itemCount = 0))
    }
}
