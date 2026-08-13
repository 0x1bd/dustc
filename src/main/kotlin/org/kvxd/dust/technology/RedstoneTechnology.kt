package org.kvxd.dust.technology

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.redstone.Redstone
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.library.CellLibrary

class RedstoneTechnology internal constructor(
    val cellLibrary: CellLibrary,
    val debugInputPad: StandardCell,
    val debugOutputPad: StandardCell,
    val inputTerminal: StandardCell,
    val outputTerminal: StandardCell,
    val ioSign: BlockState,
    val wire: BlockState,
    val routeSupport: BlockState,
    val viaSupport: BlockState,
    val isolation: Int,
    val lowerPlaneY: Int,
    val viaSignalOffsets: List<BlockPos>,
) {
    private val validatedCells = mutableSetOf<StandardCell>()

    init {
        require(isolation > 0)
        require(lowerPlaneY > 0)
        require(viaSignalOffsets.first() == BlockPos.ORIGIN)
        require(viaSignalOffsets.last().y > 0)
        viaSignalOffsets.zipWithNext().forEach { (from, to) ->
            val delta = to - from
            require(delta.y == 1 && delta.x == 0 && kotlin.math.abs(delta.z) == 1) {
                "via step $from -> $to is not a one-rise glass stair"
            }
        }

        cellLibrary.registeredCells().forEach { validateCell(it.physicalView) }
        listOf(debugInputPad, debugOutputPad, inputTerminal, outputTerminal).forEach(::validateCell)
    }

    val cellGap: Int = isolation
    val signalStrength: Int = Redstone.maximumSignalStrength
    val upperPlaneY: Int = lowerPlaneY + viaSignalOffsets.last().y
    val lanePitch: Int = viaSignalOffsets.maxOf { it.z } - viaSignalOffsets.minOf { it.z } + isolation
    val backboneDetour: Int = 1
    val backbonePitch: Int = backboneDetour + isolation + 1
    val backboneBypassLength: Int = viaSignalOffsets.maxOf { kotlin.math.abs(it.z) } + backboneDetour
    val cellOriginY: Int = upperPlaneY - inputTerminal.pin("y").position.y

    @Synchronized
    fun physicalCell(type: CellType): StandardCell? = cellLibrary.physicalView(type)?.also(::validateCell)

    fun repeater(travel: Direction, delay: Int = Properties.DELAY.min): BlockState =
        RedstoneBlocks.repeater(travel, delay)

    fun placeCell(matrix: BlockMatrix, cell: StandardCell, origin: BlockPos) {
        cell.blocks.forEach { (local, state) -> matrix.placeChecked(origin + local, state) }
    }

    private fun validateCell(cell: StandardCell) {
        if (!validatedCells.add(cell)) return
        cell.pins.map { it.position.x }.sorted().zipWithNext().forEach { (left, right) ->
            require(right - left > isolation) {
                "${cell.name} pins occupy columns $left and $right, which do not clear isolation $isolation"
            }
        }
    }
}
