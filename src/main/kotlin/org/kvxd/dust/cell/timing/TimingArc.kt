package org.kvxd.dust.cell.timing

data class TimingArc(
    val fromPort: String,
    val toPort: String,
    val fromBit: Int? = null,
    val toBit: Int? = null,
    val rise: DelayRange,
    val fall: DelayRange,
)
