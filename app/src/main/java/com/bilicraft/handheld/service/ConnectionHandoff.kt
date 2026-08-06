package com.bilicraft.handheld.service

import android.content.Context
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.protocol.ServerAddress
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionType

/** 一次连接请求的最小可重放快照。 */
data class ConnectionHandoff(
    val serverId: String?,
    val address: ServerAddress,
    val version: McVersion,
    val signingMode: ChatSigningMode
)

/**
 * 进程被系统回收后的连接续跑凭据。
 *
 * START_STICKY 重建 Service 时 intent 恒为 null，没有这份快照就只会起一个空转的前台服务。
 * 用户主动断开时清空，避免「手动停止后又被系统重启自动连回去」。
 *
 * 用 SharedPreferences 而非 SecureStore：这里只有服务器地址和版本，没有任何凭证。
 */
class ConnectionHandoffStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("connection_handoff", Context.MODE_PRIVATE)

    fun save(handoff: ConnectionHandoff) {
        prefs.edit()
            .putString(KEY_SERVER_ID, handoff.serverId)
            .putString(KEY_HOST, handoff.address.host)
            .putInt(KEY_PORT, handoff.address.port)
            .putString(KEY_VERSION_ID, handoff.version.id)
            .putInt(KEY_VERSION_PROTO, handoff.version.protocolNumber ?: NO_PROTOCOL)
            .putBoolean(KEY_SIGNING, handoff.signingMode == ChatSigningMode.SIGNED)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun load(): ConnectionHandoff? {
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val versionId = prefs.getString(KEY_VERSION_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val protocol = prefs.getInt(KEY_VERSION_PROTO, NO_PROTOCOL)
        return ConnectionHandoff(
            serverId = prefs.getString(KEY_SERVER_ID, null),
            address = ServerAddress(host, prefs.getInt(KEY_PORT, DEFAULT_PORT)),
            version = McVersion(
                id = versionId,
                type = VersionType.RELEASE,
                protocolNumber = protocol.takeIf { it != NO_PROTOCOL }
            ),
            signingMode = if (prefs.getBoolean(KEY_SIGNING, false)) {
                ChatSigningMode.SIGNED
            } else {
                ChatSigningMode.UNSIGNED
            }
        )
    }

    private companion object {
        const val KEY_SERVER_ID = "server_id"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_VERSION_ID = "version_id"
        const val KEY_VERSION_PROTO = "version_proto"
        const val KEY_SIGNING = "signing"
        const val NO_PROTOCOL = Int.MIN_VALUE
        const val DEFAULT_PORT = 25565
    }
}
