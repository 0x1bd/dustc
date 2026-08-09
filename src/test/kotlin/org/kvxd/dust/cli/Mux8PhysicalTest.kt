package org.kvxd.dust.cli

import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.netlist.Primitive
import org.kvxd.dust.sim.GateLevelSimulator

class Mux8PhysicalTest {
    @Test
    fun `mux8 uses compound mux cells and remains electrically correct across transitions`() {
        val module = CircuitSourceLoader().load(Path.of("examples", "mux8.dust"))
        val netlist = module.lowerToBooleanNetlist()
        assertEquals(8, netlist.gates.size)
        assertTrue(netlist.gates.all { it.primitive == Primitive.MUX2 })

        val design = module.compile().physical
        assertEquals(8, design.cells.count { it.cell.logicalType == Primitive.MUX2.cellType })
        assertEquals(25, design.routes.size)

        val simulator = GateLevelSimulator(design.matrix)
        val tickBound = design.matrix.blockCount()
        simulator.settle(tickBound)

        fun driveBus(name: String, value: Int) {
            repeat(8) { bit ->
                simulator.setInput(design.inputs.getValue("$name[$bit]"), value and (1 shl bit) != 0)
            }
        }
        fun readBus(name: String): Int = (0 until 8).fold(0) { value, bit ->
            if (simulator.readOutput(design.outputs.getValue("$name[$bit]"))) value or (1 shl bit) else value
        }

        val random = Random(0x4D555832)
        repeat(384) { index ->
            val select = random.nextBoolean()
            val low = random.nextInt(256)
            val high = random.nextInt(256)
            simulator.setInput(design.inputs.getValue("select"), select)
            driveBus("low", low)
            driveBus("high", high)
            simulator.advanceUntilIdle(tickBound)
            assertEquals(if (select) high else low, readBus("value"), "vector $index")
        }
        assertTrue(simulator.unsettled().isEmpty())
    }
}
