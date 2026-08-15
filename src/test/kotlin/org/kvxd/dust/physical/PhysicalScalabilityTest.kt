package org.kvxd.dust.physical

import kotlin.test.Test
import kotlin.test.assertTrue
import org.kvxd.dust.netlist.booleanNetlist
import org.kvxd.dust.physical.io.PhysicalIo

class PhysicalScalabilityTest {
    @Test
    fun `large adder stays within per-gate routing cost`() {
        val netlist = RouterHarness.adder(128)
        val quality = RouterHarness.measure(PhysicalCompiler().compile(netlist))
        assertTrue(quality.nodesPerGate < 22.0, "cost per gate rose at scale: $quality")
    }

    @Test
    fun `high fanout stays within routing size bounds`() {
        val width = 64
        val netlist = booleanNetlist("fanout-$width") {
            val shared = input("shared")
            repeat(width) { bit ->
                val data = input("data[$bit]")
                output("q[$bit]", and(shared, data))
            }
        }
        val design = PhysicalCompiler().compile(netlist, PhysicalIo.TERMINALS)
        val routed = design.routes.sumOf { it.routeBlocks.size }
        assertTrue(maxOf(design.matrix.width, design.matrix.length) < 400)
        assertTrue(routed < 10_000)
    }
}
