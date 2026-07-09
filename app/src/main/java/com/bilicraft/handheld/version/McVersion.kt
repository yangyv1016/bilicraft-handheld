package com.bilicraft.handheld.version

import kotlinx.serialization.Serializable

/** MC 版本类型，决定下拉框分组 */
enum class VersionType {
    RELEASE,        // 正式版
    SNAPSHOT,       // 快照
    OLD_BETA,       // 远古 Beta
    OLD_ALPHA;      // 远古 Alpha / Classic

    companion object {
        /** Mojang manifest 的 type 字段映射 */
        fun fromManifest(raw: String): VersionType = when (raw) {
            "release" -> RELEASE
            "snapshot" -> SNAPSHOT
            "old_beta" -> OLD_BETA
            "old_alpha" -> OLD_ALPHA
            else -> SNAPSHOT
        }
    }
}

/**
 * 一个可选择的 MC 版本。
 *
 * protocolNumber 可空：内置表里近代版本有明确协议号；
 * manifest 拉来的历史版本可能没有映射，此时为 null，
 * 只能靠「自动识别」ping 服务器获取。
 */
@Serializable
data class McVersion(
    val id: String,                    // "1.21.8" / "1.20.1" / "23w31a" ...
    val type: VersionType,
    val protocolNumber: Int? = null,
    val releaseTime: String = ""       // ISO 时间，用于排序（manifest 提供）
)

/**
 * 特殊哨兵：默认「自动识别」。
 * protocolNumber = -1 是约定值，协议层见到它就走「ping 服务器拿协议号」逻辑，
 * 不锁定任何版本，交给握手阶段协商。
 */
val AUTO_DETECT = McVersion(
    id = "自动识别",
    type = VersionType.RELEASE,
    protocolNumber = -1
)