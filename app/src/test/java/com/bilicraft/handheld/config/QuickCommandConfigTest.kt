package com.bilicraft.handheld.config

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCommandConfigTest {
    @Test
    fun `plain chat content stays plain`() {
        assertEquals("大家好", QuickCommandConfig.normalizeCommand("  大家好  "))
    }

    @Test
    fun `existing leading slash is preserved`() {
        assertEquals("/signin click", QuickCommandConfig.normalizeCommand(" /signin click "))
    }
}
