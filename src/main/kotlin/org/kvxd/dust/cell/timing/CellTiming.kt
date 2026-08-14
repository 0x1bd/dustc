package org.kvxd.dust.cell.timing

data class CellTiming(
    val arcs: List<TimingArc>,
    val constraints: List<TimingConstraint> = emptyList(),
    val generatedClockPeriodTicks: Int? = null,
) {
    init {
        require(generatedClockPeriodTicks == null || generatedClockPeriodTicks > 0)
    }

    companion object {
        val NONE: CellTiming = CellTiming(emptyList())
    }
}
