package com.bilicraft.handheld.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginServerBindingTest {
    @Test
    fun `replacing one plugin servers preserves other plugin bindings`() {
        val current = listOf(
            PluginServerBinding("server-a", "plugin-a"),
            PluginServerBinding("server-a", "plugin-b")
        )

        val next = replacePluginServerBindings(current, "plugin-a", setOf("server-c", "server-b"))

        assertEquals(
            listOf(
                PluginServerBinding("server-a", "plugin-b"),
                PluginServerBinding("server-b", "plugin-a"),
                PluginServerBinding("server-c", "plugin-a")
            ),
            next
        )
    }

    @Test
    fun `empty server selection unloads plugin everywhere`() {
        val current = listOf(PluginServerBinding("server-a", "plugin-a"))

        assertEquals(emptyList<PluginServerBinding>(), replacePluginServerBindings(current, "plugin-a", emptySet()))
    }
}
