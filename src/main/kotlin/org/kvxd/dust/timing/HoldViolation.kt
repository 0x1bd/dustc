package org.kvxd.dust.timing

data class HoldViolation(
    val latch: String,
    val launchedBy: String,
    val earliestDataTicks: Int,
    val latestHoldTicks: Int,
    val slackTicks: Int,
)
