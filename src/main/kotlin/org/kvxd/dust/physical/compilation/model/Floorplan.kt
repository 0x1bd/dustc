package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.physical.design.PlacedCell

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
) {
    val area: Long = width.toLong() * length
    val maximumDimension: Int = maxOf(width, length)
    val selectionCost: Long = clockSkewTicks.toLong() * CLOCK_SKEW_SELECTION_WEIGHT +
        routingBlocks * ROUTING_SELECTION_WEIGHT +
        maximumDimension.toLong() * MAX_DIMENSION_SELECTION_WEIGHT + area * AREA_SELECTION_WEIGHT
}
