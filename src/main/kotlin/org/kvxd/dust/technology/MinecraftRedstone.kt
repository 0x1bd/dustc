package org.kvxd.dust.technology

import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockState
import org.kvxd.dust.device.Direction
import org.kvxd.dust.cell.BuiltinCells
import org.kvxd.dust.cell.CellTypeId
import org.kvxd.dust.netlist.Primitive

object MinecraftRedstone {
    private val cellSupport = RedstoneBlocks.cellSupport
    private val dust = RedstoneBlocks.dust
    private val eastTorch = RedstoneBlocks.wallTorch(Direction.EAST)
    private val northTorch = RedstoneBlocks.wallTorch(Direction.NORTH)
    private val southTorch = RedstoneBlocks.wallTorch(Direction.SOUTH)
    private val eastRepeater = RedstoneBlocks.repeater(Direction.EAST)
    private val northRepeater = RedstoneBlocks.repeater(Direction.NORTH)
    private val floorLever = RedstoneBlocks.floorLever

    val technology: RedstoneTechnology = technology()

    fun technology(isolation: Int = 1, planeSeparation: Int = 2): RedstoneTechnology {
        val not = notCell()
        val and = andCell()
        val or = orCell()
        val xor = xorCell()
        val mux = muxCell(not, and, or)
        return RedstoneTechnology(
        primitives = mapOf(
            Primitive.NOT to not,
            Primitive.AND2 to and,
            Primitive.OR2 to or,
            Primitive.XOR2 to xor,
            Primitive.MUX2 to mux,
            Primitive.LATCH to latchCell(),
        ),
        debugInputPad = inputPad(),
        debugOutputPad = outputPad(),
        inputTerminal = inputTerminal(),
        outputTerminal = outputTerminal(),
        ioSign = RedstoneBlocks.ioSign,
        wire = dust,
        routeSupport = RedstoneBlocks.glassSupport,
        viaSupport = RedstoneBlocks.opaqueSupport,
        isolation = isolation,
        lowerPlaneY = 1,
        viaSignalOffsets = List(planeSeparation + 1) { step -> BlockPos(0, step, -step) },
    )
    }

