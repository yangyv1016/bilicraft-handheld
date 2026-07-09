package com.bilicraft.handheld.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 根导航：极简双屏切换。未登录 → 登录页；已登录 → 主控页。
 * 不引入 Navigation 组件，因为只有两个状态，一个布尔足够，避免过度设计。
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    if (loggedIn) {
        MainScreen(vm)
    } else {
        LoginScreen(vm)
    }
}