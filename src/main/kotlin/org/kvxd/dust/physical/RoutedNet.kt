package org.kvxd.dust.physical

import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.netlist.Signal

data class RoutedNet(
    val signal: Signal,
    val source: BlockPos,
    val sinks: List<BlockPos>,
    val routeBlocks: Set<BlockPos>,
)
