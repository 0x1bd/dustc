package org.kvxd.dust.device

data class BlockPos(val x: Int, val y: Int, val z: Int) {

    fun offset(direction: Direction): BlockPos = BlockPos(x + direction.dx, y + direction.dy, z + direction.dz)

    fun offset(direction: Direction, distance: Int): BlockPos =
        BlockPos(x + direction.dx * distance, y + direction.dy * distance, z + direction.dz * distance)

    operator fun plus(other: BlockPos): BlockPos = BlockPos(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: BlockPos): BlockPos = BlockPos(x - other.x, y - other.y, z - other.z)

    fun manhattanTo(other: BlockPos): Int = Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z)

    override fun toString(): String = "($x, $y, $z)"

    companion object {
        val ORIGIN: BlockPos = BlockPos(0, 0, 0)
    }
}
