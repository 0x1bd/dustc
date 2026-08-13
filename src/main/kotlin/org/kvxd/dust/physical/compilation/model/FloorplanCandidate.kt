package org.kvxd.dust.physical.compilation.model

internal data class FloorplanCandidate(
    val plan: Floorplan,
    val partitions: List<List<CellSpec>>,
    val assignment: IntArray,
    val tierCount: Int,
    val candidate: Int,
)
