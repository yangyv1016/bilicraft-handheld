package com.bilicraft.handheld.update

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadProgressTest {
    @Test
    fun `uses release asset size when streamed response has unknown length`() {
        assertEquals(47_266_506L, resolveDownloadTotalBytes(-1L, 47_266_506L))
    }

    @Test
    fun `prefers response content length when available`() {
        assertEquals(1024L, resolveDownloadTotalBytes(1024L, 2048L))
    }

    @Test
    fun `keeps progress indeterminate when neither size is available`() {
        assertEquals(-1L, resolveDownloadTotalBytes(-1L, 0L))
    }
}
