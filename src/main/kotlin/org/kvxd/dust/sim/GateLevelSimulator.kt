package org.kvxd.dust.sim

import org.kvxd.dust.device.AttachFace
import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockState
import org.kvxd.dust.device.BlockType
import org.kvxd.dust.device.ComponentKind
import org.kvxd.dust.device.Direction
import org.kvxd.dust.device.Properties
import org.kvxd.dust.device.Redstone
import org.kvxd.dust.device.Scheduling
import org.kvxd.dust.device.TickPriority

class GateLevelSimulator(private val matrix: BlockMatrix) {

    private val width = matrix.width
    private val height = matrix.height
    private val length = matrix.length

    private val states = Array(matrix.volume) { index ->
        matrix[index % width, index / (width * length), (index / width) % length]
    }

    private val pending = ArrayList<PendingTick>()
    private val analogOutputs = IntArray(matrix.volume)
    private var sequence = 0L

    private var currentTick = 0

    private val pendingCounts = HashMap<Int, Int>()

    private val world = object : org.kvxd.dust.device.BlockAccess {
        override fun blockAt(pos: BlockPos): BlockState = stateAt(pos)
        override fun blockEntityAt(pos: BlockPos) = matrix.blockEntityAt(pos)
        override fun analogOutputAt(pos: BlockPos): Int? = toLocal(pos)?.let { analogOutputs[it] }
    }

    fun stateAt(pos: BlockPos): BlockState {
        val local = toLocal(pos) ?: return BlockState.AIR
        return stateAt(local)
    }

    fun settle(maxTicks: Int) {
        forEachActivePosition { pos -> update(pos) }
        var remaining = maxTicks
        while (pending.isNotEmpty() && remaining-- > 0) tick()
    }

    fun advance(ticks: Int) {
        repeat(ticks) { tick() }
    }

    fun advanceUntilIdle(maxTicks: Int): Int {
        require(maxTicks >= 0)
        var elapsed = 0
        while (pending.isNotEmpty() && elapsed < maxTicks) {
            tick()
            elapsed++
        }
        check(pending.isEmpty()) { "circuit did not settle within $maxTicks ticks" }
        return elapsed
    }

    fun unsettled(): List<String> {
        val found = mutableListOf<String>()
        forEachActivePosition { pos ->
            val state = stateAt(pos)
            if (hasPendingTick(pos)) {
                val index = toLocal(pos)
                val due = pending.filter { it.index == index }.map { it.dueAt - currentTick }
                found += "$pos ${state.type.id} still has ${pendingCounts[index!!]} tick(s) scheduled, due in $due"
                return@forEachActivePosition
            }
            when (state.type.component) {
                ComponentKind.WIRE -> {
                    val power = Redstone.wirePower(world, pos)
                    if (power != state[Properties.POWER]) {
                        found += "$pos dust holds ${state[Properties.POWER]} but its neighbours give $power"
                    }
                }

                ComponentKind.TORCH ->
                    if (state[Properties.LIT] == Redstone.anyTorchShouldBeOff(world, pos)) {
                        found += "$pos torch is ${if (state[Properties.LIT]) "lit" else "out"} and should not be"
                    }

                ComponentKind.REPEATER -> {
                    val facing = state[Properties.FACING]
                    val powered = Redstone.diodeInputStrength(world, pos, facing) > 0
                    if (!Redstone.repeaterShouldBeLocked(world, pos, facing) && powered != state[Properties.POWERED]) {
                        found += "$pos repeater is ${if (state[Properties.POWERED]) "on" else "off"} " +
                            "but its input says ${if (powered) "on" else "off"}"
                    }
                }

                ComponentKind.COMPARATOR -> {
                    val output = Redstone.comparatorOutput(world, pos)
                    if (output != signalAt(pos)) {
                        found += "$pos comparator outputs ${signalAt(pos)} but its inputs give $output"
                    }
                }

                ComponentKind.LAMP ->
                    if (!state[Properties.LIT] && Redstone.lampShouldBeLit(world, pos)) {
                        found += "$pos lamp is dark but powered"
                    }

                else -> Unit
            }
        }
        return found
    }

    fun tick() {
        if (pending.isEmpty()) return

        pending.sortWith(
            compareBy({ it.dueAt }, { it.priority.ordinal }, { it.sequence }),
        )
        currentTick++

        var drained = 0
        while (drained < pending.size && pending[drained].dueAt <= currentTick) {
            val entry = pending[drained]
            val left = (pendingCounts[entry.index] ?: 1) - 1
            if (left == 0) pendingCounts.remove(entry.index) else pendingCounts[entry.index] = left
            drained++
            commit(fromLocal(entry.index))
        }
        if (drained > 0) pending.subList(0, drained).clear()
    }

