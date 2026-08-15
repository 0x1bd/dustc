package org.kvxd.dust.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.kvxd.dust.compile
import org.kvxd.dust.physical.PhysicalSimulationHarness

class Adder4PhysicalTest {
    @Test
    fun `placed and routed adder computes in emitted redstone`() {
        val design = CircuitSourceLoader().load(Path.of("examples", "adder4.dust")).compile().physical
        val simulation = PhysicalSimulationHarness(design)

        val vectors = listOf(
            Triple(0, 0, 0),
            Triple(15, 0, 0),
            Triple(15, 0, 1),
            Triple(0, 15, 0),
            Triple(15, 15, 1),
            Triple(5, 10, 0),
            Triple(10, 5, 1),
            Triple(8, 7, 1),
            Triple(3, 12, 0),
            Triple(0, 0, 0),
        )
        vectors.forEachIndexed { index, (a, b, cin) ->
            simulation.setBus("a", 4, a.toULong())
            simulation.setBus("b", 4, b.toULong())
            simulation.setInput("cin", cin != 0)
            simulation.advance()

            val expected = a + b + cin
            assertEquals(
                (expected and 15).toULong(),
                simulation.outputBus("sum", 4),
                "vector $index a=$a b=$b cin=$cin",
            )
            assertEquals(
                expected > 15,
                simulation.output("cout"),
                "vector $index a=$a b=$b cin=$cin",
            )
        }
        simulation.requireSettled()
    }
}
