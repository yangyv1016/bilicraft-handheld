package com.bilicraft.handheld.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilicraft.handheld.auth.AuthState
import com.bilicraft.handheld.auth.OfflineAccount

/**
 * 登录页：支持微软设备码登录与 Minecraft 标准离线账号。
 * 微软登录的设备码申请、轮询、XBL/XSTS/Minecraft Token 换取仍由 AuthManager 执行。
 * 纯 UI 美化：大号设备码、剪贴板复制、Toast、浏览器跳转和加载态展示。
 */
@Composable
fun LoginScreen(vm: MainViewModel) {
    val state by vm.authState.collectAsStateWithLifecycle()
    val loginOverlay by vm.loginOverlay.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler {
        vm.cancelLoginOverlay()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("掌上碧玺", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "可使用微软正版账号，或创建仅适用于离线模式服务器的本地账号。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = vm::cancelLoginOverlay) {
            Text(if (loginOverlay) "返回主界面" else "暂不登录")
        }
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            is AuthState.Idle,
            is AuthState.Failed -> LoginStartState(
                error = (s as? AuthState.Failed)?.reason,
                onMicrosoftLogin = vm::startLogin,
                onOfflineLogin = vm::startOfflineLogin
            )

            is AuthState.RequestingDeviceCode -> LoadingState("正在申请设备码…")
            is AuthState.ExchangingTokens -> LoadingState("正在获取游戏令牌…")
            is AuthState.Success -> LoadingState("登录成功：${s.profile.name}")
            is AuthState.WaitingForUser -> DeviceCodeState(
                userCode = s.info.userCode,
                verificationUri = s.info.verificationUri,
                onOpenBrowser = { openBrowser(context, s.info.verificationUri) },
                onCopy = { copyCode(context, s.info.userCode, manual = true) }
            )
        }
    }
}

@Composable
private fun LoginStartState(
    error: String?,
    onMicrosoftLogin: () -> Unit,
    onOfflineLogin: (String) -> Unit
) {
    var showOfflineDialog by remember { mutableStateOf(false) }
    if (error != null) {
        Text("登录失败：$error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
    }
    Button(onClick = onMicrosoftLogin, modifier = Modifier.fillMaxWidth()) {
        Text("使用微软账户登录")
    }
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onClick = { showOfflineDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text("使用离线账号登录")
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "离线账号没有微软凭据，只能进入关闭正版验证的服务器。",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center
    )

    if (showOfflineDialog) {
        OfflineLoginDialog(
            onDismiss = { showOfflineDialog = false },
            onLogin = {
                showOfflineDialog = false
                onOfflineLogin(it)
            }
        )
    }
}

@Composable
private fun OfflineLoginDialog(onDismiss: () -> Unit, onLogin: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    val normalized = OfflineAccount.normalizeUsername(username)
    val validationError = OfflineAccount.validateUsername(normalized)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("离线账号登录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入玩家名后会在本机生成标准离线 UUID。该账号不能登录开启正版验证的服务器。")
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(16) },
                    label = { Text("玩家名") },
                    supportingText = {
                        Text(
                            if (username.isBlank()) "3–16 位英文字母、数字或下划线"
                            else validationError ?: "将保存为本机离线账号"
                        )
                    },
                    isError = username.isNotBlank() && validationError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(normalized) },
                enabled = validationError == null
            ) { Text("登录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LoadingState(text: String) {
    CircularProgressIndicator()
    Spacer(Modifier.height(16.dp))
    Text(text, textAlign = TextAlign.Center)
}

@Composable
private fun DeviceCodeState(
    userCode: String,
    verificationUri: String,
    onOpenBrowser: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(userCode) {
        copyCode(context, userCode, manual = false)
    }

    Card(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("请在浏览器中输入设备码", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Text(
                text = userCode,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "设备码已自动复制。授权完成后当前页面会继续等待现有登录流程返回。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCopy) { Text("手动复制") }
                Button(onClick = onOpenBrowser) { Text("打开浏览器") }
            }
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(verificationUri, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

private fun copyCode(context: Context, code: String, manual: Boolean) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Microsoft device code", code))
    Toast.makeText(context, if (manual) "设备码已复制" else "设备码已自动复制", Toast.LENGTH_SHORT).show()
}

private fun openBrowser(context: Context, uri: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
}
