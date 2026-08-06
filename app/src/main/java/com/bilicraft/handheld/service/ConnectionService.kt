package com.bilicraft.handheld.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bilicraft.handheld.AppContainer
import com.bilicraft.handheld.ui.MainActivity
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.protocol.ServerAddress
import com.bilicraft.handheld.protocol.ChatSigningMode
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 常驻策略实现（锁屏不断线）。
 *
 * 选择的机制组合（放权后由 AI 决定）：
 *   1. 前台 Service（dataSync 类型）+ 持久通知 —— 让系统不轻易杀进程
 *   2. 部分唤醒锁 PARTIAL_WAKE_LOCK —— 屏幕灭后 CPU 仍可跑网络心跳
 *   3. 断线重连在 SessionController 内（指数退避）—— 真被切网时优雅恢复
 *   4. 连接参数落盘（ConnectionHandoffStore）—— 进程被回收后 START_STICKY 能自己接上
 *   5. 默认网络回调 —— 切 WiFi/流量、飞行模式恢复时立刻重连，不等退避
 *
 * 低能耗挂后台（用户可选，默认关）：开启后仅在「界面不可见或息屏」时生效，
 * 释放唤醒锁并给通知刷新加节流，把常驻开销降到只剩前台 Service 本身。
 * 代价是深度休眠可能让 socket 静默断开，恢复交给重连链路。
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
    private var logJob: Job? = null
    private var powerPolicyJob: Job? = null

    private val handoffStore by lazy { AppContainer.connectionHandoffStore }

    private val screenInteractive = MutableStateFlow(true)
    private var lowPowerActive = false
    private var powerPolicyApplied = false

    private var latestStateText = ""
    private var lastNotifiedText = ""
    private var lastNotifiedLowPower = false
    private var lastNotifiedAtElapsed = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenInteractive.value = true
                Intent.ACTION_SCREEN_OFF -> screenInteractive.value = false
            }
        }
    }

    /**
     * 网络恢复即刻重连：退避最长要等 30s，且达到上限后就彻底停了，
     * 而用户切网/退出飞行模式时通常希望马上回到线上。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val session = AppContainer.session
            if (!session.hasActiveRequest) return
            when (session.connState.value) {
                is ConnectionState.Connected,
                is ConnectionState.Connecting,
                is ConnectionState.LoggingIn -> Unit
                else -> serviceScope.launch { session.retryNow() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppContainer.init(applicationContext)
        createChannel()
        startForeground(NOTIF_ID, buildNotification("正在启动…"))
        // 先无条件持锁：省电偏好要读盘，不能让启动瞬间出现无保护窗口。
        acquireWakeLock()
        registerScreenReceiver()
        registerNetworkCallback()
        observeState()
        observePowerPolicy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val proto = intent.getIntExtra(EXTRA_VERSION_PROTO, Int.MIN_VALUE)
                val handoff = ConnectionHandoff(
                    serverId = intent.getStringExtra(EXTRA_SERVER_ID),
                    address = ServerAddress(
                        host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                        port = intent.getIntExtra(EXTRA_PORT, 25565)
                    ),
                    version = McVersion(
                        id = intent.getStringExtra(EXTRA_VERSION_ID).orEmpty(),
                        type = VersionType.RELEASE,
                        protocolNumber = if (proto == Int.MIN_VALUE) null else proto
                    ),
                    signingMode = if (intent.getBooleanExtra(EXTRA_SIGNING, false))
                        ChatSigningMode.SIGNED else ChatSigningMode.UNSIGNED
                )
                handoffStore.save(handoff)
                connect(handoff)
            }
            ACTION_STOP -> {
                handoffStore.clear()
                AppContainer.session.stop()
                stopSelf()
            }
            // intent 为 null = START_STICKY 重建。用落盘快照自己接上，否则只会空转。
            null -> restoreFromHandoff()
        }
        // 被系统杀掉后尝试重建（配合 ConnectionHandoffStore 恢复连接参数）
        return START_STICKY
    }

    private fun connect(handoff: ConnectionHandoff) {
        AppContainer.session.start(
            handoff.serverId,
            handoff.address,
            handoff.version,
            handoff.signingMode
        )
    }

    private fun restoreFromHandoff() {
        if (AppContainer.session.hasActiveRequest) return
        val handoff = handoffStore.load()
        if (handoff == null) {
            stopSelf()
            return
        }
        connect(handoff)
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            AppContainer.session.connState.collect { state ->
                latestStateText = when (state) {
                    is ConnectionState.Connected -> "已连接"
                    is ConnectionState.Connecting -> "连接中…"
                    is ConnectionState.LoggingIn -> "登录中…"
                    is ConnectionState.Reconnecting -> "重连中（第 ${state.attempt} 次）"
                    is ConnectionState.Failed -> "连接失败"
                    is ConnectionState.Disconnected -> "已断开"
                }
                // 重连中状态变化频繁，省电时用节流避免每次都唤醒通知栏；
                // 连接成功/失败这类终态一定要立刻可见，不进节流。
                val throttle = lowPowerActive && state is ConnectionState.Reconnecting
                pushNotification(throttle)
            }
        }
    }

    /**
     * 把 SessionController 的系统消息镜像到 logcat（tag=BilicraftMC）。
     * 业务核心保持纯 Kotlin 零污染，观测性只在 Android 宿主层挂载。
     * 只镜像新增的系统提示（sender==null），避免刷屏聊天内容。
     */
    private fun startLogMirror() {
        if (logJob?.isActive == true) return
        logJob = serviceScope.launch {
            var lastSeenSize = AppContainer.session.log.value.size
            AppContainer.session.log.collect { events ->
                if (events.size > lastSeenSize) {
                    events.subList(lastSeenSize, events.size)
                        .filter { it.sender == null }
                        .forEach { Log.i(LOG_TAG, it.plainText) }
                }
                lastSeenSize = events.size
            }
        }
    }

    // ---- 省电策略 ----

    /**
     * 低能耗只在「用户确实看不见」时启用：开关打开 + 界面不可见或已息屏。
     * 前台可见时保持原有全功率行为，避免影响正在使用的聊天体验。
     */
    private fun observePowerPolicy() {
        powerPolicyJob?.cancel()
        powerPolicyJob = serviceScope.launch {
            // load() 可能因 IO 失败抛异常；吞掉后保持默认全功率，避免整个省电策略协程静默死掉。
            runCatching { AppContainer.uiConfigRepo.load() }
            combine(
                AppContainer.uiConfigRepo.preferences.map { it.backgroundLowPowerEnabled },
                AppContainer.appVisibility.isForeground,
                screenInteractive
            ) { lowPowerEnabled, foreground, interactive ->
                lowPowerEnabled && (!foreground || !interactive)
            }.collect(::applyPowerPolicy)
        }
    }

    private fun applyPowerPolicy(lowPower: Boolean) {
        if (powerPolicyApplied && lowPower == lowPowerActive) return
        powerPolicyApplied = true
        lowPowerActive = lowPower
        if (lowPower) {
            releaseWakeLock()
            logJob?.cancel()
        } else {
            acquireWakeLock()
            startLogMirror()
        }
        pushNotification(throttle = false)
    }

    private fun acquireWakeLock() {
        val existing = wakeLock ?: run {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
        }
        if (!existing.isHeld) existing.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun registerScreenReceiver() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenInteractive.value = pm.isInteractive
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onDestroy() {
        releaseWakeLock()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        }
        stateJob?.cancel()
        logJob?.cancel()
        powerPolicyJob?.cancel()
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
            .setContentTitle(if (lowPowerActive) "Bilicraft 掌机 · 省电挂机" else "Bilicraft 掌机")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()

    /**
     * 点击通知回到应用主界面。
     * SINGLE_TOP + CLEAR_TOP：复用已存在的 MainActivity 实例，避免重复入栈。
     * FLAG_IMMUTABLE：Android 12+ 强制要求，且本 Intent 无需被外部改写。
     */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 通知降噪：文本没变就不重发；省电挂机期间的中间态再加最小间隔，
     * 免得重连退避把通知栏刷成高频唤醒源。
     */
    private fun pushNotification(throttle: Boolean) {
        val text = latestStateText.ifEmpty { return }
        val now = SystemClock.elapsedRealtime()
        if (text == lastNotifiedText && lowPowerActive == lastNotifiedLowPower) return
        if (throttle && now - lastNotifiedAtElapsed < LOW_POWER_NOTIFY_INTERVAL_MS) return
        lastNotifiedText = text
        lastNotifiedLowPower = lowPowerActive
        lastNotifiedAtElapsed = now
        (getSystemService(NotificationManager::class.java))
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.bilicraft.handheld.START"
        const val ACTION_STOP = "com.bilicraft.handheld.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_PORT = "port"
        const val EXTRA_VERSION_ID = "version_id"
        const val EXTRA_VERSION_PROTO = "version_proto"
        const val EXTRA_SIGNING = "signing"

        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 1001
        private const val LOG_TAG = "BilicraftMC"
        private const val WAKE_LOCK_TAG = "bilicraft:connection"
        private const val LOW_POWER_NOTIFY_INTERVAL_MS = 30_000L

        /** 构造启动 Intent（UI 侧调用） */
        fun startIntent(
            context: Context,
            serverId: String?,
            host: String,
            port: Int,
            version: McVersion,
            mode: com.bilicraft.handheld.protocol.ChatSigningMode
        ): Intent = Intent(context, ConnectionService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SERVER_ID, serverId)
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