package com.bilicraft.handheld.plugin

import com.bilicraft.handheld.protocol.ChatEvent
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * 单个 JS 插件的运行时封装。
 *
 * 沙箱要点：
 * - 每个插件独立的 Rhino Scriptable 作用域，互不干扰
 * - optimizationLevel=-1（解释模式）：Android 上 Rhino 不能 JIT 生成字节码，必须解释执行
 * - 每次回调都 try/catch，插件抛错只产生 PluginResult.Error，绝不冒泡到主进程
 * - 插件里能拿到的全局只有：bot（宿主能力）、约定的三个回调函数
 *
 * 插件 JS 约定（类 MCC ChatBot）：
 *   function onLoad() {}
 *   function onChat(event) {}   // event = {text, sender, raw}
 *   function onUnload() {}
 */
class JsPlugin(
    val info: PluginInfo,
    private val host: PluginHost
) {
    private var scope: Scriptable? = null

    /** 加载：编译源码并触发 onLoad。失败返回 Error（不抛出）。 */
    fun load(): PluginResult = guard("onLoad") {
        val ctx = enterContext()
        try {
            val newScope = ctx.initStandardObjects()
            // 注入 bot API：受限能力面
            ScriptableObject.putProperty(newScope, "bot", createBotApi(ctx, newScope))
            ctx.evaluateString(newScope, info.source, info.name, 1, null)
            scope = newScope
            callIfExists(ctx, newScope, "onLoad", emptyArray())
        } finally {
            Context.exit()
        }
    }

    /** 分发聊天事件到插件 onChat。 */
    fun onChat(event: ChatEvent): PluginResult = guard("onChat") {
        val s = scope ?: return@guard
        val ctx = enterContext()
        try {
            val jsEvent = ctx.newObject(s).apply {
                put("text", this, event.plainText)
                put("sender", this, event.sender ?: "")
                put("raw", this, event.rawJson)
            }
            callIfExists(ctx, s, "onChat", arrayOf(jsEvent))
        } finally {
            Context.exit()
        }
    }

    /** 卸载：触发 onUnload 并释放作用域。 */
    fun unload(): PluginResult = guard("onUnload") {
        val s = scope ?: return@guard
        val ctx = enterContext()
        try {
            callIfExists(ctx, s, "onUnload", emptyArray())
        } finally {
            Context.exit()
            scope = null
        }
    }

    // ---- 内部 ----

    private fun enterContext(): Context = Context.enter().apply {
        optimizationLevel = -1          // Android 必须：解释模式
        languageVersion = Context.VERSION_ES6
        // 限制单次执行指令数，防止插件死循环拖垮宿主
        instructionObserverThreshold = 100_000
    }

    /** 构造注入给 JS 的 bot 对象，只暴露受控能力 */
    private fun createBotApi(ctx: Context, scope: Scriptable): Scriptable {
        val api = ctx.newObject(scope)
        api.put("sendChat", api, HostFunction { args -> host.sendChat(args.getOrNull(0)?.toString() ?: "") })
        api.put("log", api, HostFunction { args -> host.log(args.getOrNull(0)?.toString() ?: "") })
        return api
    }

    /**
     * 把 Kotlin lambda 适配成 Rhino 可调用函数。
     * 只允许无返回值的副作用调用（sendChat / log），进一步收窄插件能力面。
     */
    private class HostFunction(
        private val action: (Array<out Any?>) -> Unit
    ) : org.mozilla.javascript.BaseFunction() {
        override fun call(
            cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any?>
        ): Any {
            action(args)
            return Context.getUndefinedValue()
        }
    }

    private fun callIfExists(ctx: Context, scope: Scriptable, fnName: String, args: Array<Any>) {
        val fn = scope.get(fnName, scope)
        if (fn is Function) {
            fn.call(ctx, scope, scope, args)
        }
    }

    /** 崩溃隔离：任何异常都转成 PluginResult.Error */
    private inline fun guard(phase: String, block: () -> Unit): PluginResult =
        try {
            block()
            PluginResult.Ok
        } catch (t: Throwable) {
            PluginResult.Error(info.name, phase, t.message ?: t.javaClass.simpleName)
        }
}