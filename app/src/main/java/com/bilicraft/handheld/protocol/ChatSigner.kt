package com.bilicraft.handheld.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
 * session 签名链状态（1.19.3/协议761+）。
 *
 * session 体系与旧的 1.19/1.19.1 逐条独立签名不同：客户端进服时生成一个 sessionId，
 * 之后每条消息带一个自增 index，签名覆盖 (sessionId, index, body)，形成一条链。
 * 服务器据此防重放、防乱序。
 *
 * 这里维护最小状态：sessionId（进服随机生成一次）+ 单调自增的 messageIndex。
 * lastSeen 消息链采用空链（进服无历史上下文），故不追踪已见消息摘要。
 */
class MessageChainState {
    val sessionId: UUID = UUID.randomUUID()
    private var messageIndex: Int = 0

    /** 取下一条消息的序号（从 0 开始，发送后自增） */
    fun nextIndex(): Int = messageIndex++
}

/**
 * 聊天消息签名器（session 体系，1.19.3+）。
 *
 * 签名算法：SHA256withRSA，对一段确定性拼装的字节签名。
 * 待签字节布局（session 体系）：
 *   int(1)                 签名版本前缀
 *   UUID sender            玩家 uuid
 *   UUID session           进服生成的 sessionId
 *   int index              消息序号
 *   long salt
 *   long timestampSeconds
 *   int msgLen | msgBytes  UTF-8 消息内容
 *   int lastSeenCount(0)   空 lastSeen 链
 *
 * 【需校准】此布局的字节序、字段顺序对签名有效性极敏感，必须对真实
 * enforce-secure-profile 服务器抓包核对。私钥仅在本对象内存生命周期使用。
 */
class ChatSigner(
    private val privateKey: PrivateKey,
    private val playerUuid: UUID,
    private val sessionId: UUID
) {
    /** 一条已签名消息的签名字节（256 字节定长 RSA 签名） */
    data class SignedMessage(
        val message: String,
        val timestamp: Long,
        val salt: Long,
        val index: Int,
        val signature: ByteArray
    )

    fun sign(message: String, timestamp: Long, salt: Long, index: Int): SignedMessage {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(buildSignable(message, timestamp, salt, index))
            sign()
        }
        return SignedMessage(message, timestamp, salt, index, signature)
    }

    private fun buildSignable(message: String, timestamp: Long, salt: Long, index: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(1)                                   // 签名版本前缀
        d.writeLong(playerUuid.mostSignificantBits)
        d.writeLong(playerUuid.leastSignificantBits)
        d.writeLong(sessionId.mostSignificantBits)
        d.writeLong(sessionId.leastSignificantBits)
        d.writeInt(index)                               // 消息序号（session 链）
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