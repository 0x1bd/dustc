package org.kvxd.dust.physical

import kotlin.test.Test
import kotlin.test.assertTrue

class PlacementQualityTest {
    @Test
    fun `decoded mux routes backward across rows within its cost bound`() {
        val netlist = RouterHarness.decodedMux(8)
        val design = PhysicalCompiler().compile(netlist)

        val rowByEndpoint = design.cells.flatMap { cell ->
            cell.cell.pins.map { pin -> cell.pin(pin.name) to cell.row }
        }.toMap()
        val reverseRoutes = design.routes.filter { route ->
            val sourceRow = rowByEndpoint.getValue(route.source)
            route.sinks.any { sink -> rowByEndpoint.getValue(sink) < sourceRow }
        }
        assertTrue(reverseRoutes.isNotEmpty(), "fixture no longer contains a route from a later row to an earlier row")
        RouterHarness.proveElectrically(design, vectors = 4, seed = 99)

        val quality = RouterHarness.measure(design)
        val recordedNodesPerGate = 32.14
        assertTrue(
            quality.nodesPerGate <= recordedNodesPerGate * 1.02,
            "decoded-mux-8 cost per gate rose from $recordedNodesPerGate: $quality",
        )
    }
}
