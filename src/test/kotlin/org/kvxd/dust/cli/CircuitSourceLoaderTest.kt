package org.kvxd.dust.cli

import java.nio.file.Path
import java.nio.file.Files
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

    @Test
    fun `specializes a generic top level from parameters`() {
        val source = Files.createTempFile("generic-", ".dust")
        try {
            Files.writeString(
                source,
                "module generic<const WIDTH: int = 2>(input a: bits<WIDTH>, output y: bits<WIDTH>) { y = a }",
            )
            val circuit = CircuitSourceLoader().load(source, parameters = mapOf("WIDTH" to 7))
            assertEquals(7, circuit.inputs.single().width)
            assertEquals(7, circuit.outputs.single().width)
        } finally {
            Files.deleteIfExists(source)
        }
    }
}
