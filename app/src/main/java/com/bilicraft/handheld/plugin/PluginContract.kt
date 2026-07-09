package com.bilicraft.handheld.plugin

import com.bilicraft.handheld.protocol.ChatEvent

/**
 * 插件对外契约（对齐 MCC 的 ChatBot 生命周期）。
 *
 * 架构隔离：插件通过 PluginHost 提供的能力与外界交互，
 * 只能看到归一化的 ChatEvent 和有限的操作（发送聊天、日志），
 * 看不到 socket、token、原始 packet。
 */

/** 插件可调用的宿主能力（注入到 JS 环境的 `bot` 对象背后） */
interface PluginHost {
    /** 向服务器发送一条聊天 */
    fun sendChat(text: String)

    /** 输出到 App 内日志（插件调试用，非系统 log） */
    fun log(message: String)
}

/** 插件元信息 */
data class PluginInfo(
    val name: String,
    val source: String,          // JS 源码
    val enabled: Boolean = true
)

/**
 * 插件运行结果，用于崩溃隔离上报。
 * 任何一个回调抛异常都会被捕获成 Error，主进程不受影响。
 */
sealed interface PluginResult {
    data object Ok : PluginResult
    data class Error(val pluginName: String, val phase: String, val message: String) : PluginResult
}