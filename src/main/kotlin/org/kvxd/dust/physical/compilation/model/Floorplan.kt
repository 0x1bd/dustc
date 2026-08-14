package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.timing.TimingReport

internal data class Floorplan(
    val rows: List<PlacedRow>,
    val cells: List<PlacedCell>,
    val globalTracks: List<GlobalTrack>,
    val width: Int,
    val height: Int,
    val length: Int,
    val timingCutCost: Long,
    val routingRepeaters: Long,
    val routingBlocks: Long,
    val tierCount: Int,
    val clockSkewTicks: Int,
    val timing: TimingReport?,
) {
    val area: Long = width.toLong() * length
    val maximumDimension: Int = maxOf(width, length)
    val selectionCost: Long = routingBlocks * ROUTING_SELECTION_WEIGHT +
        maximumDimension.toLong() * MAX_DIMENSION_SELECTION_WEIGHT + area * AREA_SELECTION_WEIGHT
    val timingViolationCount: Int = timing?.let {
        it.setupViolations.size + it.holdViolations.size + it.clockSkewViolations.size
    } ?: 0
    val timingDeficitTicks: Int = timing?.let {
        maxOf(0, -it.worstSetupSlackTicks) + maxOf(0, -it.worstHoldSlackTicks)
    } ?: 0
}
