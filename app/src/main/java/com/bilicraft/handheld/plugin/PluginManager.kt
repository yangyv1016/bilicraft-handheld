package com.bilicraft.handheld.plugin

import com.bilicraft.handheld.protocol.ChatEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 插件编排层：管理所有插件的生命周期，把 ChatEvent 广播给每个插件。
 *
 * 崩溃隔离保证：单个插件在 onChat 抛错只影响它自己（被 JsPlugin.guard 捕获），
 * 其余插件与主进程继续运行。错误通过 errors 流上报给 UI。
 *
 * 数据流：
 *   MinecraftClient.incoming ─► PluginManager.dispatchChat ─► 各插件 onChat
 *   插件 bot.sendChat ────────► PluginHost ─► MinecraftClient.sendChat
 */
class PluginManager(private val host: PluginHost) {

    private val plugins = CopyOnWriteArrayList<JsPlugin>()

    private val _errors = MutableSharedFlow<PluginResult.Error>(extraBufferCapacity = 64)
    val errors: SharedFlow<PluginResult.Error> = _errors.asSharedFlow()

    /** 已加载插件名列表（UI 展示） */
    fun loadedNames(): List<String> = plugins.map { it.info.name }

    /** 注册并加载一个插件 */
    fun install(info: PluginInfo) {
        val plugin = JsPlugin(info, host)
        plugins.add(plugin)
        report(plugin.load())
    }

    /** 卸载指定插件 */
    fun uninstall(name: String) {
        val it = plugins.firstOrNull { p -> p.info.name == name } ?: return
        report(it.unload())
        plugins.remove(it)
    }

    /** 广播聊天事件给所有插件（逐个隔离） */
    fun dispatchChat(event: ChatEvent) {
        plugins.forEach { p -> report(p.onChat(event)) }
    }

    /** 全部卸载（断开连接 / 退出时） */
    fun unloadAll() {
        plugins.forEach { report(it.unload()) }
        plugins.clear()
    }

    /** 加载内置示例插件 */
    fun installBuiltins() {
        install(PluginInfo(name = "KeywordReply", source = KeywordReplyPlugin.SOURCE))
    }

    private fun report(result: PluginResult) {
        if (result is PluginResult.Error) _errors.tryEmit(result)
    }
}