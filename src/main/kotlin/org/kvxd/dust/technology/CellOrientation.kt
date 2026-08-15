package org.kvxd.dust.technology

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties

enum class CellOrientation(private val clockwiseTurns: Int) {
    SOUTH(0),
    WEST(1),
    NORTH(2),
    EAST(3),
    ;

    fun rotate(direction: Direction): Direction {
        var result = direction
        repeat(clockwiseTurns) { result = result.rotateClockwise() }
        return result
    }

    fun size(size: CellSize): CellSize = if (clockwiseTurns % 2 == 0) size else CellSize(size.z, size.y, size.x)

    fun position(position: BlockPos, size: CellSize): BlockPos = when (clockwiseTurns) {
        0 -> position
        1 -> BlockPos(size.z - 1 - position.z, position.y, position.x)
        2 -> BlockPos(size.x - 1 - position.x, position.y, size.z - 1 - position.z)
        3 -> BlockPos(position.z, position.y, size.x - 1 - position.x)
        else -> error("unreachable")
    }

    internal fun state(state: BlockState): BlockState {
        var result = state
        state.getOrNull(Properties.FACING)?.let { result = result.with(Properties.FACING, rotate(it)) }
        state.getOrNull(Properties.BLOCK_FACING)?.let {
            result = result.with(Properties.BLOCK_FACING, rotate(it))
        }
        val wire = Direction.HORIZONTALS.associateWith { direction ->
            state.getOrNull(Properties.wireSide(direction))
        }
        if (wire.values.any { it != null }) {
            wire.forEach { (direction, connection) ->
                if (connection != null) result = result.with(Properties.wireSide(rotate(direction)), connection)
            }
        }
        return result
    }

    companion object {
        fun forAccessDirection(direction: Direction): CellOrientation = when (direction) {
            Direction.SOUTH -> SOUTH
            Direction.WEST -> WEST
            Direction.NORTH -> NORTH
            Direction.EAST -> EAST
            else -> throw IllegalArgumentException("cell access direction must be horizontal, got $direction")
        }
    }
}
