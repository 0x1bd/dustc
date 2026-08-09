package org.kvxd.dust.device

object Redstone {
    val maximumSignalStrength: Int get() = Properties.POWER.max

    fun isDiode(state: BlockState): Boolean = state.type == BlockType.REPEATER

    fun weakPower(access: BlockAccess, pos: BlockPos, side: Direction, dustPower: Boolean = true): Int {
        val state = access.blockAt(pos)
        return when (state.type) {
            BlockType.REDSTONE_WALL_TORCH ->
                if (state[Properties.LIT] && state[Properties.FACING] != side) maximumSignalStrength else 0
            BlockType.LEVER -> if (state[Properties.POWERED]) maximumSignalStrength else 0
            BlockType.REPEATER ->
                if (state[Properties.FACING] == side && state[Properties.POWERED]) maximumSignalStrength else 0
            BlockType.COMPARATOR ->
                if (state[Properties.FACING] == side) {
                    access.analogOutputAt(pos) ?: comparatorOutput(access, pos)
                } else {
                    0
                }
            BlockType.REDSTONE_WIRE -> if (dustPower) wireEmission(access, pos, state, side) else 0
            else -> 0
        }
    }

    fun strongPower(access: BlockAccess, pos: BlockPos, side: Direction, dustPower: Boolean = true): Int {
        val state = access.blockAt(pos)
        return when (state.type) {
            BlockType.REDSTONE_WALL_TORCH ->
                if (state[Properties.LIT] && side == Direction.DOWN) maximumSignalStrength else 0
            BlockType.LEVER -> {
                val attached = when (side) {
                    Direction.UP -> state[Properties.FACE] == AttachFace.FLOOR
                    Direction.DOWN -> state[Properties.FACE] == AttachFace.CEILING
                    else -> state[Properties.FACE] == AttachFace.WALL && state[Properties.FACING] == side
                }
                if (attached && state[Properties.POWERED]) maximumSignalStrength else 0
            }
            BlockType.REPEATER, BlockType.COMPARATOR, BlockType.REDSTONE_WIRE ->
                weakPower(access, pos, side, dustPower)
            else -> 0
        }
    }

    fun redstonePower(access: BlockAccess, pos: BlockPos, side: Direction, dustPower: Boolean = true): Int {
        val type = access.blockAt(pos).type
        return if (type.isSolid && !type.isTransparent) {
            Direction.ALL.maxOf { neighbour ->
                strongPower(access, pos.offset(neighbour), neighbour, dustPower)
            }
        } else {
            weakPower(access, pos, side, dustPower)
        }
    }

    fun wirePower(access: BlockAccess, pos: BlockPos): Int {
        var direct = 0
        var wire = 0
        val above = access.blockAt(pos.offset(Direction.UP))
        for (side in Direction.ALL) {
            val neighbourPos = pos.offset(side)
            val neighbour = access.blockAt(neighbourPos)
            direct = maxOf(direct, redstonePower(access, neighbourPos, side, dustPower = false))
            wire = maxOf(wire, wireStrength(access, neighbourPos))
            if (side.isHorizontal) {
                if (!above.type.isSolid && !neighbour.type.isTransparent) {
                    wire = maxOf(wire, wireStrength(access, neighbourPos.offset(Direction.UP)))
                }
                if (!neighbour.type.isSolid || neighbour.type.isTransparent) {
                    wire = maxOf(wire, wireStrength(access, neighbourPos.offset(Direction.DOWN)))
                }
            }
        }
        return maxOf(direct, (wire - 1).coerceAtLeast(0))
    }

    fun rawWireSide(access: BlockAccess, pos: BlockPos, side: Direction): WireConnection {
        val neighbourPos = pos.offset(side)
        val neighbour = access.blockAt(neighbourPos)
        if (canConnectTo(neighbour, side)) return WireConnection.SIDE
        val above = access.blockAt(pos.offset(Direction.UP))
        if (!above.type.isSolid && access.blockAt(neighbourPos.offset(Direction.UP)).type == BlockType.REDSTONE_WIRE) {
            return WireConnection.UP
        }
        if (!neighbour.type.isSolid && access.blockAt(neighbourPos.offset(Direction.DOWN)).type == BlockType.REDSTONE_WIRE) {
            return WireConnection.SIDE
        }
        return WireConnection.NONE
    }

