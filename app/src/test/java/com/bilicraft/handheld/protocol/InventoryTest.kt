package com.bilicraft.handheld.protocol

import com.bilicraft.handheld.protocol.McTypes.writeVarInt
import io.netty.buffer.Unpooled
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InventoryTest {
    @Test fun playerProfilesNormalizeWireNamesAndKeepStaticDynamicDistinction() {
        val codec = codec()
        val id = codec.itemNames.keys().asSequence().first { codec.itemNames.getString(it) == "player_head" }.toInt()
        val types = codec.schema.getJSONArray("SlotComponentType").getJSONObject(1).getJSONObject("mappings")
        val profileType = types.keys().asSequence().first { types.getString(it) == "profile" }.toInt()
        for (complete in listOf(false, true)) {
            val buffer = Unpooled.buffer()
            try {
                buffer.writeVarInt(1).writeVarInt(id).writeVarInt(1).writeVarInt(0).writeVarInt(profileType)
                buffer.writeVarInt(if (complete) 1 else 0)
                if (complete) {
                    buffer.writeLong(0).writeLong(1)
                    with(McTypes) { buffer.writeString("Player") }
                } else {
                    buffer.writeBoolean(true)
                    with(McTypes) { buffer.writeString("Player") }
                    buffer.writeBoolean(false)
                }
                buffer.writeVarInt(0).writeBoolean(true)
                with(McTypes) { buffer.writeString("server:skin") }
                buffer.writeBoolean(false).writeBoolean(false).writeBoolean(false)
                val profile = (codec.readSlot(buffer)!!.details.components.entries.getValue("minecraft:profile") as NbtTag.NbtCompound).entries
                assertEquals(NbtTag.NbtString("server:skin"), profile["texture"])
                assertEquals(complete, "properties" in profile)
                assertEquals(complete, "id" in profile)
                assertFalse(buffer.isReadable)
            } finally { buffer.release() }
        }
    }
    @Test fun itemNbtSupportsSupplementaryCharactersAndRejectsOversizedLists() {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { it.writeByte(8); it.writeUTF("物品 😀\u0000") }
        val buf = Unpooled.wrappedBuffer(bytes.toByteArray())
        val invalid = Unpooled.buffer().writeByte(9).writeByte(3).writeInt(Int.MAX_VALUE)
        try {
            assertEquals(NbtTag.NbtString("物品 😀\u0000"), with(Nbt) { buf.readNetworkNbt() })
            assertThrows(IllegalArgumentException::class.java) { with(Nbt) { invalid.readNetworkNbt() } }
        } finally { buf.release(); invalid.release() }
    }
    private fun codec(): InventoryCodec {
        fun asset(name: String) = JSONObject(File("src/main/assets/minecraft/inventory-774/$name.json").readText())
        return InventoryCodec(asset("slot-schema"), asset("items"), asset("item-components"))
    }

    @Test fun vanillaDefaultsAndCustomPatchesRemainDistinct() {
        val codec = codec()
        val id = codec.itemNames.keys().asSequence().first { codec.itemNames.getString(it) == "diamond_sword" }.toInt()
        val buffer = Unpooled.buffer()
        try {
            buffer.writeVarInt(1).writeVarInt(id).writeVarInt(2).writeVarInt(1)
            buffer.writeVarInt(3).writeVarInt(12) // damage
            buffer.writeVarInt(13).writeVarInt(1).writeVarInt(0).writeVarInt(5) // enchantments
            buffer.writeVarInt(9) // remove item_name
            codec.registries["minecraft:enchantment"] = listOf("minecraft:sharpness")
            val item = codec.readSlot(buffer)!!
            assertFalse(buffer.isReadable)
            assertEquals(1561, (item.details.components.entries["minecraft:max_damage"] as NbtTag.NbtInt).value)
            assertEquals(12, (item.details.components.entries["minecraft:damage"] as NbtTag.NbtInt).value)
            assertFalse(item.details.components.entries.containsKey("minecraft:item_name"))
            assertEquals(NbtTag.NbtInt(5), (item.details.components.entries["minecraft:enchantments"] as NbtTag.NbtCompound).entries["minecraft:sharpness"])
        } finally { buffer.release() }
    }

    @Test fun emptySlotsAndNestedItemsConsumeExactlyTheirBytes() {
        val codec = codec()
        val buffer = Unpooled.buffer()
        try {
            buffer.writeVarInt(0)
            assertNull(codec.readSlot(buffer))
            val id = codec.itemNames.keys().asSequence().first { codec.itemNames.getString(it) == "bundle" }.toInt()
            buffer.writeVarInt(1).writeVarInt(id).writeVarInt(1).writeVarInt(0)
            buffer.writeVarInt(48).writeVarInt(2).writeVarInt(0).writeVarInt(0)
            assertEquals("minecraft:bundle", codec.readSlot(buffer)!!.details.id)
            assertFalse(buffer.isReadable)
        } finally { buffer.release() }
    }

    @Test fun unknownComponentFailsInsteadOfGuessingPayloadLength() {
        val buffer = Unpooled.buffer()
        try {
            buffer.writeVarInt(1).writeVarInt(1).writeVarInt(1).writeVarInt(0).writeVarInt(999)
            assertThrows(Exception::class.java) { codec().readSlot(buffer) }
        } finally { buffer.release() }
    }

    @Test fun inventorySnapshotsUpdatesAndPlayerIndicesStayConsistent() {
        val tracker = InventoryTracker(codec())
        val content = Unpooled.buffer()
        val update = Unpooled.buffer()
        try {
            content.writeVarInt(0).writeVarInt(17).writeVarInt(46)
            repeat(47) { content.writeVarInt(0) }
            tracker.receive(PacketKey.CB_INVENTORY_CONTENT, content)
            assertTrue(tracker.state.value.ready)
            assertEquals(17, tracker.state.value.stateId)
            update.writeVarInt(40).writeVarInt(3).writeVarInt(1).writeVarInt(0).writeVarInt(0)
            tracker.receive(PacketKey.CB_PLAYER_INVENTORY, update)
            assertEquals(3, tracker.state.value.slots[45]!!.count)
            assertNull(tracker.state.value.slots[40])
            tracker.reset()
            assertFalse(tracker.state.value.ready)
            assertTrue(tracker.state.value.slots.all { it == null })
        } finally { content.release(); update.release() }
    }

    @Test fun malformedSnapshotDisablesOperationsWithoutPublishingPartialItems() {
        val tracker = InventoryTracker(codec())
        val content = Unpooled.buffer()
        try {
            content.writeVarInt(0).writeVarInt(1).writeVarInt(46).writeVarInt(1)
            tracker.receive(PacketKey.CB_INVENTORY_CONTENT, content)
            assertFalse(tracker.state.value.ready)
            assertTrue(tracker.state.value.slots.all { it == null })
            assertTrue(tracker.state.value.message!!.startsWith("背包同步失败"))
        } finally { content.release() }
    }

    @Test fun inventoryPacketIdsAreExclusiveTo774() {
        val palette = PaletteRegistry.forProtocol(774)
        assertEquals(PacketKey.CB_INVENTORY_CONTENT, palette.cbKey(0x12, PacketPhase.PLAY))
        assertEquals(PacketKey.CB_CONFIG_REGISTRY, palette.cbKey(0x07, PacketPhase.CONFIGURATION))
        assertEquals(0x11, palette.sbId(PacketKey.SB_INVENTORY_CLICK))
        assertNull(PaletteRegistry.forProtocol(773).sbId(PacketKey.SB_INVENTORY_CLICK))
    }

    private fun stack(name: String, count: Int, maximum: Int = 64, equipment: String? = null): InventoryItem {
        val components = mutableMapOf<String, NbtTag>("minecraft:max_stack_size" to NbtTag.NbtInt(maximum))
        if (equipment != null) components["minecraft:equippable"] = NbtTag.NbtCompound(mapOf("slot" to NbtTag.NbtString(equipment)))
        return InventoryItem(ItemDetails("minecraft:$name", count, NbtTag.NbtCompound(components)), name)
    }
    private fun inventory(vararg items: Pair<Int, InventoryItem>) = InventoryState(supported = true, ready = true,
        slots = List(46) { slot -> items.firstOrNull { it.first == slot }?.second })

    @Test fun splitAndMergeConserveEveryItemAndFinishWithEmptyCursor() {
        val original = inventory(9 to stack("stone", 16), 10 to stack("stone", 61))
        val merge = InventoryActions.plan(original, InventoryAction.Move(9, 10))
        assertEquals(3, merge.size)
        assertEquals(64, merge.last().expectedSlots[10]!!.count)
        assertEquals(13, merge.last().expectedSlots[9]!!.count)
        assertNull(merge.last().expectedCursor)
        val split = InventoryActions.plan(original, InventoryAction.Move(9, 11, 5))
        assertEquals(5, split.last().expectedSlots[11]!!.count)
        assertEquals(11, split.last().expectedSlots[9]!!.count)
        assertNull(split.last().expectedCursor)
        assertTrue(split.all { click -> click.expectedSlots.values.filterNotNull().sumOf { it.count } + (click.expectedCursor?.count ?: 0) == 16 })
    }

    @Test fun swapEquipmentAndDropsHaveExactExpectedStates() {
        val state = inventory(9 to stack("stone", 16), 10 to stack("dirt", 4), 11 to stack("diamond_helmet", 1, 1, "head"))
        val swap = InventoryActions.plan(state, InventoryAction.Move(9, 10)).last()
        assertEquals("minecraft:dirt", swap.expectedSlots[9]!!.details.id)
        assertEquals(16, swap.expectedSlots[10]!!.count)
        assertNull(swap.expectedCursor)
        assertEquals("minecraft:diamond_helmet", InventoryActions.plan(state, InventoryAction.Move(11, 5)).last().expectedSlots[5]!!.details.id)
        assertEquals(15, InventoryActions.plan(state, InventoryAction.Drop(9, false)).single().expectedSlots[9]!!.count)
        assertNull(InventoryActions.plan(state, InventoryAction.Drop(9, true)).single().expectedSlots[9])
    }

    @Test fun invalidTargetsAndUncertainStateCannotGenerateClicks() {
        val state = inventory(9 to stack("stone", 16), 10 to stack("dirt", 4))
        for (action in listOf(InventoryAction.Move(9, 9), InventoryAction.Move(9, 5), InventoryAction.Move(9, 10, 3), InventoryAction.Move(9, 0))) {
            assertThrows(IllegalArgumentException::class.java) { InventoryActions.plan(state, action) }
        }
        assertThrows(IllegalArgumentException::class.java) { InventoryActions.plan(state.copy(ready = false), InventoryAction.Drop(9, true)) }
        assertThrows(IllegalArgumentException::class.java) { InventoryActions.plan(state.copy(cursor = stack("stone", 1)), InventoryAction.Drop(9, true)) }
    }

    @Test fun cursorRecoveryUsesAnExplicitDestination() {
        val state = inventory().copy(cursor = stack("stone", 12))
        val click = InventoryActions.plan(state, InventoryAction.PlaceCursor(9)).single()
        assertEquals(12, click.expectedSlots[9]!!.count)
        assertNull(click.expectedCursor)
    }

    private fun openMenu(tracker: InventoryTracker, id: Int, type: Int, title: String = "Menu") {
        val buffer = Unpooled.buffer().writeVarInt(id).writeVarInt(type)
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { it.writeByte(8); it.writeUTF(title) }
        buffer.writeBytes(bytes.toByteArray())
        try { tracker.receive(PacketKey.CB_OPEN_CONTAINER, buffer) } finally { buffer.release() }
    }

    private fun menuContent(tracker: InventoryTracker, id: Int, count: Int, occupied: Int = -1) {
        val buffer = Unpooled.buffer().writeVarInt(id).writeVarInt(7).writeVarInt(count)
        for (slot in 0 until count) {
            if (slot == occupied) buffer.writeVarInt(3).writeVarInt(1).writeVarInt(0).writeVarInt(0)
            else buffer.writeVarInt(0)
        }
        buffer.writeVarInt(0)
        try { tracker.receive(PacketKey.CB_INVENTORY_CONTENT, buffer) } finally { buffer.release() }
    }

    @Test fun menuSnapshotMapsPlayerSlotsAndIgnoresClosedWindowResponses() {
        val tracker = InventoryTracker(codec())
        openMenu(tracker, 2, 2, "菜单 😀")
        assertEquals("菜单 😀", tracker.state.value.menu!!.title.joinToString("") { it.text })
        assertFalse(tracker.state.value.menu!!.ready)
        menuContent(tracker, 2, 63, 54)
        assertEquals(3, tracker.state.value.slots[36]!!.count)
        assertEquals(3, tracker.state.value.menu!!.slots[54]!!.count)
        assertTrue(tracker.state.value.menu!!.ready)
        val generation = tracker.state.value.menu!!.generation
        openMenu(tracker, 3, 0)
        assertNotEquals(generation, tracker.state.value.menu!!.generation)
        var callbacks = 0
        tracker.onSnapshot = { callbacks++ }
        menuContent(tracker, 2, 63)
        val close = Unpooled.buffer().writeVarInt(2)
        try { tracker.receive(PacketKey.CB_CLOSE_CONTAINER, close) } finally { close.release() }
        assertEquals(0, callbacks)
        assertEquals(3, tracker.state.value.menu!!.id)
        assertFalse(tracker.state.value.menu!!.ready)
    }

    @Test fun menuSlotUpdatesAndWindowReuseCannotLeakOldContents() {
        val tracker = InventoryTracker(codec())
        openMenu(tracker, 1, 16)
        menuContent(tracker, 1, 41)
        val update = Unpooled.buffer().writeVarInt(1).writeVarInt(8).writeShort(40)
            .writeVarInt(3).writeVarInt(1).writeVarInt(0).writeVarInt(0)
        try { tracker.receive(PacketKey.CB_INVENTORY_SLOT, update) } finally { update.release() }
        assertEquals(3, tracker.state.value.slots[44]!!.count)
        assertEquals(8, tracker.state.value.menu!!.stateId)
        val previous = tracker.state.value.menu!!.generation
        openMenu(tracker, 1, 5)
        assertNotEquals(previous, tracker.state.value.menu!!.generation)
        assertTrue(tracker.state.value.menu!!.slots.isEmpty())
        assertFalse(tracker.state.value.menu!!.ready)
    }

    @Test fun malformedMenuAndTimeoutDisableClicksWhileUnsupportedMenusStayClosable() {
        val tracker = InventoryTracker(codec())
        openMenu(tracker, 1, 0)
        menuContent(tracker, 1, 44)
        assertFalse(tracker.state.value.menu!!.ready)
        assertTrue(tracker.state.value.message!!.contains("槽位数量"))
        menuContent(tracker, 1, 45)
        tracker.status(true, null)
        tracker.menuTimeout()
        assertFalse(tracker.state.value.busy)
        assertFalse(tracker.state.value.menu!!.ready)
        openMenu(tracker, 2, 8)
        assertFalse(tracker.state.value.menu!!.supported)
        tracker.closeMenu()
        assertEquals(0, tracker.state.value.openContainer)
        assertNull(tracker.state.value.menu)
    }
}