    private fun notCell(): StandardCell = cell(
        name = "not",
        size = CellSize(3, 2, 2),
        latencyTicks = 1,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 1), allowsHorizontalAbutment = false, requiredStrength = 1),
            CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 1), driveStrength = 13),
        ),
        palette = mapOf('#' to cellSupport, '+' to dust, '>' to eastTorch),
        layers = mapOf(
            0 to listOf("..#", "#.#"),
            1 to listOf("#>+", "+.+"),
        ),
    )

    private fun andCell(): StandardCell = cell(
        name = "and2",
        size = CellSize(5, 2, 3),
        latencyTicks = 2,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 2), allowsHorizontalAbutment = false, requiredStrength = 1),
            CellPin("b", PinDirection.INPUT, BlockPos(2, 1, 2), allowsHorizontalAbutment = false, requiredStrength = 1),
            CellPin("y", PinDirection.OUTPUT, BlockPos(4, 1, 2), driveStrength = 15),
        ),
        palette = mapOf('#' to cellSupport, '+' to dust, '^' to northTorch, 'V' to southTorch),
        layers = mapOf(
            0 to listOf("####.", ".....", "....#"),
            1 to listOf("++++#", "^.^.V", "#.#.+"),
        ),
    )

    private fun orCell(): StandardCell = cell(
        name = "or2",
        size = CellSize(5, 2, 3),
        latencyTicks = 1,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 2), requiredStrength = 1),
            CellPin("b", PinDirection.INPUT, BlockPos(2, 1, 2), requiredStrength = 1),
            CellPin("y", PinDirection.OUTPUT, BlockPos(4, 1, 2), driveStrength = 9),
        ),
        palette = mapOf('#' to cellSupport, '+' to dust, 'N' to northRepeater),
        layers = mapOf(
            0 to listOf("#####", "#.#.#", "#.#.#"),
            1 to listOf("+++++", "N.N.+", "+.+.+"),
        ),
    )

    private fun xorCell(): StandardCell = cell(
        name = "xor2",
        size = CellSize(13, 2, 7),
        latencyTicks = 4,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 6), requiredStrength = 7),
            CellPin("b", PinDirection.INPUT, BlockPos(2, 1, 6), requiredStrength = 5),
            CellPin("y", PinDirection.OUTPUT, BlockPos(12, 1, 6), driveStrength = 15),
        ),
        palette = mapOf(
            '#' to cellSupport,
            '+' to dust,
            '>' to eastTorch,
            'E' to eastRepeater,
            'V' to southTorch,
        ),
        layers = mapOf(
            0 to listOf(
                "########..#..",
                "#.....#.#.#..",
                "#.####..#...#",
                "#.#.#...#.#.#",
                "#.#.####..#..",
                "#.#..........",
                "#.#.........#",
            ),
            1 to listOf(
                "+E++++++#>+..",
                "+.....+.+.+..",
                "+.+E++#>+.#>+",
                "+.+.+...+.+.+",
                "+.+.++++#>+.#",
                "+.+.........V",
                "+.+.........+",
            ),
        ),
    )

    private fun muxCell(not: StandardCell, and: StandardCell, or: StandardCell): StandardCell {
        val blocks = LinkedHashMap<BlockPos, BlockState>()

        fun put(pos: BlockPos, state: BlockState) {
            val previous = blocks.putIfAbsent(pos, state)
            require(previous == null || previous == state) { "mux2 internal overlap at $pos: $previous vs $state" }
        }

        fun place(subcell: StandardCell, origin: BlockPos) {
            subcell.blocks.forEach { (local, state) -> put(origin + local, state) }
        }

        fun signal(pos: BlockPos, state: BlockState = dust) {
            val support = pos.offset(Direction.DOWN)
            val previousSupport = blocks[support]
            when {
                previousSupport == null -> put(support, cellSupport)
                previousSupport.type.isSolid -> Unit
                else -> error("mux2 route at $pos lacks solid support; found $previousSupport at $support")
            }
            put(pos, state)
        }

        place(not, BlockPos(0, 0, 1))
        place(and, BlockPos(5, 0, 0))
        place(or, BlockPos(13, 0, 0))
        place(and, BlockPos(20, 0, 0))
        listOf(BlockPos(3, 1, 2), BlockPos(4, 1, 2)).forEach(::signal)
        listOf(BlockPos(10, 1, 2), BlockPos(11, 1, 2), BlockPos(12, 1, 2)).forEach(::signal)

        listOf(0, 7, 22).forEach { x ->
            for (z in 3..6) {
                signal(BlockPos(x, 1, z), if (z == 5) northRepeater else dust)
            }
        }
        for (z in 3..6) {
            signal(BlockPos(17, 1, z), if (z == 5) RedstoneBlocks.repeater(Direction.SOUTH) else dust)
        }

        listOf(
            BlockPos(1, 2, 4),
            BlockPos(2, 3, 4),
            BlockPos(3, 4, 4),
            BlockPos(3, 4, 5),
            BlockPos(3, 4, 6),
        ).forEach(::signal)
        for (x in 4..20) {
            signal(BlockPos(x, 4, 6), if (x == 12) eastRepeater else dust)
        }
        listOf(
            BlockPos(20, 3, 5),
            BlockPos(20, 2, 4),
            BlockPos(20, 1, 3),
        ).forEach(::signal)

        signal(BlockPos(25, 1, 2))
        signal(BlockPos(25, 1, 3), RedstoneBlocks.repeater(Direction.SOUTH))
        signal(BlockPos(25, 1, 4))
        listOf(
            BlockPos(24, 2, 4),
            BlockPos(23, 3, 4),
            BlockPos(22, 4, 4),
            BlockPos(21, 5, 4),
            BlockPos(20, 6, 4),
            BlockPos(19, 7, 4),
            BlockPos(18, 6, 4),
            BlockPos(17, 5, 4),
            BlockPos(16, 4, 4),
            BlockPos(15, 3, 4),
            BlockPos(15, 2, 3),
        ).forEach(::signal)

        return StandardCell(
            "mux2",
            BuiltinCells.mux2,
            CellSize(26, 8, 7),
            listOf(
                CellPin("select", PinDirection.INPUT, BlockPos(0, 1, 6)),
                CellPin("low", PinDirection.INPUT, BlockPos(7, 1, 6)),
                CellPin("high", PinDirection.INPUT, BlockPos(22, 1, 6)),
                CellPin("y", PinDirection.OUTPUT, BlockPos(17, 1, 6), driveStrength = 15),
            ),
            blocks.entries.map { it.key to it.value },
        )
    }

    private fun latchCell(): StandardCell = cell(
        name = "latch",
        size = CellSize(5, 2, 3),
        latencyTicks = 1,
        pins = listOf(
            CellPin("d", PinDirection.INPUT, BlockPos(0, 1, 2), requiredStrength = 4),
            CellPin("hold", PinDirection.INPUT, BlockPos(2, 1, 2), requiredStrength = 1),
            CellPin("q", PinDirection.OUTPUT, BlockPos(4, 1, 2), driveStrength = 12),
        ),
        palette = mapOf(
            '#' to cellSupport,
            '+' to dust,
            'E' to eastRepeater,
            'N' to northRepeater,
        ),
        layers = mapOf(
            0 to listOf("#####", "#.#.#", "#.#.#"),
            1 to listOf("++E++", "+.N.+", "+.+.+"),
        ),
    )

    private fun inputPad(): StandardCell = cell(
        name = "input-pad",
        size = CellSize(1, 2, 2),
        latencyTicks = 0,
        pins = listOf(CellPin("y", PinDirection.OUTPUT, BlockPos(0, 1, 1))),
        palette = mapOf('#' to cellSupport, '+' to dust, 'L' to floorLever),
        layers = mapOf(0 to listOf("#", "#"), 1 to listOf("L", "+")),
    )

    private fun outputPad(): StandardCell = cell(
        name = "output-pad",
        size = CellSize(2, 4, 2),
        latencyTicks = 0,
        pins = listOf(
            CellPin(
                "a",
                PinDirection.INPUT,
                BlockPos(0, 3, 1),
                allowsHorizontalAbutment = false,
                accessesFromSouth = true,
                branchOffsetX = 1,
            ),
        ),
        palette = mapOf('#' to cellSupport, 'N' to northRepeater, 'O' to RedstoneBlocks.lamp),
        layers = mapOf(
            2 to listOf("..", "#."),
            3 to listOf("O.", "N."),
        ),
    )

    private fun inputTerminal(): StandardCell = cell(
        name = "input-terminal",
        size = CellSize(1, 2, 1),
        latencyTicks = 0,
        pins = listOf(CellPin("y", PinDirection.OUTPUT, BlockPos(0, 1, 0))),
        palette = mapOf('#' to cellSupport, '+' to dust),
        layers = mapOf(0 to listOf("#"), 1 to listOf("+")),
    )

    private fun outputTerminal(): StandardCell = cell(
        name = "output-terminal",
        size = CellSize(2, 4, 1),
        latencyTicks = 0,
        pins = listOf(
            CellPin(
                "a",
                PinDirection.INPUT,
                BlockPos(0, 3, 0),
                allowsHorizontalAbutment = false,
                accessesFromSouth = true,
                branchOffsetX = 1,
            ),
        ),
        palette = mapOf('g' to RedstoneBlocks.glassSupport, '+' to dust),
        layers = mapOf(2 to listOf("g."), 3 to listOf("+.")),
    )

    private fun cell(
        name: String,
        size: CellSize,
        latencyTicks: Int,
        pins: List<CellPin>,
        palette: Map<Char, BlockState>,
        layers: Map<Int, List<String>>,
    ): StandardCell {
        pins.forEach { pin ->
            require(pin.position.x in 0 until size.x)
            require(pin.position.y in 0 until size.y)
            require(pin.position.z in 0 until size.z)
            require(pin.position.z == size.z - 1) { "$name pin ${pin.name} is not on the routing edge" }
        }
        val blocks = buildList {
            layers.toSortedMap().forEach { (y, rows) ->
                require(y in 0 until size.y && rows.size == size.z)
                rows.forEachIndexed { z, row ->
                    require(row.length == size.x)
                    row.forEachIndexed { x, symbol ->
                        if (symbol != '.') add(BlockPos(x, y, z) to checkNotNull(palette[symbol]))
                    }
                }
            }
        }
        val logicalType = BuiltinCells.byId.getValue(CellTypeId(name))
        require(logicalType.timing.arcs.all { arc ->
            maxOf(arc.rise.maxTicks, arc.fall.maxTicks) == latencyTicks
        }) { "$name physical latency does not match its logical timing arcs" }
        return StandardCell(name, logicalType, size, pins, blocks)
    }
}

internal fun BlockMatrix.placeChecked(pos: BlockPos, state: BlockState) {
    require(contains(pos)) { "$pos is outside $this" }
    val previous = blockAt(pos)
    require(previous.isAir || previous == state) { "$pos contains $previous, cannot place $state" }
    setBlockAt(pos, state)
}
