package com.bilicraft.handheld.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.protocol.ServerAddress
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 常驻策略实现（锁屏不断线）。
 *
 * 选择的机制组合（放权后由 AI 决定）：
 *   1. 前台 Service（dataSync 类型）+ 持久通知 —— 让系统不轻易杀进程
 *   2. 部分唤醒锁 PARTIAL_WAKE_LOCK —— 屏幕灭后 CPU 仍可跑网络心跳
 *   3. 断线重连在 SessionController 内（指数退避）—— 真被切网时优雅恢复
 *
 * 为什么不用独立进程：聊天客户端资源占用小，独立进程反而增加 IPC 复杂度，
 * 前台 Service + WakeLock 已足够覆盖锁屏场景。
 *
 * Service 只做「容器」职责：起通知、拿唤醒锁、把参数转交 SessionController。
 * 所有业务编排都在 SessionController。
 */
class ConnectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("正在启动…"))
        acquireWakeLock()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 25565)
                val verId = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty()
                val proto = intent.getIntExtra(EXTRA_VERSION_PROTO, -1)
                val version = McVersion(
                    id = verId,
                    type = VersionType.RELEASE,
                    protocolNumber = if (proto == Int.MIN_VALUE) null else proto
                )
                val mode = if (intent.getBooleanExtra(EXTRA_SIGNING, false))
                    ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED
                AppContainer.session.start(ServerAddress(host, port), version, mode)
            }
            ACTION_STOP -> {
                AppContainer.session.stop()
                stopSelf()
            }
        }
        // 被系统杀掉后尝试重建（配合 SessionController 状态恢复）
        return START_STICKY
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            AppContainer.session.connState.collect { state ->
                val text = when (state) {
                    is com.bilicraft.handheld.protocol.ConnectionState.Connected -> "已连接"
                    is com.bilicraft.handheld.protocol.ConnectionState.Connecting -> "连接中…"
                    is com.bilicraft.handheld.protocol.ConnectionState.LoggingIn -> "登录中…"
                    is com.bilicraft.handheld.protocol.ConnectionState.Reconnecting -> "重连中（第 ${state.attempt} 次）"
                    is com.bilicraft.handheld.protocol.ConnectionState.Failed -> "连接失败"
                    is com.bilicraft.handheld.protocol.ConnectionState.Disconnected -> "已断开"
                }
                updateNotification(text)
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "bilicraft:connection"
        ).apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 通知 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "连接状态", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持与 MC 服务器的连接" }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bilicraft 掌机")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(NotificationManager::class.java))
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.bilicraft.handheld.START"
        const val ACTION_STOP = "com.bilicraft.handheld.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_VERSION_PROTO = "version_proto"
        const val EXTRA_SIGNING = "signing"

        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 1001

        /** 构造启动 Intent（UI 侧调用） */
        fun startIntent(
            context: Context, host: String, port: Int, version: McVersion,
            mode: com.bilicraft.handheld.protocol.ChatSigningMode
        ): Intent = Intent(context, ConnectionService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_VERSION_ID, version.id)
            putExtra(EXTRA_VERSION_PROTO, version.protocolNumber ?: Int.MIN_VALUE)
            putExtra(EXTRA_SIGNING, mode == com.bilicraft.handheld.protocol.ChatSigningMode.SIGNED)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ConnectionService::class.java).apply { action = ACTION_STOP }
    }
}