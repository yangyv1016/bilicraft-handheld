package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilicraft.handheld.protocol.ConnectionState

/**
 * 主控页：版本下拉 + 服务器地址 + 连接控制 + 聊天。
 * 布局自上而下对应操作顺序：选版本 → 填服务器 → 连接 → 聊天。
 */
@Composable
fun MainScreen(vm: MainViewModel) {
    val conn by vm.connState.collectAsStateWithLifecycle()
    val log by vm.chatLog.collectAsStateWithLifecycle()
    val selectedVersion by vm.selectedVersion.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val forceSigning by vm.forceSigning.collectAsStateWithLifecycle()

    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("25565") }
    var input by remember { mutableStateOf("") }

    val connected = conn is ConnectionState.Connected
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        // 顶部状态栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(statusText(conn), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.logout() }) { Text("登出") }
        }
        Spacer(Modifier.height(8.dp))

        // 版本下拉框
        VersionDropdown(
            grouped = versions,
            selected = selectedVersion,
            onSelect = { vm.selectVersion(it) }
        )
        Spacer(Modifier.height(8.dp))

        // 服务器地址
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("端口") },
                singleLine = true,
                modifier = Modifier.width(96.dp)
            )
        }
        Spacer(Modifier.height(8.dp))

        // 强制签名开关
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("强制签名聊天", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "开启后用正版证书签名，适配 enforce-secure-profile 服务器",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = forceSigning,
                onCheckedChange = { vm.setForceSigning(it) },
                enabled = !connected
            )
        }
        Spacer(Modifier.height(8.dp))

        // 连接 / 断开
        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = { vm.connect(host.trim(), port.toIntOrNull() ?: 25565) },
                enabled = host.isNotBlank() && !connected,
                modifier = Modifier.weight(1f)
            ) { Text("连接") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.stopConnection() },
                modifier = Modifier.weight(1f)
            ) { Text("断开") }
        }

        if (vm.pluginNames.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "已加载插件：${vm.pluginNames.joinToString()}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))

        // 聊天记录
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(log) { ev ->
                Text(
                    text = ev.plainText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        // 发送框
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("发送聊天…") },
                singleLine = true,
                enabled = connected,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.sendChat(input); input = "" },
                enabled = connected && input.isNotBlank()
            ) { Text("发送") }
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "● 已连接"
    is ConnectionState.Connecting -> "○ 连接中…"
    is ConnectionState.LoggingIn -> "○ 登录中…"
    is ConnectionState.Reconnecting -> "○ 重连中（第 ${state.attempt} 次）"
    is ConnectionState.Failed -> "× ${state.reason}"
    is ConnectionState.Disconnected -> "○ 未连接"
}