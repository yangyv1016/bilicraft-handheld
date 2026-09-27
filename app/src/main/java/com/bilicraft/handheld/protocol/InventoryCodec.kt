package com.bilicraft.handheld.protocol

import com.bilicraft.handheld.protocol.McTypes.readString
import com.bilicraft.handheld.protocol.McTypes.readUuid
import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.Nbt.readNetworkNbt
import io.netty.buffer.ByteBuf
import org.json.JSONArray
import org.json.JSONObject

/** Fixed 774 schema, bundled with the app. No schema is accepted from a server. */
class InventoryCodec(val schema: JSONObject, val itemNames: JSONObject, val defaults: JSONObject) {
    private val componentNames = schema.getJSONArray("SlotComponentType").getJSONObject(1).getJSONObject("mappings")
    val registries = mutableMapOf<String, List<String>>()

    fun readRegistry(buf: ByteBuf) {
        val name = buf.readString()
        val count = buf.readVarInt()
        require(count in 0..65536) { "注册表大小无效" }
        registries[name] = List(count) {
            val key = buf.readString()
            if (buf.readBoolean()) buf.readNetworkNbt()
            key
        }
    }

    fun readSlot(buf: ByteBuf): InventoryItem? {
        val start = buf.readerIndex()
        val slot = read("Slot", buf, emptyMap(), 0) as NbtTag.NbtCompound
        val count = (slot.entries.getValue("itemCount") as NbtTag.NbtInt).value
        require(count in 0..127) { "物品数量无效：$count" }
        if (count == 0) return null
        val itemId = (slot.entries.getValue("itemId") as NbtTag.NbtInt).value
        val name = itemNames.getString(itemId.toString())
        val components = (Nbt.fromJson(defaults.getJSONObject(name)) as NbtTag.NbtCompound).entries.toMutableMap()
        val added = (slot.entries.getValue("components") as NbtTag.NbtList).items
        for (entry in added) {
            val fields = (entry as NbtTag.NbtCompound).entries
            val type = (fields.getValue("type") as NbtTag.NbtString).value
            components["minecraft:$type"] = normalize(type, fields.getValue("data"))
        }
        for (entry in (slot.entries.getValue("removeComponents") as NbtTag.NbtList).items) {
            val type = ((entry as NbtTag.NbtCompound).entries.getValue("type") as NbtTag.NbtString).value
            components.remove("minecraft:$type")
        }
        // Exact wire patch identity, excluding the count; never a HashedSlot component hash.
        val countBytes = buf.getUnsignedByte(start).toInt().let { if (it < 128) 1 else error("数量编码无效") }
        val patch = ByteArray(buf.readerIndex() - start - countBytes)
        buf.getBytes(start + countBytes, patch)
        return InventoryItem(ItemDetails("minecraft:$name", count, NbtTag.NbtCompound(components)),
            patch.joinToString("") { "%02x".format(it) })
    }

    private fun normalize(type: String, value: NbtTag): NbtTag = when (type) {
        "profile" -> {
            val fields = (value as NbtTag.NbtCompound).entries
            val patch = (fields.getValue("skinPatch") as NbtTag.NbtCompound).entries
            val result = mutableMapOf<String, NbtTag>()
            for ((wire, name) in listOf("uuid" to "id", "name" to "name")) {
                fields.getValue(wire).takeIf { it != NbtTag.NbtEnd }?.let { result[name] = it }
            }
            val properties = fields.getValue("properties") as NbtTag.NbtList
            if (fields.getValue("type") == NbtTag.NbtString("complete") || properties.items.isNotEmpty()) {
                result["properties"] = properties
            }
            for ((wire, name) in listOf("body" to "texture", "cape" to "cape", "elytra" to "elytra", "model" to "model")) {
                patch.getValue(wire).takeIf { it != NbtTag.NbtEnd }?.let { result[name] = it }
            }
            NbtTag.NbtCompound(result)
        }
        "enchantments", "stored_enchantments" -> {
            val list = ((value as NbtTag.NbtCompound).entries.getValue("enchantments") as NbtTag.NbtList).items
            NbtTag.NbtCompound(list.associate { entry ->
                val fields = (entry as NbtTag.NbtCompound).entries
                val id = (fields.getValue("id") as NbtTag.NbtInt).value
                val key = registries["minecraft:enchantment"]?.getOrNull(id)
                    ?: error("缺少附魔注册表项：$id")
                key to fields.getValue("level")
            })
        }
        "tooltip_display" -> {
            val fields = (value as NbtTag.NbtCompound).entries
            NbtTag.NbtCompound(mapOf("hide_tooltip" to fields.getValue("hideTooltip"),
                "hidden_components" to NbtTag.NbtList((fields.getValue("hiddenComponents") as NbtTag.NbtList).items.map {
                    NbtTag.NbtString("minecraft:" + componentNames.getString((it as NbtTag.NbtInt).value.toString()))
                })))
        }
        "unbreakable", "glider", "creative_slot_lock" -> NbtTag.NbtCompound(emptyMap())
        "potion_contents" -> {
            val fields = (value as NbtTag.NbtCompound).entries
            NbtTag.NbtCompound(fields + mapOf("custom_color" to fields.getValue("customColor")))
        }
        "charged_projectiles" -> {
            val projectiles = (value as NbtTag.NbtCompound).entries.getValue("projectiles") as NbtTag.NbtList
            NbtTag.NbtList(projectiles.items.map { projectile ->
                val fields = (projectile as NbtTag.NbtCompound).entries
                val id = fields["itemId"] as? NbtTag.NbtInt
                NbtTag.NbtCompound(if (id == null) emptyMap() else mapOf("id" to NbtTag.NbtString("minecraft:" + itemNames.getString(id.value.toString()))))
            })
        }
        else -> value
    }

