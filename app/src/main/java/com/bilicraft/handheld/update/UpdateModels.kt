package com.bilicraft.handheld.update

import kotlinx.serialization.Serializable
import java.io.File

/**
 * update 模块领域模型。全部不可变，只在模块内流转。
 */

/**
 * 更新下载源。用户可选，持久化在 UI 偏好里。
 *
 * 设计：源的差异只体现在「如何把一个 github.com / api.github.com 的原始 URL 变成实际请求 URL」，
 * 因此每个源就是一个纯变换 rewrite()：
 * - DIRECT 原样返回（直连 GitHub）
 * - 其余镜像给原始 URL 加代理前缀（gh-proxy 系公共代理，API 查询与 APK 下载统一走同一前缀）
 *
 * 为什么统一改写 API 与下载：用户选镜像的前提就是直连不稳，gh-proxy 系代理对
 * api.github.com/repos/.../releases 与 github.com/.../releases/download 都支持，
 * 二者用同一前缀即可，避免出现「能查到新版却下不动」的割裂状态。
 *
 * 默认优先国内镜像（本项目面向国内用户，直连 GitHub 常超时）。
 */
@Serializable
enum class DownloadSource(
    val displayName: String,
    val description: String,
    private val prefix: String
) {
    GH_PROXY("镜像 · gh-proxy", "国内推荐，通过 gh-proxy.com 加速", "https://gh-proxy.com/"),
    GHPROXY_NET("镜像 · ghproxy.net", "备用镜像，gh-proxy.com 不通时可切换", "https://ghproxy.net/"),
    MIRROR_GHPROXY("镜像 · mirror.ghproxy", "备用镜像，通过 mirror.ghproxy.com 加速", "https://mirror.ghproxy.com/"),
    DIRECT("直连 GitHub", "不走镜像，海外网络或已有代理时使用", "");

    /** 把 GitHub 原始 URL 变成经由本源的实际请求 URL。直连原样返回，镜像加前缀。 */
    fun rewrite(githubUrl: String): String =
        if (prefix.isEmpty()) githubUrl else prefix + githubUrl

    companion object {
        /** 默认源：优先国内镜像 */
        val DEFAULT = GH_PROXY
    }
}

/**
 * GitHub 最新 Release 的解析结果。
 *
 * - versionName 是剥掉 tag 前导 'v' 后的纯版本号（如 "0.1.6"），用于与 BuildConfig.VERSION_NAME 比较。
 * - apkDownloadUrl 指向 Release assets 里的 .apk（browser_download_url，已由 GitHub 编码好）。
 * - apkSizeBytes 供 UI 展示与下载进度分母；为 0 时进度按不确定处理。
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long
)

/**
 * 更新流程状态机（对 UI 暴露）。
 * UI 只需 when 一下即可渲染每个阶段，不接触任何 HTTP / 文件细节。
 */
sealed interface UpdateState {
    /** 空闲：尚未检查，或用户已消费完一次结果 */
    data object Idle : UpdateState

    /** 正在查询 GitHub 最新 Release */
    data object Checking : UpdateState

    /** 已是最新版（手动检查时给用户明确反馈；自动静默检查时 UI 可忽略此态） */
    data object UpToDate : UpdateState

    /** 发现新版本，等待用户决定是否下载 */
    data class Available(val info: ReleaseInfo) : UpdateState

    /** 下载中，progress 为 0f..1f；size 未知时以 -1f 表示不确定进度 */
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState

    /** 下载完成，APK 已就绪，等待触发系统安装器 */
    data class Downloaded(val info: ReleaseInfo, val apkFile: File) : UpdateState

    /** 失败（附带人类可读原因） */
    data class Failed(val reason: String) : UpdateState
}