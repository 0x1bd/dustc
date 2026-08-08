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

    fun technology(isolation: Int = 1, planeSeparation: Int = 2): RedstoneTechnology = RedstoneTechnology(
        primitives = mapOf(
            Primitive.NOT to notCell(),
            Primitive.AND2 to andCell(),
            Primitive.OR2 to orCell(),
            Primitive.XOR2 to xorCell(),
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

    private fun notCell(): StandardCell = cell(
        name = "not",
        size = CellSize(3, 2, 4),
        latencyTicks = 1,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 3), requiredStrength = 2),
            CellPin("y", PinDirection.OUTPUT, BlockPos(2, 1, 3), driveStrength = 11),
        ),
        palette = mapOf('#' to cellSupport, '+' to dust, '^' to northTorch),
        layers = mapOf(
            0 to listOf(".##", "#.#", "#.#", "#.#"),
            1 to listOf("^++", "#.+", "+.+", "+.+"),
        ),
    )

    private fun andCell(): StandardCell = cell(
        name = "and2",
        size = CellSize(6, 2, 5),
        latencyTicks = 2,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 4), requiredStrength = 2),
            CellPin("b", PinDirection.INPUT, BlockPos(2, 1, 4), requiredStrength = 2),
            CellPin("y", PinDirection.OUTPUT, BlockPos(5, 1, 4), driveStrength = 12),
        ),
        palette = mapOf('#' to cellSupport, '+' to dust, '^' to northTorch, '>' to eastTorch),
        layers = mapOf(
            0 to listOf("#####.", ".....#", "#.#..#", "#.#..#", "#.#..#"),
            1 to listOf("++++#>", "^.^..+", "#.#..+", "+.+..+", "+.+..+"),
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
        size = CellSize(13, 2, 9),
        latencyTicks = 4,
        pins = listOf(
            CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 8), requiredStrength = 9),
            CellPin("b", PinDirection.INPUT, BlockPos(2, 1, 8), requiredStrength = 7),
            CellPin("y", PinDirection.OUTPUT, BlockPos(12, 1, 8), driveStrength = 15),
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
                "########..#.#",
                "#.....#.#.#..",
                "#.####..#...#",
                "#.#.#...#.#.#",
                "#.#.####..#.#",
                "#.#.........#",
                "#.#.........#",
                "#.#..........",
                "#.#.........#",
            ),
            1 to listOf(
                "+E++++++#>+.+",
                "+.....+.+.+..",
                "+.+E++#>+.#>+",
                "+.+.+...+.+.+",
                "+.+.++++#>+.+",
                "+.+.........+",
                "+.+.........#",
                "+.+.........V",
                "+.+.........+",
            ),
        ),
    )

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
