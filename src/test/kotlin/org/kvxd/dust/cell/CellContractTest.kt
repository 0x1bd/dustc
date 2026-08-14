package org.kvxd.dust.cell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.behavior.CellEvaluation
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.cell.timing.DelayRange
import org.kvxd.dust.cell.timing.Edge
import org.kvxd.dust.cell.timing.TimingArc
import org.kvxd.dust.cell.timing.TimingConstraint
import org.kvxd.dust.netlist.SequentialSimulator
import org.kvxd.dust.netlist.booleanNetlist

class CellContractTest {
    @Test
    fun `state cells cut legal feedback while combinational cycles are rejected`() {
        val dff = CellType(
            CellTypeId("test-dff"),
            listOf(
                CellPort("d", 1, PortDirection.INPUT),
                CellPort("clock", 1, PortDirection.INPUT),
                CellPort("q", 1, PortDirection.OUTPUT),
            ),
            CellBehavior.Stateful(
                1,
                CellBehavior.Trigger.EdgeTriggered("clock", Edge.RISE),
            ) { inputs, _ ->
                val q = inputs.getValue("d").single()
                CellEvaluation(mapOf("q" to booleanArrayOf(q)), booleanArrayOf(q))
            },
            CellTiming(
                listOf(
                    TimingArc("clock", "q", rise = DelayRange(1, 1), fall = DelayRange(1, 1)),
                ),
                listOf(TimingConstraint.SetupHold("d", "clock", Edge.RISE, 1, 1)),
            ),
        )
        val counter = booleanNetlist("feedback") {
            val step = input("step")
            val clock = input("clock")
            val q = wire()
            val toggled = not(q)
            val d = mux(step, q, toggled)
            connect(dff, mapOf("d" to listOf(d), "clock" to listOf(clock), "q" to listOf(q)))
            output("q", q)
        }
        val simulator = SequentialSimulator(counter)
        assertEquals(false, simulator.step(mapOf("step" to true, "clock" to false)).getValue("q"))
        assertEquals(true, simulator.step(mapOf("step" to true, "clock" to true)).getValue("q"))
        assertEquals(true, simulator.step(mapOf("step" to false, "clock" to true)).getValue("q"))
        assertEquals(true, simulator.step(mapOf("step" to true, "clock" to false)).getValue("q"))
        assertEquals(false, simulator.step(mapOf("step" to true, "clock" to true)).getValue("q"))

        assertFailsWith<IllegalArgumentException> {
            booleanNetlist("bad-cycle") {
                input("unused")
                val a = wire()
                val b = not(a)
                connect(BuiltinCells.not, mapOf("a" to listOf(b), "y" to listOf(a)))
                output("a", a)
            }
        }
    }
}
