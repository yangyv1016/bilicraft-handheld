package com.bilicraft.handheld.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAccountTest {
    @Test
    fun `generates vanilla compatible offline uuid`() {
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", OfflineAccount.uuidFor("Notch"))
    }

    @Test
    fun `accepts standard minecraft username`() {
        assertNull(OfflineAccount.validateUsername("Player_123"))
    }

    @Test
    fun `rejects invalid username characters and length`() {
        assertTrue(OfflineAccount.validateUsername("ab")!!.contains("3–16"))
        assertTrue(OfflineAccount.validateUsername("玩家名")!!.contains("只能包含"))
        assertTrue(OfflineAccount.validateUsername("name-with-dash")!!.contains("只能包含"))
    }
}
