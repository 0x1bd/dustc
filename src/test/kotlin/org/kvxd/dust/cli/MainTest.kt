package org.kvxd.dust.cli

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun `build errors are concise`() {
        val errors = StringWriter()
        val commandLine = dustcCommandLine().setErr(PrintWriter(errors))

        assertEquals(1, commandLine.execute("build", "missing.dust"))
        assertTrue("dustc: error: design file does not exist: missing.dust" in errors.toString())
        assertFalse("\tat " in errors.toString())
    }

    @Test
    fun `duplicate top level parameters are rejected before building`() {
        val errors = StringWriter()
        val commandLine = dustcCommandLine().setErr(PrintWriter(errors))

        assertEquals(
            1,
            commandLine.execute(
                "build",
                "examples/gates.dust",
                "--param",
                "WIDTH=4",
                "--param",
                "WIDTH=8",
            ),
        )
        assertTrue("duplicate --param 'WIDTH'" in errors.toString())
    }

    @Test
    fun `build specializes repeated top level parameters`() {
        val source = Files.createTempFile("parameterized-", ".dust")
        val schematic = Files.createTempFile("parameterized-", ".schem")
        Files.deleteIfExists(schematic)
        try {
            Files.writeString(
                source,
                "module main<const WIDTH: int = 1>(" +
                    "input a: bits<WIDTH>, output y: bits<WIDTH>) { y = ~a }",
            )
            val output = StringWriter()
            val commandLine = dustcCommandLine().setOut(PrintWriter(output))

            assertEquals(
                0,
                commandLine.execute(
                    "build",
                    source.toString(),
                    "--param",
                    "WIDTH=2",
                    "--output",
                    schematic.toString(),
                ),
            )
            assertTrue(Files.size(schematic) > 0)
            assertTrue("wrote $schematic" in output.toString())
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(schematic)
        }
    }
}
