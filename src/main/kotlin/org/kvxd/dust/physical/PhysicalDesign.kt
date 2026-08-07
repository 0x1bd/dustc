package org.kvxd.dust.physical

import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.technology.RedstoneTechnology

data class PhysicalDesign(
    val netlist: BooleanNetlist,
    val technology: RedstoneTechnology,
    val matrix: BlockMatrix,
    val cells: List<PlacedCell>,
    val routes: List<RoutedNet>,
    val inputs: Map<String, BlockPos>,
    val outputs: Map<String, BlockPos>,
    val rowCount: Int,
    val laneCount: Int,
    val globalNetCount: Int,
    val routeDelayTicks: Map<BlockPos, Int>,
)
