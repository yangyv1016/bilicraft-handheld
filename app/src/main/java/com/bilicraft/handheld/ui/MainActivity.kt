package com.bilicraft.handheld.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bilicraft.handheld.AppContainer

/**
 * 唯一入口 Activity：初始化依赖容器、申请通知权限、承载 Compose UI。
 * 纯 UI 美化：这里定义 Material 3 品牌色；业务状态仍来自 AppContainer 中的既有模块。
 */
class MainActivity : ComponentActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户选择即可 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContainer.init(applicationContext)
        requestNotificationPermissionIfNeeded()

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) BilicraftDarkColors else BilicraftLightColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: MainViewModel = viewModel()
                    AppRoot(vm)
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private val BilicraftLightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    secondary = Color(0xFF006B5F),
    tertiary = Color(0xFF7A5C00),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFE7EFFD)
)

private val BilicraftDarkColors = darkColorScheme(
    primary = Color(0xFF9CC2FF),
    secondary = Color(0xFF72D8C8),
    tertiary = Color(0xFFE8C45C),
    surface = Color(0xFF101318),
    surfaceVariant = Color(0xFF263142)
)