package org.kvxd.dust.timing

data class TimingReport(

    val criticalPathTicks: Int,

    val worstHoldSlackTicks: Int,
    val holdViolations: List<HoldViolation>,
) {
    val isClean: Boolean get() = holdViolations.isEmpty()
}
