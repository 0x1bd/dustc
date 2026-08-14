package org.kvxd.dust.timing

data class SetupViolation(
    val register: String,
    val launchedBy: String,
    val requiredPeriodTicks: Int,
    val actualPeriodTicks: Int,
    val slackTicks: Int,
)
