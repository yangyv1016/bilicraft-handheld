package com.bilicraft.handheld.plugin

/**
 * 内置示例插件：KeywordReply。
 *
 * 演示完整的 ChatBot 生命周期 + bot API 用法：
 * 听到含「你好」的消息就自动回一句。这段 JS 就是用户自定义插件的模板。
 */
object KeywordReplyPlugin {

    val SOURCE = """
        // KeywordReply —— 关键字自动回复示例插件
        // 生命周期回调与 MCC ChatBot 一致：onLoad / onChat / onUnload

        var rules = [
            { keyword: "你好",   reply: "你好呀，我是掌机上的机器人~" },
            { keyword: "ping",   reply: "pong!" }
        ];

        function onLoad() {
            bot.log("KeywordReply 已加载，共 " + rules.length + " 条规则");
        }

        // event = { text, sender, raw }
        function onChat(event) {
            var text = event.text || "";
            for (var i = 0; i < rules.length; i++) {
                if (text.indexOf(rules[i].keyword) >= 0) {
                    bot.sendChat(rules[i].reply);
                    break; // 一条消息只触发一次
                }
            }
        }

        function onUnload() {
            bot.log("KeywordReply 已卸载");
        }
    """.trimIndent()
}