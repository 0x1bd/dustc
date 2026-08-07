package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.compile
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockType
import org.kvxd.dust.device.SignBlockEntity

class DustLanguageTest {
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
    fun `io groups form lower input and upper output tiers`() {
        val module = DustLanguage.compile(
            """
            module panel(
                input controls {
                    enable: bit,
                    select: bit,
                },
                input data {
                    a: bit,
                    b: bit,
                },
                output indicators {
                    selected: bit,
                    active: bit,
                },
            ) {
                let chosen = mux(select, a, b)
                selected = chosen
                active = enable & chosen
            }
            """.trimIndent(),
            "panel.dust",
        ).single()
        val design = module.compile().physical
        val cells = design.cells.associateBy { it.name }
        val inputs = listOf("input-enable", "input-select", "input-a", "input-b").map { cells.getValue(it) }
        val outputs = listOf("output-selected", "output-active").map { cells.getValue(it) }
        assertEquals(1, (inputs + outputs).map { it.row }.distinct().size)
        assertEquals(listOf(0, 3, 6, 9), inputs.map { it.origin.x })
        assertEquals(listOf(0, 3), outputs.map { it.origin.x })
        assertEquals(inputs.take(outputs.size).map { it.origin.x }, outputs.map { it.origin.x })
        assertEquals(inputs.take(outputs.size).map { it.origin.z }, outputs.map { it.origin.z })
        assertTrue(inputs.maxOf { it.origin.x } < design.cells.filter { it.name.startsWith("gate-") }.minOf { it.origin.x })
        val inputLeverY = inputs.first().origin.y + 1
        val outputLampY = outputs.first().origin.y + 2
        assertEquals(inputLeverY + 1, outputLampY)
        outputs.forEachIndexed { index, output ->
            val input = inputs[index]
            val lever = design.inputs.getValue(input.name.removePrefix("input-"))
            val inputRoutePin = input.pin("y")
            val routePin = output.pin("a")
            val lamp = design.outputs.getValue(output.name.removePrefix("output-"))
            val routeSupport = routePin + BlockPos(0, -1, 0)

            assertEquals(lever + BlockPos(0, 2, 0), lamp)
            assertEquals(BlockType.REDSTONE_LAMP, design.matrix.blockAt(lamp).type)
            assertEquals(BlockType.REDSTONE_WIRE, design.matrix.blockAt(inputRoutePin).type)
            assertEquals(BlockType.REPEATER, design.matrix.blockAt(routePin).type)
            assertEquals(BlockType.REDSTONE_WIRE, design.matrix.blockAt(routePin + BlockPos(0, 0, 1)).type)
            assertEquals(inputRoutePin + BlockPos(0, 2, 0), routePin)
            assertTrue(
                design.routes.single { it.source == inputRoutePin }.routeBlocks.any { it.y == inputRoutePin.y },
                "input at $inputRoutePin has no lower-plane access route",
            )
            assertTrue(
                routePin + BlockPos(0, 0, 1) in design.routes.single { routePin in it.sinks }.routeBlocks,
                "output repeater at $routePin is not fed from its south input",
            )
            assertTrue(
                design.routes.single { routePin in it.sinks }.routeBlocks.any { it.y == routePin.y },
                "output at $routePin has no upper-plane return route",
            )
            assertTrue(lever.manhattanTo(routePin) > 1, "$lever can power output pin $routePin")
            assertTrue(lever.manhattanTo(routeSupport) > 1, "$lever can power output support $routeSupport")
        }
        val signs = design.matrix.blockEntities().values.filterIsInstance<SignBlockEntity>()
        assertEquals(6, signs.size)
        assertTrue(signs.any { it.lines == listOf("IN controls", "enable") })
        assertTrue(signs.any { it.lines == listOf("OUT indicators", "selected") })
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
