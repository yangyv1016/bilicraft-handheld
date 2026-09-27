package com.bilicraft.handheld.resourcepack

import com.bilicraft.handheld.protocol.ItemDetails
import com.bilicraft.handheld.protocol.Nbt
import com.bilicraft.handheld.protocol.NbtTag
import io.netty.buffer.Unpooled
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PlayerHeadProfileTest {
    private fun profile(raw: String) = PlayerHeadProfile.from(Nbt.fromJson(JSONObject(raw)))

    @Test fun staticAndDynamicProfilesFollow1219Rules() {
        assertTrue(profile("""{"name":"Player"}""").dynamic)
        assertTrue(profile("""{"id":"00000000-0000-0000-0000-000000000001"}""").dynamic)
        assertFalse(profile("""{"name":"Player","id":"00000000-0000-0000-0000-000000000001"}""").dynamic)
        assertFalse(profile("""{"name":"Decoration","properties":[]}""").dynamic)
        assertFalse(profile("{}").dynamic)
    }

    @Test fun listAndMapPropertiesResolveTheSameOfficialTexture() {
        val value = Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/abcdef0123"}}}""".toByteArray())
        for (properties in listOf("""[{"name":"textures","value":"$value"}]""", """{"textures":["$value"]}""")) {
            assertEquals("https://textures.minecraft.net/texture/abcdef0123", profile("""{"properties":$properties}""").skinUrl())
        }
    }

    @Test fun textureUrlsCannotTargetOtherHostsOrRedirectEndpoints() {
        for (url in listOf("file:///skin.png", "https://textures.minecraft.net.evil.test/texture/ab", "http://127.0.0.1/texture/ab",
            "https://textures.minecraft.net:8443/texture/ab", "https://user@textures.minecraft.net/texture/ab",
            "https://textures.minecraft.net/texture/ab?redirect=x", "https://textures.minecraft.net/other/ab")) {
            assertThrows(IllegalArgumentException::class.java) { PlayerHeadProfile.validatedSkinUrl(url) }
        }
    }

    @Test fun uuidIntegerArraysSurviveNetworkNbtAndSelectTheSameDefaultSkin() {
        val buffer = Unpooled.buffer().writeByte(11).writeInt(4).writeInt(-1).writeInt(-2).writeInt(-3).writeInt(-4)
        try {
            val id = with(Nbt) { buffer.readNetworkNbt() }
            val parsed = PlayerHeadProfile.from(NbtTag.NbtCompound(mapOf("id" to id)))
            assertEquals(UUID(-2L, -8589934596L), parsed.id)
            assertEquals(profile("""{"id":"${parsed.id}"}""").defaultTexture(), parsed.defaultTexture())
            assertFalse(buffer.isReadable)
        } finally { buffer.release() }
    }

    @Test fun explicitResourcePackSkinAndDefaultSteveArePreserved() {
        assertEquals("server:heads/test", profile("""{"texture":"server:heads/test"}""").texture)
        assertEquals("minecraft:entity/player/slim/steve", profile("{}").defaultTexture())
        assertEquals("minecraft:entity/player/slim/alex", profile("""{"id":"00000000-0000-0000-0000-000000000000"}""").defaultTexture())
    }

    @Test fun specialHeadSelectionHonorsResourcePackOverridesAndOtherItemIds() {
        val special = JSONObject("""{"type":"minecraft:special","base":"minecraft:item/template_skull","model":{"type":"minecraft:player_head"}}""")
        assertSame(special, ItemModelSelector.select(special, ItemDetails("minecraft:paper", 1)).single())
        val ordinary = JSONObject("""{"type":"minecraft:model","model":"server:custom_head"}""")
        assertSame(ordinary, ItemModelSelector.select(ordinary, ItemDetails("minecraft:player_head", 1)).single())
    }
}