    fun regulatedWireSides(access: BlockAccess, pos: BlockPos, stored: WireSides): WireSides {
        val computed = WireSides(
            rawWireSide(access, pos, Direction.NORTH),
            rawWireSide(access, pos, Direction.SOUTH),
            rawWireSide(access, pos, Direction.EAST),
            rawWireSide(access, pos, Direction.WEST),
        )
        if (stored.isDot && computed.isDot) return computed
        val nsEmpty = computed.north == WireConnection.NONE && computed.south == WireConnection.NONE
        val ewEmpty = computed.east == WireConnection.NONE && computed.west == WireConnection.NONE
        return WireSides(
            if (computed.north == WireConnection.NONE && ewEmpty) WireConnection.SIDE else computed.north,
            if (computed.south == WireConnection.NONE && ewEmpty) WireConnection.SIDE else computed.south,
            if (computed.east == WireConnection.NONE && nsEmpty) WireConnection.SIDE else computed.east,
            if (computed.west == WireConnection.NONE && nsEmpty) WireConnection.SIDE else computed.west,
        )
    }

    fun canConnectTo(state: BlockState, side: Direction): Boolean = when (state.type) {
        BlockType.REDSTONE_WIRE, BlockType.REDSTONE_WALL_TORCH, BlockType.LEVER -> true
        BlockType.REPEATER, BlockType.COMPARATOR ->
            state[Properties.FACING] == side || state[Properties.FACING] == side.opposite
        else -> false
    }

    fun anyTorchShouldBeOff(access: BlockAccess, pos: BlockPos): Boolean {
        val state = access.blockAt(pos)
        if (state.type != BlockType.REDSTONE_WALL_TORCH) return false
        val facing = state[Properties.FACING]
        val supportPos = pos.offset(facing.opposite)
        if (!access.blockAt(supportPos).type.isSolid) return false
        return redstonePower(access, supportPos, facing.opposite) > 0
    }

    fun lampShouldBeLit(access: BlockAccess, pos: BlockPos): Boolean =
        Direction.ALL.any { side -> redstonePower(access, pos.offset(side), side) > 0 }

    fun diodeInputStrength(access: BlockAccess, pos: BlockPos, facing: Direction): Int {
        val inputPos = pos.offset(facing)
        val power = redstonePower(access, inputPos, facing)
        if (power > 0) return power
        val input = access.blockAt(inputPos)
        return if (input.type == BlockType.REDSTONE_WIRE) input[Properties.POWER] else 0
    }

    fun comparatorOutput(access: BlockAccess, pos: BlockPos): Int {
        val state = access.blockAt(pos)
        if (state.type != BlockType.COMPARATOR) return 0
        val facing = state[Properties.FACING]
        val inputPos = pos.offset(facing)
        val container = access.blockEntityAt(inputPos) as? ContainerBlockEntity
        val rear = container?.comparatorOutput ?: redstonePower(access, inputPos, facing)
        val side = maxOf(
            comparatorSidePower(access, pos.offset(facing.rotateClockwise()), facing.rotateClockwise()),
            comparatorSidePower(access, pos.offset(facing.rotateCounterClockwise()), facing.rotateCounterClockwise()),
        )
        return when (state[Properties.MODE]) {
            ComparatorMode.COMPARE -> if (rear >= side) rear else 0
            ComparatorMode.SUBTRACT -> (rear - side).coerceAtLeast(0)
        }
    }

    private fun comparatorSidePower(access: BlockAccess, pos: BlockPos, side: Direction): Int =
        redstonePower(access, pos, side)

    fun repeaterShouldBeLocked(access: BlockAccess, pos: BlockPos, facing: Direction): Boolean =
        repeaterLockPower(access, pos, facing.rotateClockwise()) > 0 ||
            repeaterLockPower(access, pos, facing.rotateCounterClockwise()) > 0

    private fun repeaterLockPower(access: BlockAccess, pos: BlockPos, side: Direction): Int {
        val sidePos = pos.offset(side)
        return if (access.blockAt(sidePos).type == BlockType.REPEATER) {
            weakPower(access, sidePos, side, dustPower = false)
        } else {
            0
        }
    }

    private fun wireStrength(access: BlockAccess, pos: BlockPos): Int {
        val state = access.blockAt(pos)
        return if (state.type == BlockType.REDSTONE_WIRE) state[Properties.POWER] else 0
    }

    private fun wireEmission(access: BlockAccess, pos: BlockPos, state: BlockState, side: Direction): Int {
        val power = state[Properties.POWER]
        if (power == 0) return 0
        return when (side) {
            Direction.UP -> power
            Direction.DOWN -> 0
            else -> if (regulatedWireSides(access, pos, WireSides.of(state))[side.opposite] == WireConnection.NONE) 0 else power
        }
    }
}
