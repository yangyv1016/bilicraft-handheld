package com.bilicraft.handheld.protocol

/**
 * 协议层对外的唯一数据契约。
 *
 * 架构关键：protocol 模块内部处理 VarInt / 压缩 / 加密 / 版本差异 / packet id，
 * 但这些细节**绝不外泄**。UI 与插件只消费下面这几个语义事件。
 * 这样插件不会因为协议版本变化而失效，也不可能误操作原始字节。
 */

/** 服务器 → 客户端 的聊天/系统消息（已从各版本 packet 归一化） */
data class ChatEvent(
    val plainText: String,      // 去格式化后的纯文本（插件匹配关键字用）
    val rawJson: String,        // 原始 JSON/NBT 文本组件（需要富文本时用）
    val sender: String? = null, // 玩家消息的发送者名（系统消息为 null）
    val timestamp: Long = System.currentTimeMillis(),
    val spans: List<ChatSpan> = emptyList()  // 富文本片段（UI 上色用）；空表示按 plainText 纯色显示
)

/**
 * 富文本片段：一段连续、样式一致的文本。
 *
 * 这是协议层对「颜色/格式」的语义化表达——UI 只认这个结构，不接触 §x 代码或 JSON color 字段。
 * color 用 RGB 整数（0xRRGGBB），null 表示用 UI 默认前景色（兼容命名色与 1.16+ 十六进制色）。
 */
data class ChatSpan(
    val text: String,
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false
)

/** 连接生命周期状态（UI 展示 + service 决定是否重连） */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object LoggingIn : ConnectionState          // 登录/加密握手阶段
    data class Connected(val serverBrand: String?) : ConnectionState
    data class Failed(val reason: String, val retriable: Boolean = true) : ConnectionState
    data class Reconnecting(val attempt: Int) : ConnectionState
}

/** 连接目标 */
data class ServerAddress(
    val host: String,
    val port: Int = 25565
)