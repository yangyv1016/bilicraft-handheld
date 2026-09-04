package com.bilicraft.handheld.announcement

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementRepositoryTest {
    @Test
    fun `invalid and duplicate announcements are removed without changing CDN order`() {
        val first = AnnouncementEntry(
            id = "new",
            type = AnnouncementEntry.TYPE_MANUAL,
            title = "最新公告",
            content = "公告内容",
            publishedAt = "2026-09-01T00:00:00Z"
        )
        val duplicate = first.copy(title = "重复公告")
        val invalid = first.copy(id = "", title = "无效公告")
        val older = first.copy(
            id = "old",
            type = AnnouncementEntry.TYPE_RELEASE,
            title = "版本更新",
            versionName = "1.2.1"
        )

        val normalized = normalizeAnnouncements(listOf(first, duplicate, invalid, older))

        assertEquals(listOf("new", "old"), normalized.map { it.id })
        assertEquals("最新公告", normalized.first().title)
    }
}
