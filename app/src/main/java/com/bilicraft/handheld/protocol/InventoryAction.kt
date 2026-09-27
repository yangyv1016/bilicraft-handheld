package com.bilicraft.handheld.protocol

sealed interface InventoryAction {
    data class Move(val source: Int, val target: Int, val quantity: Int? = null) : InventoryAction
    data class Drop(val slot: Int, val all: Boolean) : InventoryAction
    data class PlaceCursor(val target: Int) : InventoryAction
}

data class InventoryClick(val slot: Int, val button: Int, val mode: Int,
    val expectedSlots: Map<Int, InventoryItem?>, val expectedCursor: InventoryItem?)

/** Pure click planner; it never changes the displayed authoritative inventory. */
object InventoryActions {
    fun equipmentSlot(item: InventoryItem): Int? {
        val equip = item.details.components.entries["minecraft:equippable"] as? NbtTag.NbtCompound ?: return null
        return when ((equip.entries["slot"] as? NbtTag.NbtString)?.value) {
            "head" -> 5; "chest" -> 6; "legs" -> 7; "feet" -> 8; "offhand", "off_hand" -> 45
            else -> null
        }
    }

    fun plan(state: InventoryState, action: InventoryAction): List<InventoryClick> {
        require(state.ready && state.alive && !state.busy && state.openContainer == 0) { "背包当前不可操作" }
        fun valid(slot: Int) { require(slot in 5..45) { "请选择个人背包槽位" } }
        fun accepts(slot: Int, item: InventoryItem?) {
            if (slot in 5..8 && item != null) require(equipmentSlot(item) == slot && item.count == 1) { "此物品不能放入该装备槽" }
        }
        fun amount(item: InventoryItem, count: Int) = if (count == 0) null else item.copy(details = item.details.copy(count = count))
        val slots = state.slots.toMutableList()
        var cursor = state.cursor
        val changed = linkedMapOf<Int, InventoryItem?>()
        val clicks = mutableListOf<InventoryClick>()
        fun pickup(slot: Int, right: Boolean = false) {
            val target = slots[slot]
            val held = cursor
            if (held == null) {
                require(target != null) { "物品已不在该槽位" }
                val count = if (right) (target.count + 1) / 2 else target.count
                cursor = amount(target, count)
                slots[slot] = amount(target, target.count - count)
            } else {
                accepts(slot, held)
                if (target == null || held.sameKind(target)) {
                    val capacity = (if (slot in 5..8) 1 else held.maxStackSize) - (target?.count ?: 0)
                    require(capacity > 0) { "目标槽位已满" }
                    val count = minOf(if (right) 1 else held.count, capacity)
                    slots[slot] = amount(held, (target?.count ?: 0) + count)
                    cursor = amount(held, held.count - count)
                } else {
                    require(!right) { "拆分需要空槽位或相同物品" }
                    slots[slot] = held
                    cursor = target
                }
            }
            changed[slot] = slots[slot]
            clicks += InventoryClick(slot, if (right) 1 else 0, 0, changed.toMap(), cursor)
        }
        when (action) {
            is InventoryAction.Move -> {
                require(cursor == null) { "请先放回光标上的物品" }
                valid(action.source); valid(action.target)
                require(action.source != action.target) { "请选择不同的目标槽位" }
                val source = slots[action.source] ?: error("该槽位没有物品")
                val target = slots[action.target]
                val quantity = action.quantity ?: source.count
                require(quantity in 1..source.count)
                accepts(action.target, amount(source, quantity))
                if (target != null && !source.sameKind(target)) accepts(action.source, target)
                if (quantity < source.count) {
                    require(target == null || source.sameKind(target)) { "拆分需要空槽位或相同物品" }
                    require(quantity + (target?.count ?: 0) <= source.maxStackSize) { "目标槽位容量不足" }
                    pickup(action.source)
                    repeat(quantity) { pickup(action.target, right = true) }
                } else {
                    pickup(action.source)
                    pickup(action.target)
                }
                if (cursor != null) pickup(action.source)
            }
            is InventoryAction.Drop -> {
                require(cursor == null) { "请先放回光标上的物品" }
                valid(action.slot)
                val item = slots[action.slot] ?: error("该槽位没有物品")
                slots[action.slot] = if (action.all) null else amount(item, item.count - 1)
                clicks += InventoryClick(action.slot, if (action.all) 1 else 0, 4, mapOf(action.slot to slots[action.slot]), null)
            }
            is InventoryAction.PlaceCursor -> {
                valid(action.target)
                require(cursor != null) { "光标没有物品" }
                pickup(action.target)
            }
        }
        return clicks
    }
}
