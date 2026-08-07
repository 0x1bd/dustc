package org.kvxd.dust.cli

import java.io.PrintWriter
import java.io.StringWriter
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
}
