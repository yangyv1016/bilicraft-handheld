package com.bilicraft.handheld.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 安全存储层：只负责把敏感数据加密落盘 / 读回，不理解 token 语义。
 *
 * 实现：Jetpack Security 的 EncryptedSharedPreferences
 * - 主密钥存于 Android Keystore（硬件级 TEE，不出安全芯片）
 * - 值用 AES-256-GCM 加密，键用 AES-256-SIV 确定性加密
 * - 满足「Token 永不明文存储」的硬性要求
 *
 * 对外接口刻意做成极窄的三个操作：save / load / clear。
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** 保存整个会话（序列化为 JSON 后再由 EncryptedSharedPreferences 加密） */
    fun saveSession(session: AuthSession) {
        prefs.edit()
            .putString(KEY_SESSION, json.encodeToString(session))
            .apply()
    }

    /** 读取会话，无则返回 null（首次启动或已登出） */
    fun loadSession(): AuthSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { json.decodeFromString<AuthSession>(raw) }.getOrNull()
    }

    /** 登出：抹除全部敏感数据 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "bilicraft_secure_store"
        const val KEY_SESSION = "auth_session"
        val json = Json { ignoreUnknownKeys = true }
    }
}