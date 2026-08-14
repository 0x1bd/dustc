package org.kvxd.dust.timing

data class ClockSkewViolation(
    val clock: String,
    val earliestSink: String,
    val latestSink: String,
    val earliestTicks: Int,
    val latestTicks: Int,
    val skewTicks: Int,
    val limitTicks: Int,
)
