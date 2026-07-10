package com.bilicraft.handheld.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 多账户加密存储的持久化模型。
 *
 * - accounts 以 mcUuid 为主键，天然去重（同一微软账号重复登录只更新不新增）。
 * - activeUuid 是当前活跃账户指针；为 null 或指向不存在的账户时视为未登录。
 * - 整体序列化为 JSON 后由 EncryptedSharedPreferences 加密落盘。
 */
@Serializable
private data class AccountStore(
    val accounts: List<AuthSession> = emptyList(),
    val activeUuid: String? = null
) {
    /** 当前活跃账户；指针悬空则返回 null */
    fun activeSession(): AuthSession? = accounts.firstOrNull { it.mcUuid == activeUuid }
}

/**
 * 安全存储层：只负责把敏感数据加密落盘 / 读回，不理解 token 语义。
 *
 * 实现：Jetpack Security 的 EncryptedSharedPreferences
 * - 主密钥存于 Android Keystore（硬件级 TEE，不出安全芯片）
 * - 值用 AES-256-GCM 加密，键用 AES-256-SIV 确定性加密
 * - 满足「Token 永不明文存储」的硬性要求
 *
 * 多账户模型：内部维护 AccountStore（账户表 + 活跃指针）。
 * 对外接口保持窄小，且语义向下兼容单账户调用点：
 * - loadSession()      读活跃账户
 * - saveSession(s)     按 mcUuid upsert 并设为活跃（登录/刷新共用）
 * - clear()            清空全部账户
 * 多账户新增：loadAllAccounts / setActiveAccount / removeAccount
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

    /**
     * upsert 一个会话并设为活跃账户。
     * 按 mcUuid 匹配：已存在则替换，不存在则追加。登录首登与静默刷新都走这里。
     */
    fun saveSession(session: AuthSession) {
        val current = readStore()
        val merged = current.accounts.filterNot { it.mcUuid == session.mcUuid } + session
        writeStore(current.copy(accounts = merged, activeUuid = session.mcUuid))
    }

    /** 读取活跃账户，无则返回 null（首次启动或已登出） */
    fun loadSession(): AuthSession? = readStore().activeSession()

    /** 读取全部账户（UI 账户列表用），顺序即持久化顺序 */
    fun loadAllAccounts(): List<AuthSession> = readStore().accounts

    /** 切换活跃账户；uuid 不存在则不改动。返回切换后的活跃账户。 */
    fun setActiveAccount(uuid: String): AuthSession? {
        val current = readStore()
        if (current.accounts.none { it.mcUuid == uuid }) return current.activeSession()
        writeStore(current.copy(activeUuid = uuid))
        return current.accounts.first { it.mcUuid == uuid }
    }

    /**
     * 移除指定账户。若移除的是活跃账户，活跃指针回退到剩余列表的第一个（无剩余则为 null）。
     * 返回移除后的活跃账户（可能为 null）。
     */
    fun removeAccount(uuid: String): AuthSession? {
        val current = readStore()
        val remaining = current.accounts.filterNot { it.mcUuid == uuid }
        val nextActive = when {
            current.activeUuid != uuid -> current.activeUuid   // 移除的不是活跃账户，指针不变
            else -> remaining.firstOrNull()?.mcUuid            // 移除活跃账户 → 顺延到第一个
        }
        writeStore(AccountStore(accounts = remaining, activeUuid = nextActive))
        return remaining.firstOrNull { it.mcUuid == nextActive }
    }

    /** 登出全部：抹除所有账户 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // ---- 内部读写与迁移 ----

    /**
     * 读取账户表。优先读新格式；若不存在但存在旧单槽 auth_session，
     * 则一次性迁移为单账户表并回写（已登录用户升级后不掉线）。
     */
    private fun readStore(): AccountStore {
        prefs.getString(KEY_ACCOUNTS, null)?.let { raw ->
            runCatching { json.decodeFromString<AccountStore>(raw) }.getOrNull()?.let { return it }
        }
        val legacy = prefs.getString(KEY_LEGACY_SESSION, null)
            ?.let { runCatching { json.decodeFromString<AuthSession>(it) }.getOrNull() }
            ?: return AccountStore()
        return AccountStore(accounts = listOf(legacy), activeUuid = legacy.mcUuid)
            .also { writeStore(it) }
    }

    /** 写入账户表，并顺手清掉旧单槽键，避免迁移后残留明文键位 */
    private fun writeStore(store: AccountStore) {
        prefs.edit()
            .putString(KEY_ACCOUNTS, json.encodeToString(store))
            .remove(KEY_LEGACY_SESSION)
            .apply()
    }

    private companion object {
        const val PREFS_FILE_NAME = "bilicraft_secure_store"
        const val KEY_ACCOUNTS = "auth_accounts"       // 多账户表
        const val KEY_LEGACY_SESSION = "auth_session"  // 旧单槽键，仅用于迁移
        val json = Json { ignoreUnknownKeys = true }
    }
}