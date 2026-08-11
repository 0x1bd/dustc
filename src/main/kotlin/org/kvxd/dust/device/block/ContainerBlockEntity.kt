package org.kvxd.dust.device.block

data class ContainerBlockEntity(
    val slots: Int,
    val items: List<ItemStack>,
    override val id: String = "minecraft:barrel",
) : BlockEntity {
    init {
        require(slots > 0)
        require(items.map { it.slot }.distinct().size == items.size)
        require(items.all { it.slot in 0 until slots })
    }

    val comparatorOutput: Int
        get() {
            if (items.isEmpty()) return 0
            val fullness = items.sumOf { it.count.toDouble() / it.maximumCount } / slots
            return kotlin.math.floor(1.0 + fullness * 14.0).toInt().coerceIn(1, 15)
        }

    companion object {
        const val BARREL_SLOTS: Int = 27

        fun barrelSignal(signal: Int, itemId: String = "minecraft:redstone"): ContainerBlockEntity {
            require(signal in 0..15)
            if (signal == 0) return ContainerBlockEntity(BARREL_SLOTS, emptyList())
            val capacity = BARREL_SLOTS * 64
            val itemCount = if (signal == 15) {
                capacity
            } else {
                (((signal - 1) * capacity + 13) / 14).coerceAtLeast(1)
            }
            var remaining = itemCount
            val items = buildList {
                var slot = 0
                while (remaining > 0) {
                    val count = minOf(64, remaining)
                    add(ItemStack(slot++, itemId, count))
                    remaining -= count
                }
            }
            return ContainerBlockEntity(BARREL_SLOTS, items).also {
                require(it.comparatorOutput == signal) {
                    "inventory encoded signal ${it.comparatorOutput}, expected $signal"
                }
            }
        }
    }
}
