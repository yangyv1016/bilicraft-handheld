package com.bilicraft.handheld.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import android.content.Intent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilicraft.handheld.auth.AuthState

/**
 * 登录页：设备码流程。
 * 展示 user_code + 打开浏览器按钮，全程无需输入密码（硬性要求）。
 */
@Composable
fun LoginScreen(vm: MainViewModel) {
    val state by vm.authState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bilicraft 掌机", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "使用微软账户登录（设备码方式，无需输入密码）",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            is AuthState.Idle,
            is AuthState.Failed -> {
                if (s is AuthState.Failed) {
                    Text("登录失败：${s.reason}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { vm.startLogin() }) { Text("使用微软账户登录") }
            }

            is AuthState.RequestingDeviceCode,
            is AuthState.ExchangingTokens -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(if (s is AuthState.ExchangingTokens) "正在获取游戏令牌…" else "正在申请设备码…")
            }

            is AuthState.WaitingForUser -> {
                Card(modifier = Modifier.padding(8.dp)) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("请在浏览器中输入此代码：")
                        Spacer(Modifier.height(8.dp))
                        Text(s.info.userCode, style = MaterialTheme.typography.headlineLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, s.info.verificationUri.toUri())
                            context.startActivity(intent)
                        }) { Text("打开微软授权页") }
                        Spacer(Modifier.height(8.dp))
                        Text("授权后自动返回，请稍候…", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                }
            }

            is AuthState.Success -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("登录成功：${s.profile.name}")
            }
        }
    }
}