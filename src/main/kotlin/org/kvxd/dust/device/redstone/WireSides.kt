package org.kvxd.dust.device.redstone

import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties

data class WireSides(
    val north: WireConnection,
    val south: WireConnection,
    val east: WireConnection,
    val west: WireConnection,
) {
    operator fun get(direction: Direction): WireConnection = when (direction) {
        Direction.NORTH -> north
        Direction.SOUTH -> south
        Direction.EAST -> east
        Direction.WEST -> west
        else -> error("$direction is not horizontal")
    }

    val isDot: Boolean
        get() = north == WireConnection.NONE && south == WireConnection.NONE &&
            east == WireConnection.NONE && west == WireConnection.NONE

    fun applyTo(state: BlockState): BlockState = state
        .with(Properties.WIRE_NORTH, north)
        .with(Properties.WIRE_SOUTH, south)
        .with(Properties.WIRE_EAST, east)
        .with(Properties.WIRE_WEST, west)

    companion object {
        val CROSS = WireSides(
            WireConnection.SIDE,
            WireConnection.SIDE,
            WireConnection.SIDE,
            WireConnection.SIDE,
        )

        fun of(state: BlockState): WireSides = WireSides(
            state[Properties.WIRE_NORTH],
            state[Properties.WIRE_SOUTH],
            state[Properties.WIRE_EAST],
            state[Properties.WIRE_WEST],
        )
    }
}
