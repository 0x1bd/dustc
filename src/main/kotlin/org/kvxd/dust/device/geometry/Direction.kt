package org.kvxd.dust.device.geometry

enum class Direction(val dx: Int, val dy: Int, val dz: Int) {
    DOWN(0, -1, 0),
    UP(0, 1, 0),
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    ;

    val axis: Axis
        get() = when (this) {
            UP, DOWN -> Axis.Y
            NORTH, SOUTH -> Axis.Z
            WEST, EAST -> Axis.X
        }

    val isHorizontal: Boolean get() = axis != Axis.Y

    val opposite: Direction
        get() = when (this) {
            DOWN -> UP
            UP -> DOWN
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }

    fun rotateClockwise(): Direction = when (this) {
        NORTH -> EAST
        EAST -> SOUTH
        SOUTH -> WEST
        WEST -> NORTH
        else -> this
    }

    fun rotateCounterClockwise(): Direction = when (this) {
        NORTH -> WEST
        WEST -> SOUTH
        SOUTH -> EAST
        EAST -> NORTH
        else -> this
    }

    val stateName: String get() = name.lowercase()

    companion object {
        val HORIZONTALS: List<Direction> = listOf(NORTH, SOUTH, WEST, EAST)

        val ALL: List<Direction> = listOf(DOWN, UP, NORTH, SOUTH, WEST, EAST)

        fun fromStateName(name: String): Direction? = entries.firstOrNull { it.stateName == name }
    }
}
