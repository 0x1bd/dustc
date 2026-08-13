package org.kvxd.dust.physical.compilation.model

internal data class PreparedRow(
    val draft: RowDraft,
    val laneY: Int,
    val laneBase: Int,
    val depth: Int,
)
