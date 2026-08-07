package org.kvxd.dust.technology

import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockState
import org.kvxd.dust.device.Direction
import org.kvxd.dust.device.Properties
import org.kvxd.dust.device.Redstone
import org.kvxd.dust.cell.CellTypeId
import org.kvxd.dust.netlist.Primitive

class RedstoneTechnology internal constructor(
    val primitives: Map<Primitive, StandardCell>,
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
    init {
        require(primitives.keys == Primitive.entries.toSet())
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

        (primitives.values + debugInputPad + debugOutputPad + inputTerminal + outputTerminal).forEach { cell ->
            cell.pins.map { it.position.x }.sorted().zipWithNext().forEach { (left, right) ->
                require(right - left > isolation) {
                    "${cell.name} pins occupy columns $left and $right, which do not clear isolation $isolation"
                }
            }
        }
    }

    val cellGap: Int = isolation
    val cells: Map<CellTypeId, StandardCell> = primitives.map { (primitive, cell) ->
        primitive.cellType.id to cell
    }.toMap()
    val signalStrength: Int = Redstone.maximumSignalStrength
    val upperPlaneY: Int = lowerPlaneY + viaSignalOffsets.last().y
    val lanePitch: Int = viaSignalOffsets.maxOf { it.z } - viaSignalOffsets.minOf { it.z } + isolation
    val backboneDetour: Int = 1
    val backbonePitch: Int = backboneDetour + isolation + 1
    val backboneBypassLength: Int = viaSignalOffsets.maxOf { kotlin.math.abs(it.z) } + backboneDetour
    val cellOriginY: Int = upperPlaneY - inputTerminal.pin("y").position.y
    val routeHeight: Int = maxOf(
        upperPlaneY + 1,
        cellOriginY + (primitives.values + debugInputPad + debugOutputPad + inputTerminal + outputTerminal)
            .maxOf { it.size.y },
    )

    fun repeater(travel: Direction, delay: Int = Properties.DELAY.min): BlockState =
        RedstoneBlocks.repeater(travel, delay)

    fun placeCell(matrix: BlockMatrix, cell: StandardCell, origin: BlockPos) {
        cell.blocks.forEach { (local, state) -> matrix.placeChecked(origin + local, state) }
    }
}
