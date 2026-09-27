package com.bilicraft.handheld.protocol

import com.bilicraft.handheld.protocol.McTypes.readVarInt
import com.bilicraft.handheld.protocol.Nbt.readNetworkNbt
import io.netty.buffer.ByteBuf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InventoryState(
    val serverId: String? = null,
    val supported: Boolean = false,
    val ready: Boolean = false,
    val alive: Boolean = true,
    val slots: List<InventoryItem?> = List(46) { null },
    val cursor: InventoryItem? = null,
    val selectedHotbar: Int = 0,
    val stateId: Int = 0,
    val menu: ServerMenu? = null,
    val revision: Long = 0,
    val busy: Boolean = false,
    val message: String? = null
) {
    val openContainer: Int get() = menu?.id ?: 0
}

data class ServerMenu(
    val id: Int, val type: Int, val title: List<ChatSpan>, val generation: Long,
    val slots: List<InventoryItem?> = emptyList(), val stateId: Int = 0, val ready: Boolean = false
) {
    // Fixed MenuType registration order verified against the official 1.21.11 server.
    val containerSlots: Int get() = when (type) {
        in 0..5 -> (type + 1) * 9
        6 -> 9
        16 -> 5
        20 -> 27
        else -> 0
    }
    val columns: Int get() = when (type) { 6 -> 3; 16 -> 5; else -> 9 }
    val supported: Boolean get() = containerSlots > 0
}

/** Accessed only on the connection event loop; publish immutable snapshots to the UI. */
class InventoryTracker(private val codec: InventoryCodec) {
    private val mutableState = MutableStateFlow(InventoryState(supported = true))
    val state = mutableState.asStateFlow()
    var onSnapshot: ((Int) -> Unit)? = null

    fun status(busy: Boolean, message: String?) { mutableState.value = mutableState.value.copy(busy = busy, message = message) }
    fun requireResync(message: String) { mutableState.value = mutableState.value.copy(ready = false, busy = false, message = message) }
    fun selectHotbar(slot: Int) { mutableState.value = mutableState.value.copy(selectedHotbar = slot) }
    fun setAlive(alive: Boolean) { mutableState.value = mutableState.value.copy(alive = alive) }

    fun reset() { mutableState.value = InventoryState(supported = true) }

    fun closeMenu() {
        // Close sends the carried item back to the server; subsequent inventory packets supply its destination.
        mutableState.value = mutableState.value.copy(menu = null, cursor = null, busy = false, message = null, revision = revisions.incrementAndGet())
    }

    fun menuTimeout() {
        val old = mutableState.value
        mutableState.value = old.copy(menu = old.menu?.copy(ready = false), busy = false,
            message = "菜单响应超时，未重试；请关闭后重新打开菜单", revision = revisions.incrementAndGet())
    }

    fun receive(key: PacketKey, buf: ByteBuf) {
        val old = mutableState.value
        var next = old
        var updatedContainer: Int? = null
        try {
            when (key) {
                PacketKey.CB_INVENTORY_CONTENT -> {
                    val container = buf.readVarInt()
                    updatedContainer = container
                    val stateId = buf.readVarInt()
                    val count = buf.readVarInt()
                    require(count in 0..1024) { "容器槽位数量无效" }
                    val items = List(count) { codec.readSlot(buf) }
                    val cursor = codec.readSlot(buf)
                    if (container == 0) {
                        require(count == 46) { "个人背包槽位数不匹配：$count" }
                        next = old.copy(ready = true, slots = items, cursor = cursor, stateId = stateId, message = null)
                    } else if (container == old.menu?.id) {
                        val menu = old.menu
                        if (menu.supported) require(count == menu.containerSlots + 36) { "菜单槽位数量不匹配：$count" }
                        val playerSlots = old.slots.toMutableList()
                        if (menu.supported) for (index in 0..35) playerSlots[index + 9] = items[menu.containerSlots + index]
                        next = old.copy(menu = menu.copy(slots = items, stateId = stateId, ready = true),
                            slots = playerSlots, cursor = cursor, message = null)
                    } else {
                        return // Late data for a closed/replaced window must not acknowledge an action.
                    }
                }
                PacketKey.CB_INVENTORY_SLOT -> {
                    val container = buf.readVarInt()
                    val stateId = buf.readVarInt()
                    val slot = buf.readShort().toInt()
                    val item = codec.readSlot(buf)
                    if (container == 0) {
                        require(slot in 0..45) { "背包槽位无效" }
                        next = old.copy(slots = old.slots.toMutableList().apply { set(slot, item) }, stateId = stateId)
                    } else if (container == old.menu?.id) {
                        val menu = old.menu
                        if (!menu.ready) return // Wait for the first complete snapshot.
                        require(slot in menu.slots.indices) { "菜单槽位无效" }
                        val playerSlots = old.slots.toMutableList()
                        if (menu.supported && slot >= menu.containerSlots) playerSlots[slot - menu.containerSlots + 9] = item
                        next = old.copy(menu = menu.copy(slots = menu.slots.toMutableList().apply { set(slot, item) }, stateId = stateId), slots = playerSlots)
                    } else {
                        return
                    }
                }
                PacketKey.CB_INVENTORY_CURSOR -> next = old.copy(cursor = codec.readSlot(buf))
                PacketKey.CB_PLAYER_INVENTORY -> {
                    val index = buf.readVarInt()
                    val slot = when (index) {
                        in 0..8 -> index + 36
                        in 9..35 -> index
                        in 36..39 -> 44 - index
                        40 -> 45
                        else -> error("玩家背包索引无效：$index")
                    }
                    val item = codec.readSlot(buf)
                    next = old.copy(slots = old.slots.toMutableList().apply { set(slot, item) })
                }
                PacketKey.CB_HELD_SLOT -> {
                    val slot = buf.readVarInt()
                    require(slot in 0..8)
                    next = old.copy(selectedHotbar = slot)
                }
                PacketKey.CB_OPEN_CONTAINER -> {
                    val id = buf.readVarInt()
                    updatedContainer = id
                    val type = buf.readVarInt()
                    require(id > 0 && type >= 0) { "菜单类型或编号无效" }
                    val title = ChatComponent.spansFromNbt(buf.readNetworkNbt())
                    next = old.copy(menu = ServerMenu(id, type, title, revisions.incrementAndGet()), message = null)
                }
                PacketKey.CB_CLOSE_CONTAINER -> {
                    val container = buf.readVarInt()
                    updatedContainer = container
                    if (container == old.openContainer) next = old.copy(menu = null, cursor = null, message = null) else return
                }
                else -> return
            }
            require(!buf.isReadable) { "背包数据未完整读取" }
            mutableState.value = next.copy(revision = revisions.incrementAndGet())
            updatedContainer?.let { onSnapshot?.invoke(it) }
        } catch (error: Exception) {
            mutableState.value = old.copy(ready = false, menu = old.menu?.copy(ready = false), message = "背包同步失败：${error.message}", revision = revisions.incrementAndGet())
            onSnapshot?.invoke(old.openContainer)
        }
    }
    private companion object { val revisions = java.util.concurrent.atomic.AtomicLong() }
}
