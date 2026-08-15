package org.kvxd.dust.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.netlist.Primitive
import org.kvxd.dust.physical.PhysicalSimulationHarness

class Mux8PhysicalTest {
    @Test
    fun `mux8 uses compound mux cells and remains electrically correct across transitions`() {
        val module = CircuitSourceLoader().load(Path.of("examples", "mux8.dust"))
        val netlist = module.lowerToBooleanNetlist()
        assertEquals(8, netlist.gates.size)
        assertTrue(netlist.gates.all { it.primitive == Primitive.MUX2 })

        val design = module.compile().physical
        assertEquals(8, design.cells.count { it.cell.logicalType == Primitive.MUX2.cellType })
        val simulation = PhysicalSimulationHarness(design)

        val vectors = buildList {
            add(Triple(false, 0, 255))
            add(Triple(true, 0, 255))
            repeat(8) { bit ->
                val value = 1 shl bit
                add(Triple(false, value, 0))
                add(Triple(true, value, 0))
                add(Triple(true, 0, value))
                add(Triple(false, 0, value))
            }
            add(Triple(false, 0xaa, 0x55))
            add(Triple(true, 0xaa, 0x55))
            add(Triple(false, 255, 0))
            add(Triple(true, 0, 255))
        }
        vectors.forEachIndexed { index, (select, low, high) ->
            simulation.setInput("select", select)
            simulation.setBus("low", 8, low.toULong())
            simulation.setBus("high", 8, high.toULong())
            simulation.advance()
            assertEquals((if (select) high else low).toULong(), simulation.outputBus("value", 8), "vector $index")
        }
        simulation.requireSettled()
    }
}
