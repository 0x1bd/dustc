package org.kvxd.dust.technology

import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.CellProvider
import org.kvxd.dust.cell.library.ClockCell
import org.kvxd.dust.cell.library.DisplayMatrixCell
import org.kvxd.dust.cell.library.DisplayPixelCell
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.technology.definition.CellDefinitionLoader

object MinecraftRedstone {
    private val logicalTypes = listOf(
        BuiltinCells.not,
        BuiltinCells.and2,
        BuiltinCells.or2,
        BuiltinCells.xor2,
        BuiltinCells.mux2,
        BuiltinCells.latch,
        BuiltinCells.dff,
        BuiltinCells.constantLow,
        BuiltinCells.constantHigh,
        BuiltinCells.inputPad,
        BuiltinCells.outputPad,
        BuiltinCells.inputTerminal,
        BuiltinCells.outputTerminal,
        DisplayPixelCell.logicalType,
    ).associateBy { it.id.value }

    private val cells = CellDefinitionLoader(
        mapOf(
            "support" to RedstoneBlocks.cellSupport,
            "glass" to RedstoneBlocks.glassSupport,
            "dust" to RedstoneBlocks.dust,
            "torch-east" to RedstoneBlocks.wallTorch(Direction.EAST),
            "torch-north" to RedstoneBlocks.wallTorch(Direction.NORTH),
            "torch-south" to RedstoneBlocks.wallTorch(Direction.SOUTH),
            "torch-west" to RedstoneBlocks.wallTorch(Direction.WEST),
            "repeater-east" to RedstoneBlocks.repeater(Direction.EAST),
            "repeater-north" to RedstoneBlocks.repeater(Direction.NORTH),
            "repeater-south" to RedstoneBlocks.repeater(Direction.SOUTH),
            "repeater-north-1" to RedstoneBlocks.repeater(Direction.NORTH),
            "repeater-south-1" to RedstoneBlocks.repeater(Direction.SOUTH),
            "repeater-east-2" to RedstoneBlocks.repeater(Direction.EAST, delay = 2),
            "repeater-north-2" to RedstoneBlocks.repeater(Direction.NORTH, delay = 2),
            "repeater-north-3" to RedstoneBlocks.repeater(Direction.NORTH, delay = 3),
            "repeater-north-4" to RedstoneBlocks.repeater(Direction.NORTH, delay = 4),
            "repeater-south-2" to RedstoneBlocks.repeater(Direction.SOUTH, delay = 2),
            "repeater-south-3" to RedstoneBlocks.repeater(Direction.SOUTH, delay = 3),
            "repeater-south-4" to RedstoneBlocks.repeater(Direction.SOUTH, delay = 4),
            "repeater-west" to RedstoneBlocks.repeater(Direction.WEST),
            "comparator-north-subtract" to RedstoneBlocks.comparator(
                Direction.NORTH,
                org.kvxd.dust.device.redstone.ComparatorMode.SUBTRACT,
            ),
            "comparator-east-subtract" to RedstoneBlocks.comparator(
                Direction.EAST,
                org.kvxd.dust.device.redstone.ComparatorMode.SUBTRACT,
            ),
            "lever-floor" to RedstoneBlocks.floorLever,
            "lamp" to RedstoneBlocks.lamp,
        ),
        logicalType = { name, _ -> logicalTypes.getValue(name) },
    )

    private val cellLibrary = CellLibrary(
        listOf(
            BuiltinCells.not,
            BuiltinCells.and2,
            BuiltinCells.or2,
            BuiltinCells.xor2,
            BuiltinCells.mux2,
            BuiltinCells.latch,
            BuiltinCells.dff,
            BuiltinCells.constantLow,
            BuiltinCells.constantHigh,
        ).map { logical ->
            CellProvider.fixed(logical, physicalView = { cells.load(logical.id.value) })
        } + cells.provider(ClockCell.NAME, ClockCell::logicalType) +
            cells.provider(DisplayMatrixCell.NAME, DisplayMatrixCell::logicalType),
        displayCell = RedstoneDisplayCell,
    )

    val technology: RedstoneTechnology = technology()

    fun technology(isolation: Int = 1, planeSeparation: Int = 2): RedstoneTechnology = RedstoneTechnology(
        cellLibrary = cellLibrary,
        debugInputPad = cells.load("input-pad"),
        debugOutputPad = cells.load("output-pad"),
        inputTerminal = cells.load("input-terminal"),
        outputTerminal = cells.load("output-terminal"),
        ioSign = RedstoneBlocks.ioSign,
        wire = RedstoneBlocks.dust,
        routeSupport = RedstoneBlocks.glassSupport,
        viaSupport = RedstoneBlocks.opaqueSupport,
        isolation = isolation,
        lowerPlaneY = 1,
        viaSignalOffsets = List(planeSeparation + 1) { step -> BlockPos(0, step, -step) },
    )
}

internal fun BlockMatrix.placeChecked(pos: BlockPos, state: BlockState) {
    require(contains(pos)) { "$pos is outside $this" }
    val previous = blockAt(pos)
    require(previous.isAir || previous == state) { "$pos contains $previous, cannot place $state" }
    setBlockAt(pos, state)
}
