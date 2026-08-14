package org.kvxd.dust.timing

data class TimingReport(
    val criticalPathTicks: Int,
    val generatedClockPeriodTicks: Int?,
    val minimumClockPeriodTicks: Int,
    val worstSetupSlackTicks: Int,
    val setupViolations: List<SetupViolation>,
    val worstHoldSlackTicks: Int,
    val holdViolations: List<HoldViolation>,
    val maximumClockSkewTicks: Int,
    val clockSkewViolations: List<ClockSkewViolation>,
) {
    val minimumSafeStepTicks: Int get() = minimumClockPeriodTicks

    val isClean: Boolean
        get() = setupViolations.isEmpty() && holdViolations.isEmpty() && clockSkewViolations.isEmpty()
}
