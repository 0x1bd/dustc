package org.kvxd.dust.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.definition.CellPort
import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.CellTypeId
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.DisplayCell
import org.kvxd.dust.cell.timing.CellTiming
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.physical.io.PhysicalIoEdge

class DisplayCellIntegrationTest {
    @Test
    fun `the cell library owns display support and lowering`() {
        val display = RecordingDisplayCell()
        val library = CellLibrary(emptyList(), display)
        val circuit = DustLanguage.compile(
            """
            module main(
                input x: bits<2>,
                input y: bits<3>,
                input value: bit,
                input plot: bit,
                input plot_all: bit,
                output screen: display<3, 5>,
            ) {
                screen = display_write(x, y, value, plot, plot_all)
            }
            """.trimIndent(),
            "custom-display.dust",
            cellLibrary = library,
        ).single()

        assertEquals(DisplayDimensions(3, 5), display.validated)
        assertEquals(DisplayDimensions(3, 5), display.instantiated)
        assertEquals(PhysicalIoEdge.SOUTH, circuit.displayOutputs.single().edge)
        assertSame(display.sink, circuit.lowerToBooleanNetlist().instances.single().type)
    }

    @Test
    fun `a library without a display implementation rejects display ports`() {
        val exception = assertFailsWith<DustCompileException> {
            DustLanguage.compile(
                "module main(input x: bits<3>, output screen: display<8, 8>) " +
                    "{ screen = display_write(x, x, false, false, false) }",
                "unsupported-display.dust",
                cellLibrary = CellLibrary(emptyList()),
            )
        }

        assertTrue("this cell library does not provide displays" in exception.message.orEmpty())
    }

    private class RecordingDisplayCell : DisplayCell {
        override val outputEdge: PhysicalIoEdge = PhysicalIoEdge.SOUTH

        val sink = CellType(
            CellTypeId("recording-display"),
            listOf(CellPort("pixel", 1, PortDirection.INPUT)),
            CellBehavior.Combinational { emptyMap() },
            CellTiming.NONE,
        )
        var validated: DisplayDimensions? = null
        var instantiated: DisplayDimensions? = null

        override fun validate(dimensions: DisplayDimensions) {
            validated = dimensions
        }

        override fun instantiate(
            library: CellLibrary,
            builder: BooleanNetlistBuilder,
            name: String,
            dimensions: DisplayDimensions,
            inputs: DisplayCell.Inputs,
        ) {
            instantiated = dimensions
            builder.instance(sink, mapOf("pixel" to listOf(inputs.pixelValue)), name = "output-$name")
        }
    }
}
