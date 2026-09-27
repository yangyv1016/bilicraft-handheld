package com.bilicraft.handheld

import android.content.Context
import com.bilicraft.handheld.announcement.AnnouncementRepository
import com.bilicraft.handheld.appicon.AppIconManager
import com.bilicraft.handheld.auth.AuthClient
import com.bilicraft.handheld.auth.AuthManager
import com.bilicraft.handheld.cdk.CdkRepository
import com.bilicraft.handheld.config.UiConfigRepository
import com.bilicraft.handheld.externalplugin.ExternalPluginManager
import com.bilicraft.handheld.pluginmarket.OfficialPluginMarketRepository
import com.bilicraft.handheld.protocol.MinecraftTranslations
import com.bilicraft.handheld.session.SessionController
import com.bilicraft.handheld.resourcepack.ResourcePackRepository
import java.io.File
import com.bilicraft.handheld.storage.SecureStore
import com.bilicraft.handheld.update.UpdateClient
import com.bilicraft.handheld.update.UpdateManager
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
    lateinit var updateManager: UpdateManager
        private set
    lateinit var appIconManager: AppIconManager
        private set
    lateinit var externalPluginManager: ExternalPluginManager
        private set
    lateinit var officialPluginMarket: OfficialPluginMarketRepository
        private set
    lateinit var cdkRepository: CdkRepository
        private set
    lateinit var announcementRepository: AnnouncementRepository
        private set
    lateinit var vanillaItemIcons: com.bilicraft.handheld.resourcepack.ResourcePackItemIcons
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            app.assets.open("minecraft/zh_cn.json").bufferedReader().use { reader ->
                MinecraftTranslations.loadJson(reader.readText())
            }
            secureStore = SecureStore(app)
            authManager = AuthManager(AuthClient(BuildConfig.MS_CLIENT_ID), secureStore)
            versionRepo = VersionRepository(app)
            uiConfigRepo = UiConfigRepository(app)
            val vanillaFile = File(app.filesDir, "vanilla-items-1.21.11.zip")
            // The bundled archive is authoritative. Refresh it on process start after an app update.
            app.assets.open("minecraft/vanilla-items-1.21.11.zip").use { input -> vanillaFile.outputStream().use { input.copyTo(it) } }
            val vanillaArchive = com.bilicraft.handheld.resourcepack.ResourcePackArchive(vanillaFile)
            val headSkins = com.bilicraft.handheld.resourcepack.PlayerHeadSkins(File(app.cacheDir, "player-head-skins"))
            vanillaItemIcons = com.bilicraft.handheld.resourcepack.ResourcePackItemIcons(listOf(vanillaArchive), headSkins)
            session = SessionController(authManager, versionRepo, ResourcePackRepository(File(app.filesDir, "resource-packs"), vanillaArchive, headSkins),
                inventoryCodecFactory = {
                    fun readAsset(name: String) = org.json.JSONObject(app.assets.open("minecraft/inventory-774/$name.json").bufferedReader().use { it.readText() })
                    com.bilicraft.handheld.protocol.InventoryCodec(readAsset("slot-schema"), readAsset("items"), readAsset("item-components"))
                })
            updateManager = UpdateManager(
                appContext = app,
                client = UpdateClient(owner = "yangyv1016", repo = "bilicraft-handheld"),
                currentVersionName = BuildConfig.VERSION_NAME
            )
            appIconManager = AppIconManager(app)
            externalPluginManager = ExternalPluginManager(app, session)
            officialPluginMarket = OfficialPluginMarketRepository(app, externalPluginManager)
            cdkRepository = CdkRepository(app)
            announcementRepository = AnnouncementRepository(app)
            initialized = true
        }
    }
}
