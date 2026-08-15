package org.kvxd.dust.lang.elaboration.display

import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.DisplayCell
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.io.PhysicalIoEdge

internal class DisplayElaboration(private val cellLibrary: CellLibrary) {
    val outputEdge: PhysicalIoEdge
        get() = cellLibrary.displayOutputEdge()

    fun resolvePort(direction: PortDirection, width: Int, height: Int): DisplayDimensions {
        require(direction == PortDirection.OUTPUT) { "a display must be an output" }
        return cellLibrary.displayDimensions(width, height)
    }

    fun write(arguments: List<List<Signal>>): Write {
        require(arguments.size == WRITE_ARGUMENTS)
        require(arguments.drop(COORDINATE_ARGUMENTS).all { it.size == 1 }) {
            "display_write value, plot, and plot_all inputs must be bits"
        }
        return Write(
            x = arguments[0],
            y = arguments[1],
            pixelValue = arguments[2].single(),
            plot = arguments[3].single(),
            plotAll = arguments[4].single(),
        )
    }

    fun validateAssignment(dimensions: DisplayDimensions, write: Write, bit: Int?) {
        require(bit == null) { "a display output cannot be assigned by bit" }
        require(write.x.size == dimensions.xWidth && write.y.size == dimensions.yWidth) {
            "display<${dimensions.width}, ${dimensions.height}> needs ${dimensions.xWidth}-bit x and " +
                "${dimensions.yWidth}-bit y coordinates"
        }
    }

    fun validatePlacement(edge: InterfaceEdge?, panel: Boolean, tier: Int?, near: List<String>) {
        require(edge == null || edge.name == outputEdge.name) {
            "a display output faces the ${outputEdge.name.lowercase()} edge"
        }
        require(!panel && tier == null && near.isEmpty()) { "display output placement is automatic" }
    }

    fun instantiate(
        builder: BooleanNetlistBuilder,
        name: String,
        dimensions: DisplayDimensions,
        write: Write,
    ) {
        cellLibrary.instantiateDisplay(builder, name, dimensions, write.cellInputs())
    }

    data class Write(
        val x: List<Signal>,
        val y: List<Signal>,
        val pixelValue: Signal,
        val plot: Signal,
        val plotAll: Signal,
    ) {
        val signals: List<Signal> = x + y + pixelValue + plot + plotAll

        internal fun cellInputs(): DisplayCell.Inputs = DisplayCell.Inputs(x, y, pixelValue, plot, plotAll)
    }

    private companion object {
        const val COORDINATE_ARGUMENTS: Int = 2
        const val WRITE_ARGUMENTS: Int = 5
    }
}
