package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.library.BuiltinCells
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.CellProvider
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.compile
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.CellPin
import org.kvxd.dust.technology.CellSize
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneBlocks
import org.kvxd.dust.technology.StandardCell

class DustParameterizationTest {
    @Test
    fun `generic modules specialize widths loops arithmetic and nested calls`() {
        val source = """
            module invert<const WIDTH: int>(
                input a: bits<WIDTH>,
                output y: bits<WIDTH>,
            ) {
                for i in 0..WIDTH { y[i] = ~a[i] }
            }

            module twice<const WIDTH: int = 4>(
                input a: bits<WIDTH>,
                output y: bits<WIDTH>,
            ) {
                let result = invert<clog2(WIDTH * WIDTH) + WIDTH - clog2(WIDTH * WIDTH)>(a)
                y = result.y
            }
        """.trimIndent()

        val four = DustLanguage.compile(source, "generic.dust").single()
        assertEquals(4, four.inputs.single().width)
        assertEquals(0b0101uL, four.evaluate("a" to 0b1010uL)["y"])

        val eight = DustLanguage.compile(
            source,
            "generic.dust",
            parameters = mapOf("WIDTH" to 8),
            moduleName = "twice",
        ).single()
        assertEquals(8, eight.outputs.single().width)
        assertEquals(0x55uL, eight.evaluate("a" to 0xaauL)["y"])

        val three = DustLanguage.compile(
            source,
            "generic.dust",
            parameters = mapOf("WIDTH" to 3),
            moduleName = "twice",
        ).single()
        assertEquals(3, three.outputs.single().width)
        assertEquals(0b010uL, three.evaluate("a" to 0b101uL)["y"])
    }

    @Test
    fun `boolean and bus constants share physical source cells`() {
        val circuit = DustLanguage.compile(
            """
            module constants<const WIDTH: int = 5>(
                input a: bit,
                output low: bits<WIDTH>,
                output high: bits<WIDTH>,
                output mixed: bit,
            ) {
                low = const_bits<WIDTH>(0)
                high = const_bits<WIDTH>(WIDTH * WIDTH + 6)
                mixed = (a & true) | false
            }
            """.trimIndent(),
            "constants.dust",
        ).single()

        val netlist = circuit.lowerToBooleanNetlist()
        assertEquals(1, netlist.instances.count { it.type === BuiltinCells.constantLow })
        assertEquals(1, netlist.instances.count { it.type === BuiltinCells.constantHigh })
        val result = circuit.evaluate("a" to 1uL)
        assertEquals(0uL, result["low"])
        assertEquals(31uL, result["high"])
        assertTrue(result.bit("mixed"))
        val physical = circuit.compile().physical
        assertEquals(1, physical.cells.count { it.cell.logicalType === BuiltinCells.constantLow })
        assertEquals(1, physical.cells.count { it.cell.logicalType === BuiltinCells.constantHigh })
    }

    @Test
    fun `parameter diagnostics cover missing duplicate range overflow and recursion`() {
        assertDiagnostic("module m<const WIDTH: int>(input a: bit, output y: bit) { y = a }", "needs parameter 'WIDTH'")
        assertDiagnostic(
            "module m<const WIDTH: int, const WIDTH: int>(input a: bit, output y: bit) { y = a }",
            "duplicate module parameter 'WIDTH'",
        )
        assertDiagnostic(
            "module m<const WIDTH: int = 65>(input a: bits<WIDTH>, output y: bits<WIDTH>) { y = a }",
            "bus width must be between 1 and 64",
        )
        assertDiagnostic(
            "module m<const WIDTH: int = 2147483647>(input a: bit, output y: bit) { " +
                "for i in 0..WIDTH + 1 { y = a } }",
            "integer expression overflows",
        )
        assertDiagnostic(
            "module m<const WIDTH: int = 1>(input a: bit, output y: bit) { " +
                "let next = m<WIDTH + 1>(a) y = next.y }",
            "recursive module specialization",
        )
    }

    @Test
    fun `outputless library cells are legal statements while produced values remain illegal`() {
        val sink = CellType(
            CellTypeId("sink"),
            listOf(CellPort("a", 1, PortDirection.INPUT)),
            CellBehavior.Combinational { emptyMap() },
            CellTiming.NONE,
        )
        val sinkCell = StandardCell(
            "sink",
            sink,
            CellSize(1, 2, 1),
            listOf(CellPin("a", PinDirection.INPUT, BlockPos(0, 1, 0))),
            listOf(
                BlockPos(0, 0, 0) to RedstoneBlocks.cellSupport,
                BlockPos(0, 1, 0) to RedstoneBlocks.dust,
            ),
        )
        val library = CellLibrary(listOf(CellProvider.fixed(sink, physicalView = { sinkCell })))
        val circuit = DustLanguage.compile(
            "module consume(input a: bit, output y: bit) { sink(a) y = a }",
            "sink.dust",
            cellLibrary = library,
        ).single()
        assertEquals(1, circuit.lowerToBooleanNetlist().instances.size)
        assertEquals(true, circuit.evaluate("a" to 1uL).bit("y"))

        assertDiagnostic(
            "module unused(input a: bit, output y: bit) { not(a) y = a }",
            "unused expression",
        )
    }

    @Test
    fun `source modules cannot shadow bundled library cells`() {
        assertDiagnostic(
            "module latch(input a: bit, output y: bit) { y = a }",
            "ambiguous with a bundled library cell",
        )
    }

    private fun assertDiagnostic(source: String, message: String) {
        val exception = assertFailsWith<DustCompileException> { DustLanguage.compile(source, "invalid.dust") }
        assertTrue(message in exception.message.orEmpty(), exception.message)
    }
}
