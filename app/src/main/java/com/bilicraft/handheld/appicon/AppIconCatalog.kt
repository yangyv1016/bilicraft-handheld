package com.bilicraft.handheld.appicon

import androidx.annotation.DrawableRes
import com.bilicraft.handheld.R

/**
 * 桌面启动图标目录（集中式 DSL 数据表）。
 *
 * 为什么用一张表：Android 的 activity-alias 只能在 AndroidManifest 编译期静态声明，
 * 无法运行时扫描文件夹动态生成。因此扩展性落在构建期自动化上：
 * 下面 all 列表的内容由 Gradle 任务 syncAppIcons 依据 icons 目录下的 png 自动生成，
 * 位于「APP-ICON AUTO-GENERATED」标记区之间，请勿手改；新增图标只需往 icons/ 丢 png。
 *
 * aliasSuffix 是相对包名的 alias 类名（与 manifest 的 android:name 一致），
 * 运行时由 AppIconManager 拼上 applicationId 得到完整 ComponentName。
 */
data class AppIcon(
    val id: String,
    val aliasSuffix: String,
    val displayName: String,
    val description: String,
    @DrawableRes val previewResId: Int
)

object AppIconCatalog {

    // region APP-ICON AUTO-GENERATED (由 syncAppIcons 生成，勿手改)
    val all: List<AppIcon> = listOf(
        AppIcon(
            id = "icon",
            aliasSuffix = ".ui.alias.Icon",
            displayName = "icon",
            description = "icon",
            previewResId = R.drawable.app_icon_icon
        ),
        AppIcon(
            id = "bihoyo",
            aliasSuffix = ".ui.alias.Bihoyo",
            displayName = "bihoyo",
            description = "bihoyo",
            previewResId = R.drawable.app_icon_bihoyo
        ),
    )
    // endregion APP-ICON AUTO-GENERATED

    /** 默认图标：与 manifest 中 enabled=\"true\" 的那个 alias 对应。 */
    val default: AppIcon = all.first()

    fun byId(id: String): AppIcon = all.firstOrNull { it.id == id } ?: default
}