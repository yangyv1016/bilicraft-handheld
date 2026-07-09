package com.bilicraft.handheld.protocol

import java.security.PrivateKey
import java.security.Signature
import java.util.UUID

/**
 * 聊天签名模式（用户可选）。
 *
 * - UNSIGNED：默认。发送未签名聊天结构，兼容离线服 / 未开启 enforce-secure-profile 的服务器。
 * - SIGNED：取真实玩家证书 + 私钥对消息签名，面向 enforce-secure-profile=true 的正版服务器。
 */
enum class ChatSigningMode { UNSIGNED, SIGNED }

/**
 * 聊天消息签名器（1.19+）。
 *
 * MC 的签名算法（1.19 / 1.19.1）：对一段确定性拼装的字节做 SHA256withRSA。
 * 签名内容随版本演进：
 *   1.19    (759)：签名覆盖 [salt, uuid, timestamp, 消息内容]
 *   1.19.1+ (760)：引入 lastSeen 消息链，签名还需覆盖已见消息更新
 *
 * 本签名器实现 1.19/1.19.1 的基础签名路径；lastSeen 采用空链（不追踪历史消息），
 * 适配刚进服、无消息上下文的发送场景。详见 README「已知限制」。
 *
 * 私钥来自 PlayerCertificate，只在本对象内存生命周期使用。
 */
class ChatSigner(
    private val privateKey: PrivateKey,
    private val playerUuid: UUID,
    private val protocolNumber: Int
) {
    /** 一条已签名消息的全部字段，供 MinecraftClient 写入包体 */
    data class SignedMessage(
        val message: String,
        val timestamp: Long,
        val salt: Long,
        val signature: ByteArray
    )

    /**
     * 对消息签名。
     * 说明：不同小版本的「待签字节」布局有差异，这里实现 1.19.1(760) 的常见布局：
     *   signable = [1 (版本前缀)] + [senderUuid] + [sessionUuid=0] + ...（见下）
     * 为保持可读与可维护，采用官方公开的字段顺序拼装。
     */
    fun sign(message: String, timestamp: Long, salt: Long): SignedMessage {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(buildSignable(message, timestamp, salt))
            sign()
        }
        return SignedMessage(message, timestamp, salt, signature)
    }

    /**
     * 拼装待签名字节。
     * 1.19.1+ 布局（简化空 lastSeen）：
     *   int(1) | UUID sender | UUID session(全0) | long index(0)
     *   | long salt | long timestampSeconds | int msgLen | msgBytes | int lastSeenCount(0)
     */
    private fun buildSignable(message: String, timestamp: Long, salt: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeInt(1)                                   // 签名版本前缀
        d.writeLong(playerUuid.mostSignificantBits)
        d.writeLong(playerUuid.leastSignificantBits)
        d.writeLong(0L); d.writeLong(0L)                // sessionId（进服前用全 0）
        d.writeLong(0L)                                 // message index
        d.writeLong(salt)
        d.writeLong(timestamp / 1000L)                  // 秒
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        d.writeInt(msgBytes.size)
        d.write(msgBytes)
        d.writeInt(0)                                   // lastSeen 数量：空链
        d.flush()
        return out.toByteArray()
    }
}