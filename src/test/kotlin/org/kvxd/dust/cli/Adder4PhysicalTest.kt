package org.kvxd.dust.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.kvxd.dust.compile
import org.kvxd.dust.sim.GateLevelSimulator

class Adder4PhysicalTest {
    @Test
    fun `placed and routed adder computes in emitted redstone`() {
        val design = CircuitSourceLoader().load(Path.of("examples", "adder4.dust")).compile().physical
        val simulator = GateLevelSimulator(design.matrix)
        val tickBound = design.matrix.blockCount()
        simulator.settle(tickBound)

        fun driveBus(name: String, width: Int, value: Int) {
            repeat(width) { bit ->
                simulator.setInput(design.inputs.getValue("$name[$bit]"), value and (1 shl bit) != 0)
            }
        }

        fun readBus(name: String, width: Int): Int = (0 until width).fold(0) { value, bit ->
            if (simulator.readOutput(design.outputs.getValue("$name[$bit]"))) value or (1 shl bit) else value
        }

        for (a in 0..15) {
            for (b in 0..15) {
                for (cin in 0..1) {
                    driveBus("a", 4, a)
                    driveBus("b", 4, b)
                    simulator.setInput(design.inputs.getValue("cin"), cin != 0)
                    simulator.advanceUntilIdle(tickBound)

                    val expected = a + b + cin
                    assertEquals(expected and 15, readBus("sum", 4), "a=$a b=$b cin=$cin")
                    assertEquals(
                        expected > 15,
                        simulator.readOutput(design.outputs.getValue("cout")),
                        "a=$a b=$b cin=$cin",
                    )
                }
            }
        }
    }
}
