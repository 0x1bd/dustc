package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.physical.design.PlacedCell

internal data class RowDraft(
    val index: Int,
    val cells: List<PlacedCell>,
    val routes: List<LocalRouteDraft>,
    val abutments: List<Abutment>,
    val cellDepth: Int,
)
