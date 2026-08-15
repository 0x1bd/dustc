package org.kvxd.dust.device.block

import org.kvxd.dust.device.geometry.BlockPos

class BlockMatrix(
    val width: Int,
    val height: Int,
    val length: Int,
) : BlockAccess {
    init {
        require(width > 0 && height > 0 && length > 0)
    }

    val volume: Int = run {
        val value = width.toLong() * height * length
        require(value <= Int.MAX_VALUE) { "$width x $height x $length exceeds the supported block volume" }
        value.toInt()
    }
    private val air = BlockType.AIR.defaultState
    private val chunkWidth = ((width - 1) shr CHUNK_BITS) + 1
    private val chunkLength = ((length - 1) shr CHUNK_BITS) + 1
    private val chunks = HashMap<Int, Chunk>()
    private val blockEntities = HashMap<Int, BlockEntity>()
    private var occupied = 0

    fun contains(x: Int, y: Int, z: Int): Boolean =
        x in 0 until width && y in 0 until height && z in 0 until length

    fun contains(pos: BlockPos): Boolean = contains(pos.x, pos.y, pos.z)

    fun indexOf(x: Int, y: Int, z: Int): Int = (y * length + z) * width + x

    operator fun get(x: Int, y: Int, z: Int): BlockState {
        if (!contains(x, y, z)) return air
        val chunk = chunks[chunkIndex(x, y, z)] ?: return air
        return chunk[localIndex(x, y, z)] ?: air
    }

    operator fun set(x: Int, y: Int, z: Int, state: BlockState) {
        require(contains(x, y, z))
        val chunkIndex = chunkIndex(x, y, z)
        var chunk = chunks[chunkIndex]
        if (chunk == null) {
            if (state.isAir) return
            chunk = Chunk()
            chunks[chunkIndex] = chunk
        }
        val previous = chunk.put(localIndex(x, y, z), state.takeUnless(BlockState::isAir))
        if (previous == state || previous == null && state.isAir) return
        when {
            previous == null -> occupied++
            state.isAir -> occupied--
        }
        if (chunk.isEmpty()) chunks.remove(chunkIndex)
        if (state.type != BlockType.BARREL && state.type != BlockType.OAK_WALL_SIGN) {
            blockEntities.remove(indexOf(x, y, z))
        }
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

    fun blockEntities(): Map<BlockPos, BlockEntity> = blockEntities.mapKeys { (index, _) -> positionOf(index) }

    fun forEachPosition(action: (x: Int, y: Int, z: Int, state: BlockState) -> Unit) {
        for (y in 0 until height) {
            val chunkY = y shr CHUNK_BITS
            val localY = y and CHUNK_MASK
            for (z in 0 until length) {
                val chunkZ = z shr CHUNK_BITS
                val localZ = z and CHUNK_MASK
                for (chunkX in 0 until chunkWidth) {
                    val chunk = chunks[chunkCoordinatesIndex(chunkX, chunkY, chunkZ)]
                    val startX = chunkX shl CHUNK_BITS
                    val endX = minOf(startX + CHUNK_SIZE, width)
                    if (chunk == null) {
                        for (x in startX until endX) action(x, y, z, air)
                    } else {
                        val localBase = (localY * CHUNK_SIZE + localZ) * CHUNK_SIZE
                        for (x in startX until endX) {
                            action(x, y, z, chunk[localBase + (x and CHUNK_MASK)] ?: air)
                        }
                    }
                }
            }
        }
    }

    fun forEachOccupiedPosition(action: (x: Int, y: Int, z: Int, state: BlockState) -> Unit) {
        for (y in 0 until height) {
            val chunkY = y shr CHUNK_BITS
            val localY = y and CHUNK_MASK
            for (z in 0 until length) {
                val chunkZ = z shr CHUNK_BITS
                val localZ = z and CHUNK_MASK
                for (chunkX in 0 until chunkWidth) {
                    val chunk = chunks[chunkCoordinatesIndex(chunkX, chunkY, chunkZ)] ?: continue
                    val startX = chunkX shl CHUNK_BITS
                    val endX = minOf(startX + CHUNK_SIZE, width)
                    val localBase = (localY * CHUNK_SIZE + localZ) * CHUNK_SIZE
                    for (x in startX until endX) {
                        val state = chunk[localBase + (x and CHUNK_MASK)] ?: continue
                        action(x, y, z, state)
                    }
                }
            }
        }
    }

    fun forEachOccupiedPosition(action: (position: BlockPos, state: BlockState) -> Unit) {
        forEachOccupiedPosition { x, y, z, state -> action(BlockPos(x, y, z), state) }
    }

    fun copy(): BlockMatrix = BlockMatrix(width, height, length).also { target ->
        chunks.forEach { (chunkIndex, chunk) ->
            target.chunks[chunkIndex] = chunk.copy()
        }
        target.blockEntities.putAll(blockEntities)
        target.occupied = occupied
    }

    fun blockCount(): Int = occupied

    override fun toString(): String = "BlockMatrix(${width}x${height}x$length)"

    private fun positionOf(index: Int): BlockPos {
        val x = index % width
        val z = (index / width) % length
        val y = index / (width * length)
        return BlockPos(x, y, z)
    }

    private fun chunkIndex(x: Int, y: Int, z: Int): Int =
        chunkCoordinatesIndex(x shr CHUNK_BITS, y shr CHUNK_BITS, z shr CHUNK_BITS)

    private fun chunkCoordinatesIndex(chunkX: Int, chunkY: Int, chunkZ: Int): Int =
        (chunkY * chunkLength + chunkZ) * chunkWidth + chunkX

    private fun localIndex(x: Int, y: Int, z: Int): Int =
        ((y and CHUNK_MASK) * CHUNK_SIZE + (z and CHUNK_MASK)) * CHUNK_SIZE + (x and CHUNK_MASK)

    private class Chunk private constructor(
        private var keys: ShortArray,
        private var states: Array<BlockState?>,
        private var dense: Array<BlockState?>?,
        private var entries: Int,
        private var tombstones: Int,
    ) {
        constructor() : this(
            ShortArray(INITIAL_CAPACITY) { EMPTY },
            arrayOfNulls(INITIAL_CAPACITY),
            null,
            0,
            0,
        )

        operator fun get(index: Int): BlockState? {
            dense?.let { return it[index] }
            val slot = find(index)
            return if (slot >= 0) states[slot] else null
        }

        fun put(index: Int, state: BlockState?): BlockState? {
            dense?.let { storage ->
                val previous = storage[index]
                storage[index] = state
                if (previous == null && state != null) entries++
                if (previous != null && state == null) entries--
                return previous
            }

            val found = find(index)
            if (found >= 0) {
                val previous = states[found]
                if (state == null) {
                    keys[found] = TOMBSTONE
                    states[found] = null
                    entries--
                    tombstones++
                } else {
                    states[found] = state
                }
                return previous
            }
            if (state == null) return null

            ensureInsertCapacity()
            if (entries >= DENSE_THRESHOLD) {
                makeDense()
                return put(index, state)
            }
            insertSparse(index, state)
            return null
        }

        fun isEmpty(): Boolean = entries == 0

        fun copy(): Chunk = Chunk(keys.copyOf(), states.copyOf(), dense?.copyOf(), entries, tombstones)

        private fun find(index: Int): Int {
            val mask = keys.lastIndex
            var slot = hash(index) and mask
            while (true) {
                when (val key = keys[slot]) {
                    EMPTY -> return -1
                    index.toShort() -> return slot
                    else -> if (key == TOMBSTONE) Unit
                }
                slot = (slot + 1) and mask
            }
        }

        private fun insertSparse(index: Int, state: BlockState) {
            val mask = keys.lastIndex
            var slot = hash(index) and mask
            var tombstone = -1
            while (true) {
                when (keys[slot]) {
                    EMPTY -> {
                        val target = if (tombstone >= 0) tombstone else slot
                        if (tombstone >= 0) tombstones--
                        keys[target] = index.toShort()
                        states[target] = state
                        entries++
                        return
                    }

                    TOMBSTONE -> if (tombstone < 0) tombstone = slot
                }
                slot = (slot + 1) and mask
            }
        }

        private fun ensureInsertCapacity() {
            if ((entries + tombstones + 1) * 2 < keys.size) return
            resize(if (entries * 4 < keys.size) keys.size else keys.size * 2)
        }

        private fun resize(capacity: Int) {
            val oldKeys = keys
            val oldStates = states
            keys = ShortArray(capacity) { EMPTY }
            states = arrayOfNulls(capacity)
            entries = 0
            tombstones = 0
            oldKeys.indices.forEach { slot ->
                if (oldKeys[slot] >= 0) insertSparse(oldKeys[slot].toInt(), checkNotNull(oldStates[slot]))
            }
        }

        private fun makeDense() {
            val storage = arrayOfNulls<BlockState>(CHUNK_VOLUME)
            keys.indices.forEach { slot ->
                if (keys[slot] >= 0) storage[keys[slot].toInt()] = states[slot]
            }
            dense = storage
            keys = ShortArray(0)
            states = emptyArray()
            tombstones = 0
        }

        private fun hash(index: Int): Int = index * -1640531527
    }

    private companion object {
        const val CHUNK_BITS = 4
        const val CHUNK_SIZE = 1 shl CHUNK_BITS
        const val CHUNK_MASK = CHUNK_SIZE - 1
        const val CHUNK_VOLUME = CHUNK_SIZE * CHUNK_SIZE * CHUNK_SIZE
        const val INITIAL_CAPACITY = 8
        const val DENSE_THRESHOLD = 1024
        const val EMPTY: Short = -1
        const val TOMBSTONE: Short = -2
    }
}
