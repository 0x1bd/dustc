package org.kvxd.dust.cell

data class CellEvaluation(
    val outputs: Map<String, BooleanArray>,
    val nextState: BooleanArray = BooleanArray(0),
)
