package com.bilicraft.handheld.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReleaseNotes.humanize 的纯 JVM 单测。
 * 验证「按边界切割」策略：只保留 GitHub 自动生成段之前的作者手写公告，
 * 纯机器生成的 changelog 一律丢弃并兜底。
 */
class ReleaseNotesTest {

    @Test
    fun `空白输入返回兜底文案`() {
        assertEquals(ReleaseNotes.FALLBACK, ReleaseNotes.humanize(""))
        assertEquals(ReleaseNotes.FALLBACK, ReleaseNotes.humanize("   \n  \n"))
    }

    @Test
    fun `保留作者手写公告丢弃其后的开发者changelog`() {
        val raw = """
            本次更新新增快捷工具面板，并修复了若干闪退问题。
            建议所有玩家更新到最新版本。

            ## What's Changed
            * 修复登录偶发失败 by @dev1 in https://github.com/o/r/pull/12
            * 优化聊天渲染性能 by @dev2 in https://github.com/o/r/pull/15

            ## New Contributors
            * @newbie made their first contribution

            **Full Changelog**: https://github.com/o/r/compare/v1.0.0...v1.1.0
        """.trimIndent()

        val out = ReleaseNotes.humanize(raw)

        assertTrue("应保留手写公告", out.contains("新增快捷工具面板"))
        assertTrue("应保留手写公告", out.contains("建议所有玩家更新"))
        assertFalse("应丢弃开发者 changelog 正文", out.contains("修复登录偶发失败"))
        assertFalse("应丢弃 PR 署名", out.contains("@dev1"))
        assertFalse("应丢弃 PR 链接", out.contains("pull/12"))
        assertFalse("应丢弃标题", out.contains("What's Changed"))
        assertFalse("应丢弃贡献者段", out.contains("New Contributors"))
        assertFalse("应丢弃对比链接", out.contains("Full Changelog"))
    }

    @Test
    fun `无手写公告的纯自动生成body走兜底`() {
        val raw = """
            ## What's Changed
            * 修复登录偶发失败 by @dev1 in https://github.com/o/r/pull/12

            **Full Changelog**: https://github.com/o/r/compare/v1.0.0...v1.1.0
        """.trimIndent()
        assertEquals(ReleaseNotes.FALLBACK, ReleaseNotes.humanize(raw))
    }

    @Test
    fun `保留人写公告并去掉markdown标记`() {
        val raw = "**重大更新**：新增 `快捷工具` 面板，详见 [文档](https://x.y/doc)。"
        val out = ReleaseNotes.humanize(raw)
        assertEquals("重大更新：新增 快捷工具 面板，详见 文档。", out)
    }
}