package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.physical.design.PlacedCell

internal data class PlacedRow(
    val index: Int,
    val cells: List<PlacedCell>,
    val routes: List<LocalRoute>,
    val abutments: List<Abutment>,
)
