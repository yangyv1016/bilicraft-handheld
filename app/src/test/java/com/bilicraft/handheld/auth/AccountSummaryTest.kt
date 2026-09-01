package com.bilicraft.handheld.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSummaryTest {
    @Test
    fun `active account is first and other accounts keep their order`() {
        val accounts = listOf(
            AccountSummary("first", "First", isActive = false, isOffline = false),
            AccountSummary("active", "Active", isActive = true, isOffline = true),
            AccountSummary("last", "Last", isActive = false, isOffline = true)
        )

        assertEquals(listOf("active", "first", "last"), activeAccountFirst(accounts).map { it.uuid })
    }
}
