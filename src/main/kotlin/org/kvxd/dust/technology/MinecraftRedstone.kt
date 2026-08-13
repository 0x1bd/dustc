package org.kvxd.dust.technology

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.CellProvider
import org.kvxd.dust.technology.definition.CellDefinitionLoader

object MinecraftRedstone {
    private val logicalTypes = listOf(
        BuiltinCells.not,
        BuiltinCells.and2,
        BuiltinCells.or2,
        BuiltinCells.xor2,
        BuiltinCells.mux2,
        BuiltinCells.latch,
        BuiltinCells.inputPad,
        BuiltinCells.outputPad,
        BuiltinCells.inputTerminal,
        BuiltinCells.outputTerminal,
    ).associateBy { it.id.value }

    private val cells = CellDefinitionLoader(
        mapOf(
            "support" to RedstoneBlocks.cellSupport,
            "glass" to RedstoneBlocks.glassSupport,
            "dust" to RedstoneBlocks.dust,
            "torch-east" to RedstoneBlocks.wallTorch(Direction.EAST),
            "torch-north" to RedstoneBlocks.wallTorch(Direction.NORTH),
            "torch-south" to RedstoneBlocks.wallTorch(Direction.SOUTH),
            "repeater-east" to RedstoneBlocks.repeater(Direction.EAST),
            "repeater-north" to RedstoneBlocks.repeater(Direction.NORTH),
            "repeater-south" to RedstoneBlocks.repeater(Direction.SOUTH),
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
        ).map { logical ->
            CellProvider.fixed(logical, physicalView = { cells.load(logical.id.value) })
        },
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
