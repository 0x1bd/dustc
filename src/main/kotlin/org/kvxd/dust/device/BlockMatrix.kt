package org.kvxd.dust.device

class BlockMatrix(
    val width: Int,
    val height: Int,
    val length: Int,
) : BlockAccess {
    init {
        require(width > 0 && height > 0 && length > 0)
    }

    val volume: Int = Math.multiplyExact(Math.multiplyExact(width, height), length)
    private val air = BlockType.AIR.defaultState
    private val states = Array(volume) { air }
    private val blockEntities = HashMap<Int, BlockEntity>()
    private var occupied = 0

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in 0 until width && y in 0 until height && z in 0 until length

    fun contains(pos: BlockPos): Boolean = contains(pos.x, pos.y, pos.z)

    fun indexOf(x: Int, y: Int, z: Int): Int = (y * length + z) * width + x

    operator fun get(x: Int, y: Int, z: Int): BlockState =
        if (contains(x, y, z)) states[indexOf(x, y, z)] else air

    operator fun set(x: Int, y: Int, z: Int, state: BlockState) {
        require(contains(x, y, z))
        val index = indexOf(x, y, z)
        val previous = states[index]
        if (previous == state) return
        if (previous.isAir && !state.isAir) occupied++
        if (!previous.isAir && state.isAir) occupied--
        states[index] = state
        if (state.type != BlockType.BARREL && state.type != BlockType.OAK_WALL_SIGN) blockEntities.remove(index)
    }

    override fun blockAt(pos: BlockPos): BlockState = get(pos.x, pos.y, pos.z)

    override fun blockEntityAt(pos: BlockPos): BlockEntity? =
        if (contains(pos)) blockEntities[indexOf(pos.x, pos.y, pos.z)] else null

    fun setBlockAt(pos: BlockPos, state: BlockState) {
        set(pos.x, pos.y, pos.z, state)
    }

    fun setBlockEntityAt(pos: BlockPos, blockEntity: BlockEntity) {
        require(contains(pos))
        val expected = when (blockEntity) {
            is ContainerBlockEntity -> BlockType.BARREL
            is SignBlockEntity -> BlockType.OAK_WALL_SIGN
        }
        require(blockAt(pos).type == expected) {
            "$pos contains ${blockAt(pos).type.id}, expected ${expected.id}"
        }
        blockEntities[indexOf(pos.x, pos.y, pos.z)] = blockEntity
    }

    fun blockEntities(): Map<BlockPos, BlockEntity> = blockEntities.mapKeys { (index, _) ->
        BlockPos(index % width, index / (width * length), (index / width) % length)
    }

    inline fun forEachPosition(action: (x: Int, y: Int, z: Int, state: BlockState) -> Unit) {
        for (y in 0 until height) {
            for (z in 0 until length) {
                for (x in 0 until width) action(x, y, z, this[x, y, z])
            }
        }
    }

    fun blockCount(): Int = occupied

    override fun toString(): String = "BlockMatrix(${width}x${height}x$length)"
}
