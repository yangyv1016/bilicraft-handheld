package com.bilicraft.handheld

import android.content.Context
import com.bilicraft.handheld.auth.AuthClient
import com.bilicraft.handheld.auth.AuthManager
import com.bilicraft.handheld.config.UiConfigRepository
import com.bilicraft.handheld.session.SessionController
import com.bilicraft.handheld.storage.SecureStore
import com.bilicraft.handheld.version.VersionRepository

/**
 * 进程级依赖容器（手写轻量 DI）。
 *
 * 为什么需要它：Service 与 UI 必须共享同一个 SessionController，
 * 否则会出现两套连接状态。这里用 application context 惰性构建单例，
 * 避免引入 Hilt/Koin 这类重框架——本项目模块边界清晰，手写足够。
 */
object AppContainer {

    @Volatile private var initialized = false

    lateinit var secureStore: SecureStore
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var versionRepo: VersionRepository
        private set
    lateinit var uiConfigRepo: UiConfigRepository
        private set
    lateinit var session: SessionController
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            secureStore = SecureStore(app)
            authManager = AuthManager(AuthClient(BuildConfig.MS_CLIENT_ID), secureStore)
            versionRepo = VersionRepository(app)
            uiConfigRepo = UiConfigRepository(app)
            session = SessionController(authManager, versionRepo)
            initialized = true
        }
    }
}