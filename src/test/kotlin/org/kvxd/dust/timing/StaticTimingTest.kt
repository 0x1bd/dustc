package org.kvxd.dust.timing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.lang.DustLanguage

class StaticTimingTest {
    @Test
    fun `register path reports minimum period and setup failure`() {
        val design = pipeline()
        val balanced = StaticTiming.analyse(design)
        assertTrue(balanced.maximumClockSkewTicks <= 1)
        assertTrue(balanced.clockSkewViolations.isEmpty())
        val unconstrained = StaticTiming.analyse(design, maximumClockSkewTicks = Int.MAX_VALUE)
        assertTrue(unconstrained.minimumClockPeriodTicks >= 5)
        assertEquals(unconstrained.minimumClockPeriodTicks, unconstrained.minimumSafeStepTicks)

        val constrained = StaticTiming.analyse(
            design,
            clockPeriodTicks = unconstrained.minimumClockPeriodTicks - 1,
            maximumClockSkewTicks = Int.MAX_VALUE,
        )
        assertTrue(constrained.setupViolations.isNotEmpty())
        assertTrue(constrained.worstSetupSlackTicks < 0)
    }

    @Test
    fun `late capture clock reports hold and skew failures`() {
        val design = pipeline()
        val registers = design.cells.filter { it.cell.name == "dff" }.sortedBy { it.name }
        assertEquals(2, registers.size)
        val launch = registers[0]
        val capture = registers[1]
        val delays = design.routeDelayTicks.toMutableMap().apply {
            put(launch.pin("clock"), 0)
            put(capture.pin("clock"), 10)
            put(capture.pin("d"), 0)
        }
        val violated = StaticTiming.analyse(design.copy(routeDelayTicks = delays), maximumClockSkewTicks = 1)

        assertTrue(violated.holdViolations.any { it.latch == capture.name })
        assertTrue(violated.worstHoldSlackTicks < 0)
        assertEquals(10, violated.maximumClockSkewTicks)
        assertTrue(violated.clockSkewViolations.isNotEmpty())
    }

    private fun pipeline() = DustLanguage.compile(
        """
        module pipeline(
            input d: bit,
            input clock: bit,
            output q: bit,
        ) {
            let first = dff(d, clock)
            q = dff(first, clock)
        }
        """.trimIndent(),
        "pipeline.dust",
    ).single().compile().physical
}
