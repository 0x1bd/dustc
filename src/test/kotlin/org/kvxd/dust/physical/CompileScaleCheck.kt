package org.kvxd.dust.physical

import kotlin.test.Test
import kotlin.test.assertTrue

class CompileScaleCheck {
    @Test
    fun `a large gate network still compiles`() {
        val netlist = RouterHarness.adder(128)
        val quality = RouterHarness.measure(PhysicalCompiler().compile(netlist))
        println("adder-128 $quality")
        assertTrue(quality.nodesPerGate < 22.0, "cost per gate rose to ${quality.nodesPerGate} at scale")
    }
}
