package org.kvxd.dust.physical

import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.technology.StandardCell

data class PlacedCell(
    val name: String,
    val cell: StandardCell,
    val origin: BlockPos,
    val row: Int,
    val nets: Map<String, Signal>,
) {
    fun pin(name: String): BlockPos = origin + cell.pin(name).position
}