    private fun read(type: Any, buf: ByteBuf, context: Map<String, NbtTag>, depth: Int): NbtTag {
        require(depth < 96) { "物品组件嵌套过深" }
        if (type is String) return when (type) {
            "varint" -> NbtTag.NbtInt(buf.readVarInt())
            "i32" -> NbtTag.NbtInt(buf.readInt())
            "f32" -> NbtTag.NbtFloat(buf.readFloat())
            "f64" -> NbtTag.NbtDouble(buf.readDouble())
            "bool" -> NbtTag.NbtByte(if (buf.readBoolean()) 1 else 0)
            "string" -> NbtTag.NbtString(buf.readString())
            "UUID" -> NbtTag.NbtString(buf.readUuid().toString())
            "position" -> NbtTag.NbtLong(buf.readLong())
            "anonymousNbt", "anonOptionalNbt" -> buf.readNetworkNbt()
            "void" -> NbtTag.NbtEnd
            else -> read(schema.get(type), buf, context, depth + 1)
        }
        val definition = type as JSONArray
        val kind = definition.getString(0)
        val args = definition.get(1)
        fun child(childType: Any, fields: Map<String, NbtTag> = context) = read(childType, buf, fields, depth + 1)
        return when (kind) {
            "container" -> {
                val fields = linkedMapOf<String, NbtTag>()
                val entries = args as JSONArray
                for (index in 0 until entries.length()) {
                    val entry = entries.getJSONObject(index)
                    val value = child(entry.get("type"), context + fields)
                    if (entry.optBoolean("anon")) {
                        if (value is NbtTag.NbtCompound) fields.putAll(value.entries)
                        else require(value == NbtTag.NbtEnd) { "匿名组件类型无效" }
                    } else fields[entry.getString("name")] = value
                }
                NbtTag.NbtCompound(fields)
            }
            "array" -> {
                val spec = args as JSONObject
                val count = if (spec.has("count")) (context.getValue(spec.getString("count")) as NbtTag.NbtInt).value
                    else (child(spec.get("countType")) as NbtTag.NbtInt).value
                require(count in 0..65536 && count <= buf.readableBytes()) { "物品数组长度无效：$count" }
                NbtTag.NbtList(List(count) { child(spec.get("type")) })
            }
            "option" -> if (buf.readBoolean()) child(args) else NbtTag.NbtEnd
            "mapper" -> {
                val spec = args as JSONObject
                val value = (child(spec.get("type")) as NbtTag.NbtInt).value
                NbtTag.NbtString(spec.getJSONObject("mappings").getString(value.toString()))
            }
            "switch" -> {
                val spec = args as JSONObject
                val key = when (val value = context.getValue(spec.getString("compareTo"))) {
                    is NbtTag.NbtString -> value.value
                    is NbtTag.NbtInt -> value.value.toString()
                    is NbtTag.NbtByte -> (value.value != 0.toByte()).toString()
                    else -> error("物品分支类型无效")
                }
                child(spec.getJSONObject("fields").opt(key) ?: spec.opt("default") ?: "void")
            }
            "registryEntryHolder" -> {
                val spec = args as JSONObject
                val id = buf.readVarInt()
                require(id >= 0) { "注册表引用无效" }
                if (id != 0) NbtTag.NbtCompound(mapOf(spec.getString("baseName") to NbtTag.NbtInt(id - 1)))
                else spec.getJSONObject("otherwise").let { NbtTag.NbtCompound(mapOf(it.getString("name") to child(it.get("type")))) }
            }
            "registryEntryHolderSet" -> {
                val spec = args as JSONObject
                val count = buf.readVarInt()
                require(count in 0..65536) { "注册表集合长度无效" }
                val field = spec.getJSONObject(if (count == 0) "base" else "otherwise")
                val value = if (count == 0) child(field.get("type"))
                    else NbtTag.NbtList(List(count - 1) { child(field.get("type")) })
                NbtTag.NbtCompound(mapOf(field.getString("name") to value))
            }
            else -> error("未实现的物品协议结构：$kind")
        }
    }
}

data class InventoryItem(val details: ItemDetails, val patchIdentity: String) {
    val count get() = details.count
    val maxStackSize get() = (details.components.entries["minecraft:max_stack_size"] as? NbtTag.NbtInt)?.value
        ?: error("物品缺少堆叠上限，暂不可移动")
    fun sameKind(other: InventoryItem) = patchIdentity == other.patchIdentity
}
