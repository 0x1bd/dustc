package org.kvxd.dust.lang

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.Circuit
import org.kvxd.dust.CircuitResult
import org.kvxd.dust.compile
import org.kvxd.dust.physical.PhysicalSimulationHarness

class ArithmeticLibraryTest {
    @Test
    fun `arithmetic and comparisons are exhaustive through five bits`() {
        for (width in 1..5) {
            val circuit = suite(width)
            val limit = 1 shl width
            val mask = limit - 1
            val vectors = buildList {
                for (a in 0 until limit) for (b in 0 until limit) {
                    for (carry in 0..1) for (borrow in 0..1) {
                        add(
                            mapOf(
                                "a" to a.toULong(),
                                "b" to b.toULong(),
                                "carry_in" to carry.toULong(),
                                "borrow_in" to borrow.toULong(),
                            ),
                        )
                    }
                }
            }
            circuit.evaluateAll(vectors).zip(vectors).forEach { (result, vector) ->
                val a = vector.getValue("a").toInt()
                val b = vector.getValue("b").toInt()
                val carry = vector.getValue("carry_in").toInt()
                val borrow = vector.getValue("borrow_in").toInt()
                assertVector(width, mask, a, b, carry, borrow, result)
            }
        }
    }

    @Test
    fun `widened arithmetic covers signed limits and doubled error values`() {
        val width = 7
        val mask = (1 shl width) - 1
        val circuit = suite(width)
        val values = listOf(0, 1, 31, 32, 63, 64, 65, 126, 127)
        for (a in values) for (b in values) {
            val result = circuit.evaluate(
                "a" to a.toULong(),
                "b" to b.toULong(),
                "carry_in" to 1uL,
                "borrow_in" to 1uL,
            )
            assertVector(width, mask, a, b, carry = 1, borrow = 1, result)
        }
    }

    @Test
    fun `bundled arithmetic flattens and bundled names cannot be shadowed`() {
        val circuit = suite(5)
        val netlist = circuit.lowerToBooleanNetlist()
        assertTrue(netlist.gates.isNotEmpty())
        assertEquals(netlist.gates.size + 2, netlist.instances.size, "only the two shared constants are non-primitive")
        assertTrue(netlist.instances.none { it.type.id.value in setOf("add", "subtract", "compare-unsigned") })

        val error = assertFailsWith<DustCompileException> {
            DustLanguage.compile("module add(input a: bit, output y: bit) { y = a }", "shadow.dust")
        }
        assertTrue("ambiguous with a bundled Dust library module" in error.message.orEmpty())
    }

    @Test
    fun `representative adder and digital comparator agree in emitted redstone`() {
        val circuit = DustLanguage.compile(
            """
            module arithmetic_physical<const WIDTH: int = 3>(
                input a: bits<WIDTH>,
                input b: bits<WIDTH>,
                input carry_in: bit,
                output sum: bits<WIDTH>,
                output carry_out: bit,
                output less: bit,
            ) {
                let added = ripple_add<WIDTH>(a, b, carry_in)
                let compared = compare_unsigned<WIDTH>(a, b)
                sum = added.sum
                carry_out = added.carry_out
                less = compared.less
            }
            """.trimIndent(),
            "arithmetic-physical.dust",
        ).single()
        val design = circuit.compile().physical
        val simulation = PhysicalSimulationHarness(design)

        val vectors = buildList {
            for (a in 0..7) for (b in 0..7) for (carry in 0..1) add(Triple(a, b, carry))
        }
        vectors.forEachIndexed { index, (a, b, carry) ->
            simulation.setBus("a", 3, a.toULong())
            simulation.setBus("b", 3, b.toULong())
            simulation.setInput("carry_in", carry != 0)
            simulation.advance()

            val expected = circuit.evaluate(
                "a" to a.toULong(),
                "b" to b.toULong(),
                "carry_in" to carry.toULong(),
            )
            assertEquals(expected["sum"], simulation.outputBus("sum", 3), "vector $index: a=$a b=$b carry=$carry")
            assertEquals(
                expected.bit("carry_out"),
                simulation.output("carry_out"),
                "vector $index: carry",
            )
            assertEquals(
                expected.bit("less"),
                simulation.output("less"),
                "vector $index: comparison",
            )
        }
        simulation.requireSettled()
    }

