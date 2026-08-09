package org.kvxd.dust.physical

import org.kvxd.dust.device.Direction

enum class PhysicalIoEdge(val outward: Direction) {
    NORTH(Direction.NORTH),
    SOUTH(Direction.SOUTH),
    EAST(Direction.EAST),
    WEST(Direction.WEST),
    ;

    val inward: Direction get() = outward.opposite

    companion object {
        fun fromName(name: String): PhysicalIoEdge? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
