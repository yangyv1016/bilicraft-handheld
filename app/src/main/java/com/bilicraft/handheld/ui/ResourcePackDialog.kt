package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.resourcepack.PackEntry
import com.bilicraft.handheld.resourcepack.PackStage
import com.bilicraft.handheld.resourcepack.ResourcePackState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun ResourcePackConsentDialog(entry: PackEntry, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("下载服务器资源包？") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("用于显示聊天图标与物品资源。下载后保存在本机，资源包更新时会再次询问。")
                Text("来源：${entry.request.url.toHttpUrlOrNull()?.host ?: "无效地址"}")
                if (entry.request.prompt.isNotEmpty()) MinecraftText(entry.request.prompt)
                Text(if (entry.request.required) "服务器要求使用此资源包，拒绝可能会被断开连接。" else "暂不下载仍可查看文字和使用聊天。")
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("下载并使用") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("暂不下载") } }
    )
}

@Composable
internal fun ResourcePackStatusDialog(state: ResourcePackState, onDismiss: () -> Unit) {
    val warnings = state.fonts?.warnings?.collectAsStateWithLifecycle()?.value.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器资源包") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.entries.isEmpty()) Text("当前没有服务器资源包")
                for (entry in state.entries) {
                    Text(entry.request.url.toHttpUrlOrNull()?.host ?: "无效地址")
                    Text(when (entry.stage) {
                        PackStage.Consent -> "等待确认"
                        PackStage.Downloading -> "下载中：${entry.downloaded / 1024} KiB" + (entry.total?.let { " / ${it / 1024} KiB" } ?: "")
                        PackStage.Loaded -> "已加载并缓存 · ${entry.archive!!.fonts.size} 个字体定义"
                        PackStage.Declined -> "已拒绝；重新连接可再次选择"
                        PackStage.Failed -> "加载失败：${entry.message}。可重新连接后重试。"
                    })
                }
                Text("支持聊天字形与静态物品图标，动画贴图显示首帧。专用渲染器使用占位图，HUD 特效保留原文字。")
                warnings.take(3).forEach { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
