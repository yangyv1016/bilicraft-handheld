package com.bilicraft.handheld.protocol

import java.util.UUID

data class ResourcePackRequest(
    val id: UUID,
    val url: String,
    val sha1: String,
    val required: Boolean,
    val prompt: List<ChatSpan>,
    val revision: Long = 0
)

enum class ResourcePackStatus(val wireId: Int) {
    Loaded(0), Declined(1), DownloadFailed(2), Accepted(3), Downloaded(4), InvalidUrl(5), ReloadFailed(6)
}
