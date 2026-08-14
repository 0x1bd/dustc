package org.kvxd.dust.cell.library

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.behavior.CellEvaluation
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.CellTiming

internal object ClockCell {
    const val NAME = "clock"
    private const val ENABLE = "enabled"
    private const val OUTPUT = "clock"
    private const val MINIMUM_PERIOD_TICKS = 6
    private const val PERIOD_STEP_TICKS = 4
    private const val MAXIMUM_PERIOD_TICKS = 4094

    fun logicalType(arguments: Map<String, Int>): CellType {
        val periodTicks = arguments.getValue("CLOCK_TICKS")
        require(periodTicks in MINIMUM_PERIOD_TICKS..MAXIMUM_PERIOD_TICKS &&
            (periodTicks - MINIMUM_PERIOD_TICKS) % PERIOD_STEP_TICKS == 0) {
            "clock period $periodTicks cannot be represented; supported periods are " +
                "$MINIMUM_PERIOD_TICKS + ${PERIOD_STEP_TICKS}n ticks up to $MAXIMUM_PERIOD_TICKS"
        }
        return CellType(
            CellTypeId("clock-$periodTicks"),
            listOf(
                CellPort(ENABLE, 1, PortDirection.INPUT),
                CellPort(OUTPUT, 1, PortDirection.OUTPUT),
            ),
            behavior(periodTicks),
            CellTiming(emptyList(), generatedClockPeriodTicks = periodTicks),
        )
    }

    private fun behavior(periodTicks: Int): CellBehavior.Stateful {
        val halfPeriod = periodTicks / 2
        val counterBits = Int.SIZE_BITS - Integer.numberOfLeadingZeros(halfPeriod - 1)
        return CellBehavior.Stateful(
            stateBits = counterBits + 1,
            trigger = CellBehavior.Trigger.GeneratedClock(ENABLE, periodTicks),
        ) { inputs, previous ->
            val enabled = inputs.getValue(ENABLE).single()
            var level = previous[0]
            var elapsed = decode(previous, 1)
            if (!enabled) {
                level = false
                elapsed = 0
            } else if (++elapsed == halfPeriod) {
                level = !level
                elapsed = 0
            }
            val next = BooleanArray(counterBits + 1)
            next[0] = level
            repeat(counterBits) { bit -> next[bit + 1] = elapsed and (1 shl bit) != 0 }
            CellEvaluation(mapOf(OUTPUT to booleanArrayOf(level)), next)
        }
    }

    private fun decode(bits: BooleanArray, offset: Int): Int =
        (offset until bits.size).fold(0) { value, bit ->
            if (bits[bit]) value or (1 shl (bit - offset)) else value
        }
}
