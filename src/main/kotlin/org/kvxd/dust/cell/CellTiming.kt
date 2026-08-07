package org.kvxd.dust.cell

data class CellTiming(
    val arcs: List<TimingArc>,
    val constraints: List<TimingConstraint> = emptyList(),
) {
    companion object {
        val NONE: CellTiming = CellTiming(emptyList())
    }
}
