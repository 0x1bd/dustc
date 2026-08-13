package org.kvxd.dust.physical.compilation.model

internal data class FloorplanSelection(
    val plan: Floorplan,
    val candidate: Int,
    val candidateTotal: Int,
)