    fun setInput(pos: BlockPos, on: Boolean) {
        val state = stateAt(pos)
        when (state.type) {
            BlockType.LEVER -> {
                if (state[Properties.POWERED] == on) return
                setBlockRaw(pos, state.with(Properties.POWERED, on))
                updateAround(pos)
                attachmentOf(pos, state)?.let { updateAround(it) }
            }

            else -> throw IllegalArgumentException("no input pad at $pos, found ${state.type.id}")
        }
    }

    fun readOutput(pos: BlockPos): Boolean {
        val state = stateAt(pos)
        return if (state.type == BlockType.REDSTONE_LAMP) {
            state[Properties.LIT]
        } else {
            throw IllegalArgumentException("no output pad at $pos, found ${state.type.id}")
        }
    }

    fun levelAt(pos: BlockPos): Boolean {
        val state = stateAt(pos)
        return when (state.type.component) {
            ComponentKind.WIRE -> state[Properties.POWER] > 0
            ComponentKind.TORCH, ComponentKind.LAMP -> state[Properties.LIT]
            ComponentKind.REPEATER, ComponentKind.COMPARATOR -> signalAt(pos) > 0
            ComponentKind.SUBSTRATE -> if (state.type.isSolid && !state.type.isTransparent) {
                Redstone.redstonePower(world, pos, Direction.UP) > 0
            } else {
                throw IllegalArgumentException("${state.type.id} at $pos has no Boolean level")
            }
            else -> state.getOrNull(Properties.POWERED)
                ?: throw IllegalArgumentException("${state.type.id} at $pos has no Boolean level")
        }
    }

    fun signalAt(pos: BlockPos): Int {
        val state = stateAt(pos)
        return when (state.type.component) {
            ComponentKind.WIRE -> state[Properties.POWER]
            ComponentKind.TORCH -> if (state[Properties.LIT]) Redstone.maximumSignalStrength else 0
            ComponentKind.REPEATER -> if (state[Properties.POWERED]) Redstone.maximumSignalStrength else 0
            ComponentKind.COMPARATOR -> toLocal(pos)?.let { analogOutputs[it] } ?: 0
            ComponentKind.LEVER -> if (state[Properties.POWERED]) Redstone.maximumSignalStrength else 0
            else -> 0
        }
    }

    private fun update(pos: BlockPos) {
        val state = stateAt(pos)
        when (state.type.component) {
            ComponentKind.WIRE -> updateWire(pos, state)
            ComponentKind.TORCH -> updateTorch(pos, state)
            ComponentKind.REPEATER -> updateRepeater(pos, state)
            ComponentKind.COMPARATOR -> updateComparator(pos)
            ComponentKind.LAMP -> updateLamp(pos, state)
            else -> Unit
        }
    }

    private fun updateWire(pos: BlockPos, state: BlockState) {
        val power = Redstone.wirePower(world, pos)
        if (power == state[Properties.POWER]) return
        setBlockRaw(pos, state.with(Properties.POWER, power))
        updateAround(pos)
    }

    private fun updateTorch(pos: BlockPos, state: BlockState) {
        val shouldBeOff = Redstone.anyTorchShouldBeOff(world, pos)
        if (state[Properties.LIT] == shouldBeOff && !hasPendingTick(pos)) {
            schedule(pos, Scheduling.TORCH.ticks, Scheduling.TORCH.priority)
        }
    }

    private fun updateRepeater(pos: BlockPos, state: BlockState) {
        val facing = state[Properties.FACING]
        val locked = Redstone.repeaterShouldBeLocked(world, pos, facing)
        var current = state
        if (locked != state[Properties.LOCKED]) {
            current = state.with(Properties.LOCKED, locked)
            setBlockRaw(pos, current)
        }
        if (locked || hasPendingTick(pos)) return

        val shouldBePowered = Redstone.diodeInputStrength(world, pos, facing) > 0
        if (shouldBePowered == current[Properties.POWERED]) return
        val frontIsDiode = Redstone.isDiode(stateAt(pos.offset(facing.opposite)))
        val delay = Scheduling.repeater(current[Properties.DELAY], frontIsDiode, shouldBePowered)
        schedule(pos, delay.ticks, delay.priority)
    }

    private fun updateComparator(pos: BlockPos) {
        val expected = Redstone.comparatorOutput(world, pos)
        if (expected != signalAt(pos) && !hasPendingTick(pos)) {
            schedule(pos, Scheduling.COMPARATOR.ticks, Scheduling.COMPARATOR.priority)
        }
    }

