package com.bilicraft.handheld.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * update 编排层：把 UpdateClient 的纯变换串成完整流程，管理状态机与本地文件。
 *
 * 职责边界（与 AuthManager 同构）：
 * - UpdateClient 只做 HTTP（查 Release / 下载），无状态
 * - UpdateManager 负责版本比较、下载编排、状态流转、触发系统安装器（业务语义都在这里）
 *
 * 对 UI 只暴露 state: StateFlow<UpdateState>，UI 不接触 HTTP 与文件细节。
 */
class UpdateManager(
    private val appContext: Context,
    private val client: UpdateClient,
    private val currentVersionName: String
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val downloadDir: File by lazy {
        File(appContext.cacheDir, "updates").apply { mkdirs() }
    }

    /**
     * 检查更新。silent=true 时（启动自检）不把「已是最新」与失败暴露为打扰性状态，
     * 只在确有新版本时切到 Available；手动检查则完整反馈每个结果。
     */
    suspend fun checkForUpdate(silent: Boolean, source: DownloadSource = DownloadSource.DEFAULT) {
        _state.value = UpdateState.Checking
        when (val result = client.fetchLatestRelease(source::rewrite)) {
            is UpdateClient.Result.Ok -> {
                val info = result.value
                _state.value = when {
                    isRemoteNewer(info.versionName) -> UpdateState.Available(info)
                    silent -> UpdateState.Idle
                    else -> UpdateState.UpToDate
                }
            }
            is UpdateClient.Result.Err -> {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed(result.reason)
            }
        }
    }

    /**
     * 下载指定版本的 APK。下载中持续更新进度；完成后切到 Downloaded 等待用户触发安装。
     * 下载前清理旧包，避免 cache 堆积残包。
     */
    suspend fun download(info: ReleaseInfo, source: DownloadSource = DownloadSource.DEFAULT) {
        cleanDownloadDir()
        val target = File(downloadDir, "bilicraft-${info.versionName}.apk")
        _state.value = UpdateState.Downloading(info, 0f)
        val result = client.downloadApk(info.apkDownloadUrl, target, source::rewrite) { progress ->
            _state.value = UpdateState.Downloading(info, progress)
        }
        _state.value = when (result) {
            is UpdateClient.Result.Ok -> UpdateState.Downloaded(info, result.value)
            is UpdateClient.Result.Err -> UpdateState.Failed(result.reason)
        }
    }

    /**
     * 唤起系统安装器安装已下载的 APK。
     * 通过 FileProvider 把私有 cache 文件转成 content:// 并授临时读权限，满足 Android 7+ 要求。
     */
    fun installApk(apkFile: File) {
        val apkUri: Uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        appContext.startActivity(intent)
    }

    /** 用户消费完一次结果（关闭对话框），回到空闲 */
    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    // ---- 内部 ----

    /**
     * 语义化版本比较：远端是否严格新于当前。
     * 逐段数字比较，段数不齐按 0 补齐（如 0.1.6 vs 0.1.6.1）。非数字段解析失败按 0 处理。
     */
    private fun isRemoteNewer(remoteVersion: String): Boolean {
        val remote = remoteVersion.toVersionParts()
        val local = currentVersionName.toVersionParts()
        val maxLen = maxOf(remote.size, local.size)
        for (i in 0 until maxLen) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    private fun String.toVersionParts(): List<Int> =
        trim().split(".").map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    private fun cleanDownloadDir() {
        runCatching { downloadDir.listFiles()?.forEach { it.delete() } }
    }
}