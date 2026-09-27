package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.NbtTag
import org.json.JSONArray
import org.json.JSONObject

/** Evaluate the 1.21.11 item-definition tree for a still item in a GUI. */
object ItemModelSelector {
    fun select(node: JSONObject, item: ItemDetails, depth: Int = 0): List<JSONObject> {
        require(depth < 32) { "物品模型嵌套过深" }
        val components = item.components.entries
        val custom = (components["minecraft:custom_model_data"] as? NbtTag.NbtCompound)?.entries.orEmpty()
        val index = node.optInt("index", 0)
        fun customValue(name: String) = (custom[name] as? NbtTag.NbtList)?.items?.getOrNull(index)
        fun descend(child: JSONObject?) = child?.let { select(it, item, depth + 1) }.orEmpty()
        return when (node.getString("type").removePrefix("minecraft:")) {
            "model" -> listOf(node)
            "special" -> {
                val special = node.getJSONObject("model")
                require(special.getString("type").removePrefix("minecraft:") == "player_head") { "暂不支持的专用物品模型" }
                listOf(node)
            }
            "empty" -> emptyList()
            "composite" -> node.getJSONArray("models").let { models ->
                require(models.length() <= 64) { "物品模型图层过多" }
                buildList {
                    for (index in 0 until models.length()) {
                        addAll(descend(models.getJSONObject(index)))
                        require(size <= 64) { "物品模型图层过多" }
                    }
                }
            }
            "condition" -> {
                val value = when (node.getString("property").removePrefix("minecraft:")) {
                    "using_item", "fishing_rod/cast", "selected", "carried", "extended_view", "bundle/has_selected_item" -> false
                    "broken" -> {
                        val maximum = (components["minecraft:max_damage"] as? NbtTag.NbtInt)?.value
                        val damage = (components["minecraft:damage"] as? NbtTag.NbtInt)?.value ?: 0
                        maximum != null && maximum > 0 && damage >= maximum - 1
                    }
                    "custom_model_data" -> (customValue("flags") as? NbtTag.NbtByte)?.value == 1.toByte()
                    "damaged" -> ((components["minecraft:damage"] as? NbtTag.NbtInt)?.value ?: 0) > 0
                    "has_component" -> node.getString("component") in components
                    else -> error("暂不支持的物品条件：${node.getString("property")}")
                }
                descend(node.getJSONObject(if (value) "on_true" else "on_false"))
            }
            "select" -> {
                val value = when (node.getString("property").removePrefix("minecraft:")) {
                    "custom_model_data" -> (customValue("strings") as? NbtTag.NbtString)?.value
                    "display_context" -> "gui"
                    "trim_material" -> {
                        val trim = (components["minecraft:trim"] as? NbtTag.NbtCompound)?.entries
                        val material = trim?.get("material")
                        if (material != null && material !is NbtTag.NbtString) error("暂不支持该镶边材质")
                        (material as? NbtTag.NbtString)?.value
                    }
                    "charge_type" -> {
                        val projectiles = (components["minecraft:charged_projectiles"] as? NbtTag.NbtList)?.items.orEmpty()
                        if (projectiles.isEmpty()) "none"
                        else if (projectiles.any { ((it as? NbtTag.NbtCompound)?.entries?.get("id") as? NbtTag.NbtString)?.value == "minecraft:firework_rocket" }) "rocket"
                        else "arrow"
                    }
                    else -> error("暂不支持的物品选择规则：${node.getString("property")}")
                }
                val cases = node.getJSONArray("cases")
                val match = (0 until cases.length()).map { cases.getJSONObject(it) }.firstOrNull { case ->
                    when (val expected = case.get("when")) {
                        is JSONArray -> (0 until expected.length()).any { expected.getString(it) == value }
                        else -> expected == value
                    }
                }
                descend(match?.getJSONObject("model") ?: node.optJSONObject("fallback"))
            }
            "range_dispatch" -> {
                val raw = when (node.getString("property").removePrefix("minecraft:")) {
                    "custom_model_data" -> when (val number = customValue("floats")) {
                        is NbtTag.NbtFloat -> number.value.toDouble()
                        is NbtTag.NbtDouble -> number.value
                        is NbtTag.NbtInt -> number.value.toDouble()
                        else -> 0.0
                    }
                    "damage" -> {
                        val damage = (components["minecraft:damage"] as? NbtTag.NbtInt)?.value ?: 0
                        if (!node.optBoolean("normalize", true)) damage.toDouble()
                        else {
                            val maximum = (components["minecraft:max_damage"] as? NbtTag.NbtInt)?.value
                                ?: error("缺少物品默认最大耐久，无法选择图标")
                            if (maximum == 0) 0.0 else damage.toDouble() / maximum
                        }
                    }
                    "use_duration", "crossbow/pull" -> 0.0
                    "count" -> {
                        val maximum = (components["minecraft:max_stack_size"] as? NbtTag.NbtInt)?.value
                        if (!node.optBoolean("normalize", true)) item.count.toDouble()
                        else item.count.toDouble() / (maximum ?: error("缺少物品默认堆叠大小"))
                    }
                    else -> error("暂不支持的物品数值规则：${node.getString("property")}")
                }
                val value = raw * node.optDouble("scale", 1.0)
                val entries = node.getJSONArray("entries")
                val match = (0 until entries.length()).map { entries.getJSONObject(it) }
                    .filter { it.getDouble("threshold") <= value }.maxByOrNull { it.getDouble("threshold") }
                descend(match?.getJSONObject("model") ?: node.optJSONObject("fallback"))
            }
            else -> error("暂不支持的物品模型：${node.getString("type")}")
        }
    }
}
