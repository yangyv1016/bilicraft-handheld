package com.bilicraft.handheld.protocol

/**
 * 版本差异收敛层：把「逻辑包」映射到具体协议号下的包 id。
 *
 * 为什么需要它：MC 每个大版本都可能重排包 id，且 1.20.2(协议764)+ 新增了
 * 独立的 configuration 阶段。若把这些散落在连接逻辑里，版本一多就是灾难。
 * 这里用一个 profile 把差异集中，连接状态机只认逻辑包名。
 *
 * 覆盖策略（对齐「聊天核心优先」）：现代档案覆盖 1.20.2–1.21.x（含 config 阶段）；
 * 传统档案覆盖 1.13–1.20.1（无独立 config 阶段）。更老版本可继续加档案。
 */
data class PacketProfile(
    val hasConfigurationPhase: Boolean,   // 1.20.2+ 为 true

    // --- serverbound（客户端发出）---
    val sbLoginStart: Int,
    val sbEncryptionResponse: Int,
    val sbLoginAck: Int?,                 // 仅 config 阶段版本：登录完成确认
    val sbConfigFinishAck: Int?,          // 客户端确认 config 结束
    val sbKeepAlivePlay: Int,
    val sbChatMessage: Int,               // play 阶段发送聊天

    // --- clientbound（服务器发来）---
    val cbEncryptionRequest: Int,
    val cbLoginSuccess: Int,
    val cbSetCompression: Int,
    val cbConfigFinish: Int?,             // config 阶段结束（进入 play）
    val cbConfigKeepAlive: Int?,
    val cbKeepAlivePlay: Int,
    val cbChatCandidates: Set<Int>        // 各类聊天/系统消息包 id（版本差异大，用集合宽松匹配）
) {
    companion object {
        /**
         * 现代档案：协议 764（1.20.2）及以上，含 configuration 阶段。
         * 说明：不同 1.2x 小版本 play 阶段聊天包 id 略有浮动，
         * cbChatCandidates 用集合覆盖常见取值，宽松匹配以保证聊天可读。
         */
        fun modern(protocol: Int) = PacketProfile(
            hasConfigurationPhase = true,
            sbLoginStart = 0x00,
            sbEncryptionResponse = 0x01,
            sbLoginAck = 0x03,
            sbConfigFinishAck = 0x03,
            sbKeepAlivePlay = 0x18,
            sbChatMessage = 0x06,
            cbEncryptionRequest = 0x01,
            cbLoginSuccess = 0x02,
            cbSetCompression = 0x03,
            cbConfigFinish = 0x02,
            cbConfigKeepAlive = 0x04,
            cbKeepAlivePlay = 0x26,
            cbChatCandidates = setOf(0x6C, 0x6D, 0x70, 0x73)
        )

        /** 传统档案：协议 393（1.13）至 763（1.20.1），无独立 config 阶段 */
        fun legacy(protocol: Int) = PacketProfile(
            hasConfigurationPhase = false,
            sbLoginStart = 0x00,
            sbEncryptionResponse = 0x01,
            sbLoginAck = null,
            sbConfigFinishAck = null,
            sbKeepAlivePlay = 0x11,
            sbChatMessage = 0x05,
            cbEncryptionRequest = 0x01,
            cbLoginSuccess = 0x02,
            cbSetCompression = 0x03,
            cbConfigFinish = null,
            cbConfigKeepAlive = null,
            cbKeepAlivePlay = 0x21,
            cbChatCandidates = setOf(0x0E, 0x0F, 0x5F, 0x62, 0x64)
        )

        /** 按协议号选择档案 */
        fun forProtocol(protocol: Int): PacketProfile =
            if (protocol >= 764) modern(protocol) else legacy(protocol)
    }
}