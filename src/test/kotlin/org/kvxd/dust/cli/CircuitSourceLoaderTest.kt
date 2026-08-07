package org.kvxd.dust.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CircuitSourceLoaderTest {
    @Test
    fun `loads every shipped example`() {
        listOf("adder4", "full_adder", "gates", "mux8", "latch1").forEach { name ->
            val circuit = CircuitSourceLoader().load(Path.of("examples", "$name.dust"))
            assertEquals(name, circuit.name)
        }
    }

    @Test
    fun `loads the standalone adder source`() {
        val adder = CircuitSourceLoader().load(Path.of("examples", "adder4.dust"))
        val result = adder.evaluate("a" to 15uL, "b" to 1uL, "cin" to 0uL)

        assertEquals(0uL, result["sum"])
        assertEquals(true, result.bit("cout"))
    }
}
