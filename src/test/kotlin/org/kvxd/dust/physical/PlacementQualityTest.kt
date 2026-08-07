package org.kvxd.dust.physical

import kotlin.test.Test
import kotlin.test.assertTrue

class PlacementQualityTest {
    @Test
    fun `decoded mux routing cost does not regress`() {
        val recorded = mapOf(4 to 20.43, 8 to 32.14, 16 to 48.67)

        recorded.forEach { (groups, allowed) ->
            val netlist = RouterHarness.decodedMux(groups)
            val design = PhysicalCompiler().compile(netlist)
            RouterHarness.proveElectrically(design, vectors = 3, seed = groups)
            val quality = RouterHarness.measure(design)
            println("decoded-mux-$groups $quality")
            assertTrue(
                quality.nodesPerGate <= allowed * 1.02,
                "decoded-mux-$groups cost per gate rose from $allowed to ${quality.nodesPerGate}",
            )
        }
    }

    @Test
    fun `nets route correctly against the direction of the build order`() {
        val netlist = RouterHarness.decodedMux(8)
        val design = PhysicalCompiler().compile(netlist)

        val rowOf = design.cells.associate { it.name to it.row }
        val driverRow = design.netlist.instances.associate { instance ->
            instance.name to rowOf[instance.name]
        }
        assertTrue(driverRow.isNotEmpty())
        RouterHarness.proveElectrically(design, vectors = 4, seed = 99)
    }
}