    private fun updateLamp(pos: BlockPos, state: BlockState) {
        val shouldBeLit = Redstone.lampShouldBeLit(world, pos)
        val lit = state[Properties.LIT]
        when {
            !lit && shouldBeLit -> {
                setBlockRaw(pos, state.with(Properties.LIT, true))
                updateAround(pos)
            }

            lit && !shouldBeLit && !hasPendingTick(pos) ->
                schedule(pos, Scheduling.LAMP_OFF.ticks, Scheduling.LAMP_OFF.priority)

            else -> Unit
        }
    }

    private fun commit(pos: BlockPos) {
        val state = stateAt(pos)
        when (state.type.component) {
            ComponentKind.TORCH -> commitTorch(pos, state)
            ComponentKind.REPEATER -> commitRepeater(pos, state)
            ComponentKind.COMPARATOR -> commitComparator(pos, state)
            ComponentKind.LAMP -> commitLamp(pos, state)
            else -> Unit
        }
    }

    private fun commitTorch(pos: BlockPos, state: BlockState) {
        val shouldBeOff = Redstone.anyTorchShouldBeOff(world, pos)
        if (state[Properties.LIT] != shouldBeOff) return
        setBlockRaw(pos, state.with(Properties.LIT, !shouldBeOff))
        updateAround(pos)
    }

    private fun commitRepeater(pos: BlockPos, state: BlockState) {
        if (state[Properties.LOCKED]) return
        val facing = state[Properties.FACING]
        val shouldBePowered = Redstone.diodeInputStrength(world, pos, facing) > 0
        val powered = state[Properties.POWERED]

        if (powered && !shouldBePowered) {
            setBlockRaw(pos, state.with(Properties.POWERED, false))
            updateAround(pos)
            return
        }
        if (!powered) {
            setBlockRaw(pos, state.with(Properties.POWERED, true))
            updateAround(pos)
            if (!shouldBePowered) {
                val recheck = Scheduling.repeaterRecheck(state[Properties.DELAY])
                schedule(pos, recheck.ticks, recheck.priority)
            }
        }
    }

    private fun commitComparator(pos: BlockPos, state: BlockState) {
        val output = Redstone.comparatorOutput(world, pos)
        val index = toLocal(pos) ?: return
        if (analogOutputs[index] == output) return
        analogOutputs[index] = output
        setBlockRaw(pos, state.with(Properties.POWERED, output > 0))
        updateAround(pos)
    }

    private fun commitLamp(pos: BlockPos, state: BlockState) {
        if (Redstone.lampShouldBeLit(world, pos)) return
        setBlockRaw(pos, state.with(Properties.LIT, false))
        updateAround(pos)
    }

    private fun schedule(pos: BlockPos, ticks: Int, priority: TickPriority) {
        val index = toLocal(pos) ?: return
        if ((pendingCounts[index] ?: 0) > 0) return
        pending.add(PendingTick(currentTick + ticks, priority, index, sequence++))
        pendingCounts[index] = (pendingCounts[index] ?: 0) + 1
    }

    private fun hasPendingTick(pos: BlockPos): Boolean {
        val index = toLocal(pos) ?: return false
        return (pendingCounts[index] ?: 0) > 0
    }

    private fun setBlockRaw(pos: BlockPos, state: BlockState) {
        toLocal(pos)?.let { setState(it, state) }
    }

    private fun stateAt(index: Int): BlockState = states[index]

    private fun setState(index: Int, state: BlockState) {
        states[index] = state
    }

    private fun updateAround(pos: BlockPos) {
        for (side in Direction.ALL) {
            val neighbour = pos.offset(side)
            update(neighbour)
            for (second in Direction.ALL) {
                if (second == side.opposite) continue
                update(neighbour.offset(second))
            }
        }
    }

    private fun attachmentOf(pos: BlockPos, state: BlockState): BlockPos? = when (state.getOrNull(Properties.FACE)) {
        AttachFace.FLOOR -> pos.offset(Direction.DOWN)
        AttachFace.CEILING -> pos.offset(Direction.UP)
        AttachFace.WALL -> pos.offset(state[Properties.FACING].opposite)
        null -> null
    }

    private fun toLocal(pos: BlockPos): Int? {
        val x = pos.x
        val y = pos.y
        val z = pos.z
        if (x !in 0 until width || y !in 0 until height || z !in 0 until length) return null
        return (y * length + z) * width + x
    }

    private fun fromLocal(index: Int): BlockPos {
        val x = index % width
        val z = (index / width) % length
        val y = index / (width * length)
        return BlockPos(x, y, z)
    }

    private fun forEachActivePosition(action: (BlockPos) -> Unit) {
        for (y in 0 until height) {
            for (z in 0 until length) {
                for (x in 0 until width) action(BlockPos(x, y, z))
            }
        }
    }
}
