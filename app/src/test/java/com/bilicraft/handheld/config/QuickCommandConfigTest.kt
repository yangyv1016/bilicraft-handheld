package com.bilicraft.handheld.config

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCommandConfigTest {
    @Test
    fun `command input gets a leading slash`() {
        assertEquals("/signin click", QuickCommandConfig.normalizeCommand(" signin click "))
    }

    @Test
    fun `existing leading slash is preserved`() {
        assertEquals("/signin click", QuickCommandConfig.normalizeCommand(" /signin click "))
    }
}
