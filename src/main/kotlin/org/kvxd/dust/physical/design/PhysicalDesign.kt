package org.kvxd.dust.physical.design

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.geometry.BlockPos
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
    val observations: Map<String, BlockPos> = emptyMap(),
)
