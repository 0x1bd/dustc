package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.physical.io.PhysicalIoEdge

class DustLanguageTest {
    @Test
    fun `one hot decoder selects the addressed bit`() {
        val decoder = DustLanguage.compile(
            "module main(input address: bits<3>, output selected: bits<8>) { selected = decode<8>(address) }",
            "decoder.dust",
        ).single()

        repeat(8) { address ->
            assertEquals(1uL shl address, decoder.evaluate("address" to address.toULong())["selected"])
        }
        val mismatch = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module main(input address: bits<2>, output selected: bits<8>) { selected = decode<8>(address) }",
                "bad-decoder.dust",
            )
        }
        assertTrue("decode<8> needs a 3-bit address" in mismatch.message.orEmpty())
    }

    @Test
    fun `hardware if expressions lower bits and buses to mux cells`() {
        val conditional = DustLanguage.compile(
            """
            module conditional(
                input select: bit,
                input low: bits<4>,
                input high: bits<4>,
                output y: bits<4>,
            ) {
                y = if select { high } else { low }
            }
            """.trimIndent(),
            "conditional.dust",
        ).single()

        assertEquals(0x3uL, conditional.evaluate("select" to 0uL, "low" to 0x3uL, "high" to 0xcuL)["y"])
        assertEquals(0xcuL, conditional.evaluate("select" to 1uL, "low" to 0x3uL, "high" to 0xcuL)["y"])
        val netlist = conditional.lowerToBooleanNetlist()
        assertEquals(4, netlist.gates.count { it.primitive == org.kvxd.dust.netlist.Primitive.MUX2 })
        val physical = conditional.compile().physical
        assertEquals(
            4,
            physical.cells.count { it.cell.logicalType === org.kvxd.dust.cell.library.BuiltinCells.mux2 },
        )
    }

    @Test
    fun `hardware if expressions nest and diagnose invalid widths`() {
        val nested = DustLanguage.compile(
            """
            module nested(
                input first: bit,
                input second: bit,
                input a: bit,
                input b: bit,
                input c: bit,
                output y: bit,
            ) {
                y = if first { a } else { if second { b } else { c } }
            }
            """.trimIndent(),
            "nested-if.dust",
        ).single()
        assertEquals(
            true,
            nested.evaluate("first" to 1uL, "second" to 0uL, "a" to 1uL, "b" to 0uL, "c" to 0uL).bit("y")
        )
        assertEquals(
            true,
            nested.evaluate("first" to 0uL, "second" to 1uL, "a" to 0uL, "b" to 1uL, "c" to 0uL).bit("y")
        )

        val invalid = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(input select: bits<2>, input a: bit, input b: bit, output y: bit) " +
                        "{ y = if select { a } else { b } }",
                "invalid-if.dust",
            )
        }
        assertTrue("an if condition must be one bit" in invalid.message.orEmpty())

        val mismatched = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(input select: bit, input a: bit, input b: bits<2>, output y: bit) " +
                        "{ y = if select { a } else { b } }",
                "mismatched-if.dust",
            )
        }
        assertTrue("if branches have widths 1 and 2" in mismatched.message.orEmpty())
    }

    @Test
    fun `four bit adder is logically exhaustive`() {
        val adder = DustLanguage.compile(ADDER, "adder4.dust").single()

        assertEquals(20, adder.lowerToBooleanNetlist().gates.size)
        assertEquals(listOf("operands", "result"), adder.ioGroups.map { it.name })
        for (a in 0..15) for (b in 0..15) for (cin in 0..1) {
            val result = adder.evaluate("a" to a.toULong(), "b" to b.toULong(), "cin" to cin.toULong())
            val expected = a + b + cin
            assertEquals((expected and 15).toULong(), result["sum"])
            assertEquals(expected > 15, result.bit("cout"))
        }
    }

    @Test
    fun `io groups do not impose physical terminal strips`() {
        fun compile(grouped: Boolean) = DustLanguage.compile(
            if (grouped) {
                """
                module panel(
                    input operands {
                        a: bit,
                        b: bit,
                    },
                    output result {
                        y: bit,
                    },
                ) {
                    y = a ^ b
                }
                """.trimIndent()
            } else {
                """
                module panel(
                    input a: bit,
                    input b: bit,
                    output y: bit,
                ) {
                    y = a ^ b
                }
                """.trimIndent()
            },
            if (grouped) "grouped.dust" else "plain.dust",
        ).single().compile().physical

        val grouped = compile(true)
        val plain = compile(false)
        val names = listOf("input-a", "input-b", "output-y")
        assertEquals(
            names.associateWith { name -> grouped.cells.single { it.name == name }.origin },
            names.associateWith { name -> plain.cells.single { it.name == name }.origin },
        )
    }

    @Test
    fun `panel marks named io groups as compact physical interfaces`() {
        val circuit = DustLanguage.compile(
            """
            module panel(
                #[panel] input operands { a: bits<2>, b: bit },
                #[panel] output result { y: bits<2> },
            ) {
                y[0] = a[0] ^ b
                y[1] = a[1] ^ b
            }
            """.trimIndent(),
            "panel.dust",
        ).single()

        assertTrue(circuit.ioGroups.single { it.name == "operands" }.panel)
        assertTrue(circuit.ioGroups.single { it.name == "result" }.panel)
    }

    @Test
    fun `edge accepts cardinal io constraints and does not propagate through flattened modules`() {
        val cardinal = DustLanguage.compile(
            """
            module cardinal(
                #[edge(north)] input north_in: bit,
                #[edge(south)] input south_in: bit,
                #[edge(east)] output east_out: bit,
                #[edge(west)] output west_out: bit,
            ) {
                east_out = ~north_in
                west_out = ~south_in
            }
            """.trimIndent(),
            "cardinal.dust",
        ).single()
        assertEquals(PhysicalIoEdge.NORTH, cardinal.ports.single { it.name == "north_in" }.edge)
        assertEquals(PhysicalIoEdge.SOUTH, cardinal.ports.single { it.name == "south_in" }.edge)
        assertEquals(PhysicalIoEdge.EAST, cardinal.ports.single { it.name == "east_out" }.edge)
        assertEquals(PhysicalIoEdge.WEST, cardinal.ports.single { it.name == "west_out" }.edge)

        val grouped = DustLanguage.compile(
            """
            module grouped_edges(
                #[edge(north)] input operands { a: bit, b: bit },
                #[edge(south)] output result { y: bit },
            ) {
                y = a ^ b
            }
            """.trimIndent(),
            "grouped-edges.dust",
        ).single()
        assertEquals(PhysicalIoEdge.NORTH, grouped.ioGroups.single { it.name == "operands" }.edge)
        assertEquals(PhysicalIoEdge.SOUTH, grouped.ioGroups.single { it.name == "result" }.edge)

        val nested = DustLanguage.compile(
            """
            module child(#[edge(east)] input a: bit, #[edge(west)] output y: bit) { y = ~a }
            module top(input a: bit, output y: bit) { let child_result = child(a) y = child_result.y }
            """.trimIndent(),
            "nested-edge.dust",
        ).single { it.name == "top" }
        assertTrue(nested.ports.all { it.edge == null })
    }

    @Test
    fun `placement attribute diagnostics reject invalid edge forms`() {
        val badEdge = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(#[edge(up)] input a: bit, output y: bit) { y = ~a }",
                "bad-edge.dust",
            )
        }
        assertTrue("invalid edge 'up'" in badEdge.message.orEmpty())

        val badArity = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(#[edge(north, south)] input a: bit, output y: bit) { y = ~a }",
                "bad-edge-arity.dust",
            )
        }
        assertTrue("#[edge] expects one of north, south, east, west" in badArity.message.orEmpty())

        val localEdge = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(input a: bit, output y: bit) { #[edge(north)] let p = ~a y = p }",
                "local-edge.dust",
            )
        }
        assertTrue("#[edge] is only supported on top-level I/O" in localEdge.message.orEmpty())
    }

    @Test
    fun `panel diagnostics require named north south io groups`() {
        val ungrouped = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(#[panel] input a: bit, output y: bit) { y = ~a }",
                "panel-ungrouped.dust",
            )
        }
        assertTrue("#[panel] requires a named top-level I/O group" in ungrouped.message.orEmpty())

        val arguments = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(#[panel(foo)] input operands { a: bit }, output y: bit) { y = ~a }",
                "panel-arguments.dust",
            )
        }
        assertTrue("#[panel] does not take arguments" in arguments.message.orEmpty())

        val east = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(#[panel] #[edge(east)] input operands { a: bit }, output y: bit) { y = ~a }",
                "panel-east.dust",
            )
        }
        assertTrue("#[panel] currently supports north/south edges" in east.message.orEmpty())

        val local = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module invalid(input a: bit, output y: bit) { #[panel] let p = ~a y = p }",
                "panel-local.dust",
            )
        }
        assertTrue("#[panel] is only supported on top-level I/O groups" in local.message.orEmpty())
    }

    @Test
    fun `modules compose through named output bundles`() {
        val modules = DustLanguage.compile(
            """
            module half_adder(
                input a: bit,
                input b: bit,
                output sum: bit,
                output carry: bit,
            ) {
                sum = a ^ b
                carry = a & b
            }

            module pair(
                input a: bit,
                input b: bit,
                output sum: bit,
                output carry: bit,
            ) {
                let stage = half_adder(a, b)
                sum = stage.sum
                carry = stage.carry
            }
            """.trimIndent(),
            "modules.dust",
        )
        val pair = modules.single { it.name == "pair" }

        assertEquals(false, pair.evaluate("a" to 1uL, "b" to 1uL).bit("sum"))
        assertEquals(true, pair.evaluate("a" to 1uL, "b" to 1uL).bit("carry"))
        assertEquals(2, pair.lowerToBooleanNetlist().gates.size)
    }

    @Test
    fun `diagnostics include source line and caret`() {
        val error = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module broken(input a: bit, output y: bit) { let unused = a }",
                "broken.dust",
            )
        }

        assertTrue("broken.dust:1:" in error.message.orEmpty())
        assertTrue("unused expression" !in error.message.orEmpty())
        assertTrue("unassigned output y" in error.message.orEmpty())
        assertTrue("^" in error.message.orEmpty())
    }

    private companion object {
        val ADDER: String = """
            module adder4(
                input operands {
                    a: bits<4>,
                    b: bits<4>,
                    cin: bit,
                },
                output result {
                    sum: bits<4>,
                    cout: bit,
                },
            ) {
                let mut carry = cin
                for i in 0..4 {
                    let p = a[i] ^ b[i]
                    sum[i] = p ^ carry
                    carry = (a[i] & b[i]) | (p & carry)
                }
                cout = carry
            }
        """.trimIndent()
    }
}
