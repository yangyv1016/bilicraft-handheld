package com.bilicraft.handheld.auth

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Minecraft Java 版离线账号规则与标准 OfflinePlayer UUID 生成。 */
object OfflineAccount {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{3,16}$")

    fun normalizeUsername(raw: String): String = raw.trim()

    fun validateUsername(username: String): String? = when {
        username.length !in 3..16 -> "玩家名需为 3–16 个字符"
        !usernamePattern.matches(username) -> "玩家名只能包含英文字母、数字和下划线"
        else -> null
    }

    fun uuidFor(username: String): String = UUID.nameUUIDFromBytes(
        "OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8)
    ).toString().replace("-", "")
}