    private fun suite(width: Int): Circuit = DustLanguage.compile(
        SUITE,
        "arithmetic-suite.dust",
        parameters = mapOf("WIDTH" to width),
        moduleName = "arithmetic_suite",
    ).single()

    private fun assertVector(
        width: Int,
        mask: Int,
        a: Int,
        b: Int,
        carry: Int,
        borrow: Int,
        result: CircuitResult,
    ) {
        val added = a + b + carry
        val subtracted = a - b - borrow
        assertEquals((added and mask).toULong(), result["sum"], "add width=$width a=$a b=$b carry=$carry")
        assertEquals(added > mask, result.bit("carry_out"), "carry width=$width a=$a b=$b carry=$carry")
        assertEquals((subtracted and mask).toULong(), result["difference"], "sub width=$width a=$a b=$b")
        assertEquals(a < b + borrow, result.bit("borrow_out"), "borrow width=$width a=$a b=$b borrow=$borrow")
        assertEquals((-a and mask).toULong(), result["negated"], "negate width=$width a=$a")
        assertEquals((a + 1 and mask).toULong(), result["incremented"], "increment width=$width a=$a")
        assertEquals(a == mask, result.bit("increment_carry"), "increment carry width=$width a=$a")
        assertEquals((a - 1 and mask).toULong(), result["decremented"], "decrement width=$width a=$a")
        assertEquals(a == 0, result.bit("decrement_borrow"), "decrement borrow width=$width a=$a")
        assertEquals(a == b, result.bit("equal"), "equal width=$width a=$a b=$b")
        assertEquals(a != b, result.bit("not_equal"), "not equal width=$width a=$a b=$b")
        assertEquals(a < b, result.bit("unsigned_less"), "unsigned less width=$width a=$a b=$b")
        assertEquals(a > b, result.bit("unsigned_greater"), "unsigned greater width=$width a=$a b=$b")
        val signedA = signed(a, width)
        val signedB = signed(b, width)
        assertEquals(signedA < signedB, result.bit("signed_less"), "signed less width=$width a=$a b=$b")
        assertEquals(signedA > signedB, result.bit("signed_greater"), "signed greater width=$width a=$a b=$b")
        assertEquals(abs(a - b).toULong(), result["absolute_difference"], "absolute width=$width a=$a b=$b")
    }

    private fun signed(value: Int, width: Int): Int {
        val sign = 1 shl (width - 1)
        return if (value and sign == 0) value else value - (1 shl width)
    }

    private companion object {
        val SUITE = """
            module arithmetic_suite<const WIDTH: int>(
                input a: bits<WIDTH>,
                input b: bits<WIDTH>,
                input carry_in: bit,
                input borrow_in: bit,
                output sum: bits<WIDTH>,
                output carry_out: bit,
                output difference: bits<WIDTH>,
                output borrow_out: bit,
                output negated: bits<WIDTH>,
                output incremented: bits<WIDTH>,
                output increment_carry: bit,
                output decremented: bits<WIDTH>,
                output decrement_borrow: bit,
                output equal: bit,
                output not_equal: bit,
                output unsigned_less: bit,
                output unsigned_greater: bit,
                output signed_less: bit,
                output signed_greater: bit,
                output absolute_difference: bits<WIDTH>,
            ) {
                let added = ripple_add<WIDTH>(a, b, carry_in)
                let subtracted = subtract<WIDTH>(a, b, borrow_in)
                let negative = negate<WIDTH>(a)
                let plus_one = increment<WIDTH>(a)
                let minus_one = decrement<WIDTH>(a)
                let unsigned = compare_unsigned<WIDTH>(a, b)
                let signed = compare_signed<WIDTH>(a, b)
                let distance = absolute_difference<WIDTH>(a, b)
                sum = added.sum
                carry_out = added.carry_out
                difference = subtracted.difference
                borrow_out = subtracted.borrow_out
                negated = negative.result
                incremented = plus_one.result
                increment_carry = plus_one.carry_out
                decremented = minus_one.result
                decrement_borrow = minus_one.borrow_out
                equal = unsigned.equal
                not_equal = unsigned.not_equal
                unsigned_less = unsigned.less
                unsigned_greater = unsigned.greater
                signed_less = signed.less
                signed_greater = signed.greater
                absolute_difference = distance.difference
            }
        """.trimIndent()
    }
}
