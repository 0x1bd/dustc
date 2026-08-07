package org.kvxd.dust.netlist

import kotlin.test.Test
import kotlin.test.assertEquals

class SequentialSimulatorTest {
    @Test
    fun `latch follows open data and retains closed data`() {
        val netlist = booleanNetlist("latch") {
            val data = input("data")
            val hold = input("hold")
            output("q", latch(data, hold))
        }
        val simulator = SequentialSimulator(netlist)

        fun step(data: Boolean, hold: Boolean): Boolean =
            checkNotNull(simulator.step(mapOf("data" to data, "hold" to hold))["q"])

        assertEquals(false, step(false, true))
        assertEquals(false, step(true, true))
        assertEquals(true, step(true, false))
        assertEquals(true, step(true, true))
        assertEquals(true, step(false, true))
        assertEquals(false, step(false, false))
    }
}
