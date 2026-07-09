package com.bilicraft.handheld.storage

import kotlinx.serialization.Serializable

/**
 * 完整的登录会话，是 auth 链路的最终产物，也是唯一需要持久化的敏感数据。
 *
 * 设计要点：
 * - 这是一个不可变数据类（FP 风格），刷新 token 时产生新实例而非原地修改。
 * - msRefreshToken 是静默刷新的唯一凭据，丢失即需用户重新登录。
 * - mcAccessToken 有效期约 24h，靠 msRefreshToken 静默续期。
 */
@Serializable
data class AuthSession(
    val msRefreshToken: String,     // Microsoft refresh_token（长期，用于静默刷新）
    val mcAccessToken: String,      // Minecraft 服务器登录用的 access token
    val mcUuid: String,             // 玩家 UUID（无符号，无短横线形式由协议层处理）
    val mcUsername: String,         // 玩家名
    val mcTokenObtainedAt: Long,    // mcAccessToken 获取时间戳（ms），用于判断是否过期
    val mcTokenExpiresIn: Long      // mcAccessToken 有效期（秒）
) {
    /** 是否已接近过期（留 5 分钟缓冲），到点则触发静默刷新 */
    fun isMcTokenNearExpiry(nowMs: Long = System.currentTimeMillis()): Boolean {
        val expiryMs = mcTokenObtainedAt + (mcTokenExpiresIn - 300) * 1000
        return nowMs >= expiryMs
    }
}