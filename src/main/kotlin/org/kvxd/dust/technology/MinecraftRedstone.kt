package org.kvxd.dust.technology

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.technology.definition.CellDefinitionLoader

object MinecraftRedstone {
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
    )

    val technology: RedstoneTechnology = technology()

    fun technology(isolation: Int = 1, planeSeparation: Int = 2): RedstoneTechnology = RedstoneTechnology(
        primitives = mapOf(
            org.kvxd.dust.netlist.Primitive.NOT to cells.load("not"),
            org.kvxd.dust.netlist.Primitive.AND2 to cells.load("and2"),
            org.kvxd.dust.netlist.Primitive.OR2 to cells.load("or2"),
            org.kvxd.dust.netlist.Primitive.XOR2 to cells.load("xor2"),
            org.kvxd.dust.netlist.Primitive.MUX2 to cells.load("mux2"),
            org.kvxd.dust.netlist.Primitive.LATCH to cells.load("latch"),
        ),
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
