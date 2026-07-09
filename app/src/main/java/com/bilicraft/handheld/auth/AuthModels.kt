package com.bilicraft.handheld.auth

/**
 * auth 模块的领域模型。全部为不可变数据类，每一步变换产生新实例。
 * 这些类型只在 auth 模块内部流转，对外只暴露最终的 AuthSession。
 */

/** 设备码流程第一步的产物：给用户看的授权信息 */
data class DeviceCodeInfo(
    val deviceCode: String,        // 内部轮询用
    val userCode: String,          // 展示给用户，让其在浏览器输入
    val verificationUri: String,   // 用户打开的授权页
    val interval: Int,             // 轮询间隔（秒）
    val expiresIn: Int             // 设备码有效期（秒）
)

/** Microsoft OAuth token */
data class MsToken(
    val accessToken: String,
    val refreshToken: String
)

/** Xbox Live token（含 userhash，XSTS 与 MC 登录都要用） */
data class XblToken(
    val token: String,
    val userHash: String
)

/** XSTS token */
data class XstsToken(
    val token: String,
    val userHash: String
)

/** Minecraft 服务端 access token */
data class McToken(
    val accessToken: String,
    val expiresIn: Long
)

/** 玩家档案 */
data class McProfile(
    val uuid: String,
    val name: String
)

/**
 * 玩家签名证书（强制签名模式必需）。
 *
 * 来自 Mojang 的 player/certificates 接口，含一对 RSA 密钥：
 * - privateKey 用于对聊天消息签名（敏感，只在内存中用，绝不落盘）
 * - publicKeyDer + publicKeySignatureV2 在 Login/进服时上报给服务器，
 *   服务器据此验证后续聊天签名的真实性
 * expiresAt 约 48h，过期需重新获取。
 */
data class PlayerCertificate(
    val privateKey: java.security.PrivateKey,
    val publicKeyDer: ByteArray,            // X.509 DER 编码的公钥
    val publicKeySignatureV2: ByteArray,    // Mojang 对公钥的签名（1.19.1+ 用 V2）
    val expiresAtEpochMs: Long
)

/**
 * 登录流程状态机（对 UI 暴露）。
 * UI 只需 when 一下即可渲染每个阶段，不接触任何 HTTP 细节。
 */
sealed interface AuthState {
    data object Idle : AuthState
    data object RequestingDeviceCode : AuthState

    /** 等待用户在浏览器授权：UI 据此展示 userCode + 打开 verificationUri */
    data class WaitingForUser(val info: DeviceCodeInfo) : AuthState

    /** 已拿到 MS token，正在换取 XBL/XSTS/MC token */
    data object ExchangingTokens : AuthState

    /** 登录成功 */
    data class Success(val profile: McProfile) : AuthState

    /** 失败（附带人类可读原因） */
    data class Failed(val reason: String) : AuthState
}