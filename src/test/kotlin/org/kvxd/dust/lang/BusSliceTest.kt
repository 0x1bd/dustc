package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BusSliceTest {
    @Test
    fun `bus slices read half open ranges in least significant bit order`() {
        val circuit = DustLanguage.compile(
            """
            module slices<const FIRST: int = 2>(
                input value: bits<8>,
                output low: bits<4>,
                output middle: bits<4>,
                output selected: bit,
            ) {
                low = value[0..4]
                middle = value[FIRST..FIRST + 4]
                selected = value[1..5][1]
            }
            """.trimIndent(),
            "slice-read.dust",
        ).single()

        val result = circuit.evaluate("value" to 0xd6uL)
        assertEquals(0x6uL, result["low"])
        assertEquals(0x5uL, result["middle"])
        assertEquals(true, result.bit("selected"))
    }

    @Test
    fun `slices assign output and mutable bus ranges`() {
        val circuit = DustLanguage.compile(
            """
            module slice_assign(
                input original: bits<8>,
                input replacement: bits<4>,
                output direct: bits<8>,
                output changed: bits<8>,
            ) {
                direct[0..4] = original[4..8]
                direct[4..8] = original[0..4]

                let mut edited = original
                edited[2..6] = replacement
                changed = edited
            }
            """.trimIndent(),
            "slice-assignment.dust",
        ).single()

        val result = circuit.evaluate("original" to 0xabuL, "replacement" to 0x5uL)
        assertEquals(0xbauL, result["direct"])
        assertEquals(0x97uL, result["changed"])
    }

    @Test
    fun `slice diagnostics validate bounds direction constants and widths`() {
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bits<2>) { y = a[-1..1] }",
            "slice -1..1 is out of bounds for a 4-bit bus",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bits<2>) { y = a[3..5] }",
            "slice 3..5 is out of bounds for a 4-bit bus",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bit) { y = a[1..-1] }",
            "slice 1..-1 is out of bounds for a 4-bit bus",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bit) { y = a[2..2] }",
            "slice range must select at least one bit",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bit) { y = a[3..2] }",
            "slice range must select at least one bit",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, input b: bit, output y: bit) { y = a[b..3] }",
            "expected a compile-time integer",
        )
        assertDiagnostic(
            "module m(input a: bits<4>, output y: bits<3>) { y[0..3] = a[0..2] }",
            "y slice needs 3 bits, got 2",
        )
    }

    private fun assertDiagnostic(source: String, message: String) {
        val exception = assertFailsWith<DustCompileException> { DustLanguage.compile(source, "invalid-slice.dust") }
        assertTrue(message in exception.message.orEmpty(), exception.message)
    }
}
