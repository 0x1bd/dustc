package org.kvxd.dust.cli

import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.lang.DustCompileException

class CircuitSourceLoaderTest {
    @Test
    fun `loads every shipped example`() {
        listOf("adder4", "alu4", "full_adder", "gates", "mux8", "latch1").forEach { name ->
            val circuit = CircuitSourceLoader().load(Path.of("examples", "$name.dust"))
            assertEquals("main", circuit.name)
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
                "module main<const WIDTH: int = 2>(input a: bits<WIDTH>, output y: bits<WIDTH>) { y = a }",
            )
            val circuit = CircuitSourceLoader().load(source, parameters = mapOf("WIDTH" to 7))
            assertEquals(7, circuit.inputs.single().width)
            assertEquals(7, circuit.outputs.single().width)
        } finally {
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun `requires main unless a legacy module is requested`() {
        val source = Files.createTempFile("legacy-", ".dust")
        try {
            Files.writeString(source, "module old_top(input a: bit, output y: bit) { y = a }")

            val error = assertFailsWith<DustCompileException> { CircuitSourceLoader().load(source) }
            assertTrue("no module named 'main'" in error.message.orEmpty())
            assertEquals("old_top", CircuitSourceLoader().load(source, requestedName = "old_top").name)
        } finally {
            Files.deleteIfExists(source)
        }
    }
}
