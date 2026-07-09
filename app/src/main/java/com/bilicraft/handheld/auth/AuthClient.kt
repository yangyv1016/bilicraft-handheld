package com.bilicraft.handheld.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Microsoft → XBL → XSTS → MC 的 HTTP 变换实现。
 *
 * 设计：每个方法是一个独立的、无副作用的 suspend 变换（输入 token → 输出 token），
 * 编排逻辑放在 AuthManager，本类只管「一次 HTTP 往返 + 解析」。
 * 这样每一步都可单独测试、单独重试，符合职责分明原则。
 *
 * client_id 来自 BuildConfig.MS_CLIENT_ID（PrismLauncher 公开 id，可在 README 指引下替换）。
 */
class AuthClient(private val clientId: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 结果类型：成功携带值，失败携带原因。避免异常穿透到编排层。 */
    sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>
        data class Err(val reason: String) : Step<Nothing>
    }

    // ---- Device Code Flow ----

    /** 第一步：申请设备码 */
    suspend fun requestDeviceCode(): Step<DeviceCodeInfo> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val req = Request.Builder().url(DEVICE_CODE_URL).post(body).build()
        execJson(req) { j ->
            DeviceCodeInfo(
                deviceCode = j.getString("device_code"),
                userCode = j.getString("user_code"),
                verificationUri = j.getString("verification_uri"),
                interval = j.optInt("interval", 5),
                expiresIn = j.optInt("expires_in", 900)
            )
        }
    }

    /**
     * 轮询取 token。返回三态：
     * - Ok：授权完成
     * - Pending：用户还没授权（调用方 sleep(interval) 后重试）
     * - Err：致命错误（过期/拒绝）
     */
    sealed interface PollResult {
        data class Ok(val token: MsToken) : PollResult
        data object Pending : PollResult
        data class Err(val reason: String) : PollResult
    }

    suspend fun pollForToken(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val j = JSONObject(text)
                if (resp.isSuccessful) {
                    PollResult.Ok(
                        MsToken(
                            accessToken = j.getString("access_token"),
                            refreshToken = j.getString("refresh_token")
                        )
                    )
                } else when (j.optString("error")) {
                    "authorization_pending" -> PollResult.Pending
                    "slow_down" -> PollResult.Pending
                    "expired_token" -> PollResult.Err("授权超时，请重新登录")
                    "authorization_declined" -> PollResult.Err("已取消授权")
                    else -> PollResult.Err(j.optString("error_description", "登录失败"))
                }
            }
        } catch (e: IOException) {
            PollResult.Err("网络错误：${e.message}")
        }
    }

    /** 静默刷新：用 refresh_token 换新的 MS token */
    suspend fun refreshMsToken(refreshToken: String): Step<MsToken> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .add("refresh_token", refreshToken)
            .build()
        val req = Request.Builder().url(TOKEN_URL).post(body).build()
        execJson(req) { j ->
            MsToken(
                accessToken = j.getString("access_token"),
                refreshToken = j.optString("refresh_token", refreshToken)
            )
        }
    }

    // ---- token 变换链 ----

    /** MS access token → XBL token */
    suspend fun authXbl(msAccessToken: String): Step<XblToken> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", "d=$msAccessToken")
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }
        val req = Request.Builder().url(XBL_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        execJson(req) { j ->
            XblToken(
                token = j.getString("Token"),
                userHash = j.getJSONObject("DisplayClaims")
                    .getJSONArray("xui").getJSONObject(0).getString("uhs")
            )
        }
    }

    /** XBL token → XSTS token */
    suspend fun authXsts(xblToken: String): Step<XstsToken> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", org.json.JSONArray().put(xblToken))
            })
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        }
        val req = Request.Builder().url(XSTS_URL)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val j = JSONObject(text)
                if (resp.isSuccessful) {
                    Step.Ok(
                        XstsToken(
                            token = j.getString("Token"),
                            userHash = j.getJSONObject("DisplayClaims")
                                .getJSONArray("xui").getJSONObject(0).getString("uhs")
                        )
                    )
                } else {
                    // XSTS 特有错误码：未注册 Xbox、儿童账户等
                    val reason = when (j.optLong("XErr")) {
                        2148916233L -> "该微软账户没有 Xbox 档案，请先在 xbox.com 创建"
                        2148916238L -> "未成年账户需加入家庭组"
                        else -> "XSTS 认证失败"
                    }
                    Step.Err(reason)
                }
            }
        } catch (e: IOException) {
            Step.Err("网络错误：${e.message}")
        }
    }

    /** XSTS token → Minecraft access token */
    suspend fun loginMinecraft(userHash: String, xstsToken: String): Step<McToken> =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("identityToken", "XBL3.0 x=$userHash;$xstsToken")
            }
            val req = Request.Builder().url(MC_LOGIN_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            execJson(req) { j ->
                McToken(
                    accessToken = j.getString("access_token"),
                    expiresIn = j.optLong("expires_in", 86400)
                )
            }
        }

    /** 验权：检查账户是否拥有 Minecraft */
    suspend fun checkEntitlements(mcAccessToken: String): Step<Boolean> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(MC_ENTITLEMENTS_URL)
                .header("Authorization", "Bearer $mcAccessToken")
                .get().build()
            execJson(req) { j ->
                val items = j.optJSONArray("items")
                (items?.length() ?: 0) > 0
            }
        }

    /** 拉取玩家档案（UUID + 名字） */
    suspend fun fetchProfile(mcAccessToken: String): Step<McProfile> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(MC_PROFILE_URL)
                .header("Authorization", "Bearer $mcAccessToken")
                .get().build()
            execJson(req) { j ->
                McProfile(uuid = j.getString("id"), name = j.getString("name"))
            }
        }

    /**
     * 获取玩家签名证书（强制签名模式用）。
     * 仅在用户选择「强制签名」时调用；返回的私钥只在内存流转，不落盘。
     */
    suspend fun fetchCertificate(mcAccessToken: String): Step<PlayerCertificate> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(MC_CERTIFICATES_URL)
                .header("Authorization", "Bearer $mcAccessToken")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            execJson(req) { j ->
                val keyPair = j.getJSONObject("keyPair")
                PlayerCertificate(
                    privateKey = parsePrivateKey(keyPair.getString("privateKey")),
                    publicKeyDer = pemToDer(keyPair.getString("publicKey")),
                    publicKeySignatureV2 = android.util.Base64.decode(
                        j.getString("publicKeySignatureV2"), android.util.Base64.DEFAULT
                    ),
                    expiresAtEpochMs = parseIsoToEpochMs(j.getString("expiresAt"))
                )
            }
        }

    /** PEM 私钥 → PrivateKey（PKCS#8） */
    private fun parsePrivateKey(pem: String): java.security.PrivateKey {
        val der = pemToDer(pem)
        val spec = java.security.spec.PKCS8EncodedKeySpec(der)
        return java.security.KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /** 剥离 PEM 头尾与换行，Base64 解码为 DER 字节 */
    private fun pemToDer(pem: String): ByteArray {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace("\\s".toRegex(), "")
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT)
    }

    private fun parseIsoToEpochMs(iso: String): Long =
        runCatching {
            java.time.Instant.parse(iso).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis() + 40L * 3600 * 1000)

    // ---- 内部工具：执行请求 + 解析，异常转 Err ----

    private inline fun <T> execJson(req: Request, parse: (JSONObject) -> T): Step<T> =
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    Step.Ok(parse(JSONObject(text)))
                } else {
                    Step.Err("请求失败(${resp.code})")
                }
            }
        } catch (e: IOException) {
            Step.Err("网络错误：${e.message}")
        } catch (e: Exception) {
            Step.Err("解析失败：${e.message}")
        }

    companion object {
        // scope 硬性包含 XboxLive.signin 与 offline_access
        private const val SCOPE = "XboxLive.signin offline_access"
        private const val DEVICE_CODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        private const val TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
        private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        private const val MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox"
        private const val MC_ENTITLEMENTS_URL =
            "https://api.minecraftservices.com/entitlements/mcstore"
        private const val MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile"
        private const val MC_CERTIFICATES_URL =
            "https://api.minecraftservices.com/player/certificates"
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}