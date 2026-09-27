package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.NbtTag
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** Canonical component data, shared by inventory slots and chat show_item components. */
data class PlayerHeadProfile(val id: UUID?, val name: String?, val texture: String?, val properties: NbtTag?) {
    val dynamic get() = properties == null && ((id == null) != (name == null))

    fun defaultTexture(): String {
        // Official 1.21.11 DefaultPlayerSkin order and floorMod(UUID.hashCode(), 18).
        val index = id?.let { Math.floorMod(it.hashCode(), 18) } ?: 6
        val names = listOf("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")
        return "minecraft:entity/player/${if (index < 9) "slim" else "wide"}/${names[index % 9]}"
    }

    fun skinUrl(): String? {
        val encoded = when (properties) {
            is NbtTag.NbtList -> properties.items.firstNotNullOfOrNull { property ->
                val fields = (property as? NbtTag.NbtCompound)?.entries
                if ((fields?.get("name") as? NbtTag.NbtString)?.value == "textures")
                    (fields["value"] as? NbtTag.NbtString)?.value else null
            }
            is NbtTag.NbtCompound -> ((properties.entries["textures"] as? NbtTag.NbtList)?.items?.firstOrNull() as? NbtTag.NbtString)?.value
            else -> null
        } ?: return null
        require(encoded.length <= 65536) { "头颅皮肤资料过大" }
        val textures = JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)).optJSONObject("textures")
        val skin = textures?.optJSONObject("SKIN") ?: return null
        return validatedSkinUrl(skin.getString("url"))
    }

    companion object {
        fun from(tag: NbtTag?): PlayerHeadProfile {
            if (tag is NbtTag.NbtString) return PlayerHeadProfile(null, tag.value, null, null)
            val fields = (tag as? NbtTag.NbtCompound)?.entries.orEmpty()
            val id = when (val value = fields["id"]) {
                is NbtTag.NbtString -> UUID.fromString(value.value)
                is NbtTag.NbtList -> {
                    require(value.items.size == 4 && value.items.all { it is NbtTag.NbtInt }) { "头颅 UUID 无效" }
                    val parts = value.items.map { (it as NbtTag.NbtInt).value.toLong() }
                    UUID((parts[0] shl 32) or (parts[1] and 0xffffffffL),
                        (parts[2] shl 32) or (parts[3] and 0xffffffffL))
                }
                else -> null
            }
            return PlayerHeadProfile(id, (fields["name"] as? NbtTag.NbtString)?.value,
                (fields["texture"] as? NbtTag.NbtString)?.value, fields["properties"])
        }

        fun validatedSkinUrl(value: String): String {
            val url = requireNotNull(value.toHttpUrlOrNull()) { "头颅皮肤地址无效" }
            require(url.host == "textures.minecraft.net" && url.port == (if (url.isHttps) 443 else 80) &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.encodedPath.matches(Regex("/texture/[a-fA-F0-9]{1,64}"))) { "头颅皮肤地址不是 Minecraft 官方贴图" }
            return url.newBuilder().scheme("https").port(443).build().toString()
        }
    }
}
