package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import org.kvxd.dust.Circuit
import org.kvxd.dust.CircuitResult

class AluTest {
    @Test
    fun `all operations and flags are exhaustive through five bits`() {
        for (width in 1..5) {
            val circuit = aluHarness(width)
            val limit = 1 shl width
            val vectors = buildList {
                for (a in 0 until limit) for (b in 0 until limit) for (operation in 0..7) {
                    add(
                        mapOf(
                            "a" to a.toULong(),
                            "b" to b.toULong(),
                            "operation" to operation.toULong(),
                        ),
                    )
                }
            }
            circuit.evaluateAll(vectors).zip(vectors).forEach { (result, vector) ->
                assertResult(
                    width,
                    vector.getValue("a").toInt(),
                    vector.getValue("b").toInt(),
                    vector.getValue("operation").toInt(),
                    result,
                )
            }
        }
    }

    @Test
    fun `four bit default makes the ALU callable without specialization`() {
        val circuit = DustLanguage.compile(
            """
            module default_alu(
                input a: bits<4>,
                input b: bits<4>,
                input operation: bits<3>,
                output value: bits<4>,
                output zero: bit,
            ) {
                let result = alu(a, b, operation)
                value = result.value
                zero = result.zero
            }
            """.trimIndent(),
            "default-alu.dust",
        ).single()

        val result = circuit.evaluate("a" to 7uL, "b" to 9uL, "operation" to 0uL)
        assertEquals(0uL, result["value"])
        assertEquals(true, result.bit("zero"))
    }

    private fun aluHarness(width: Int): Circuit = DustLanguage.compile(
        """
        module alu_harness<const WIDTH: int>(
            input a: bits<WIDTH>,
            input b: bits<WIDTH>,
            input operation: bits<3>,
            output value: bits<WIDTH>,
            output zero: bit,
            output negative: bit,
            output carry: bit,
            output overflow: bit,
        ) {
            let calculated = alu<WIDTH>(a, b, operation)
            value = calculated.value
            zero = calculated.zero
            negative = calculated.negative
            carry = calculated.carry
            overflow = calculated.overflow
        }
        """.trimIndent(),
        "alu-harness.dust",
        parameters = mapOf("WIDTH" to width),
    ).single()

    private fun assertResult(width: Int, a: Int, b: Int, operation: Int, result: CircuitResult) {
        val limit = 1 shl width
        val mask = limit - 1
        val expected = when (operation) {
            0 -> (a + b) and mask
            1 -> (a - b) and mask
            2 -> a and b
            3 -> a or b
            4 -> a xor b
            5 -> a.inv() and mask
            6 -> if (a == b) 1 else 0
            else -> if (a < b) 1 else 0
        }
        val signedA = signed(a, width)
        val signedB = signed(b, width)
        val signedResult = signed(expected, width)
        val carry = when (operation) {
            0 -> a + b >= limit
            1 -> a >= b
            else -> false
        }
        val overflow = when (operation) {
            0 -> (signedA >= 0) == (signedB >= 0) && (signedResult >= 0) != (signedA >= 0)
            1 -> (signedA >= 0) != (signedB >= 0) && (signedResult >= 0) != (signedA >= 0)
            else -> false
        }

        val context = "width=$width a=$a b=$b operation=$operation"
        assertEquals(expected.toULong(), result["value"], "value $context")
        assertEquals(expected == 0, result.bit("zero"), "zero $context")
        assertEquals(expected and (1 shl (width - 1)) != 0, result.bit("negative"), "negative $context")
        assertEquals(carry, result.bit("carry"), "carry $context")
        assertEquals(overflow, result.bit("overflow"), "overflow $context")
    }

    private fun signed(value: Int, width: Int): Int {
        val sign = 1 shl (width - 1)
        return if (value and sign == 0) value else value - (1 shl width)
    }
}
