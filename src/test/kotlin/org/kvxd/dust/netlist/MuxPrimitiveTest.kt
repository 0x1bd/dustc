package org.kvxd.dust.netlist

import kotlin.test.Test
import kotlin.test.assertEquals

class MuxPrimitiveTest {
    @Test
    fun `word evaluation applies mux select independently in every lane`() {
        val netlist = booleanNetlist("mux-word") {
            val select = input("select")
            val low = input("low")
            val high = input("high")
            output("y", mux(select, low, high))
        }
        val select = 0b1010L
        val low = 0b1100L
        val high = 0b0011L
        val expected = (low and select.inv()) or (high and select)
        assertEquals(expected, netlist.evaluateWords(mapOf("select" to select, "low" to low, "high" to high)).getValue("y"))
    }
}
