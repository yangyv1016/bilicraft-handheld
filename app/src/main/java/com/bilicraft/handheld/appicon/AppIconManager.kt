package com.bilicraft.handheld.appicon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面启动图标切换器。
 *
 * 状态真相源：Android 系统的组件启停状态（PackageManager 持久化），无需额外偏好存储。
 * 不变式：任意时刻 AppIconCatalog.all 里恰好一个 alias 处于启用态，即桌面上恰好一个入口。
 *
 * 切换副作用（Android 平台机制，非本代码可消除）：
 * 桌面图标会短暂消失重现，部分系统会把 App 从最近任务清掉。
 * 因此先启用目标、再禁用其余，缩小「零入口」的时间窗口。
 */
class AppIconManager(appContext: Context) {

    private val packageManager: PackageManager = appContext.packageManager
    private val packageName: String = appContext.packageName

    private fun componentOf(icon: AppIcon): ComponentName =
        ComponentName(packageName, packageName + icon.aliasSuffix)

    /** 读取当前启用的图标。找不到（理论不该发生）时回退默认。 */
    fun current(): AppIcon =
        AppIconCatalog.all.firstOrNull { icon ->
            packageManager.getComponentEnabledSetting(componentOf(icon)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIconCatalog.default

    /**
     * 切换到目标图标：先启用目标，再禁用其余，保证不出现零入口。
     * DONT_KILL_APP 让切换时不强杀进程，尽量平滑。
     */
    fun apply(target: AppIcon) {
        setEnabled(target, enabled = true)
        AppIconCatalog.all
            .filter { it.id != target.id }
            .forEach { setEnabled(it, enabled = false) }
    }

    private fun setEnabled(icon: AppIcon, enabled: Boolean) {
        val newState =
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            componentOf(icon),
            newState,
            PackageManager.DONT_KILL_APP
        )
    }
}