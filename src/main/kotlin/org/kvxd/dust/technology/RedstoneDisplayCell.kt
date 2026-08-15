package org.kvxd.dust.technology

import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.DisplayCell
import org.kvxd.dust.cell.library.DisplayMatrixCell
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.io.PhysicalIoEdge

internal object RedstoneDisplayCell : DisplayCell {
    override val outputEdge: PhysicalIoEdge = PhysicalIoEdge.NORTH

    override fun validate(dimensions: DisplayDimensions) {
        require(dimensions.width in MINIMUM_DIMENSION..MAXIMUM_DIMENSION && dimensions.width % 2 == 0) {
            "display width must be even and between $MINIMUM_DIMENSION and $MAXIMUM_DIMENSION"
        }
        require(dimensions.height in MINIMUM_DIMENSION..MAXIMUM_DIMENSION && dimensions.height % 2 == 0) {
            "display height must be even and between $MINIMUM_DIMENSION and $MAXIMUM_DIMENSION"
        }
    }

    override fun instantiate(
        library: CellLibrary,
        builder: BooleanNetlistBuilder,
        name: String,
        dimensions: DisplayDimensions,
        inputs: DisplayCell.Inputs,
    ) {
        require(inputs.x.size == dimensions.xWidth)
        require(inputs.y.size == dimensions.yWidth)

        val selectedColumns = decode(builder, dimensions.width, inputs.x)
        val selectedRows = decode(builder, dimensions.height, inputs.y)
        val writableRows = selectedRows.map { selectedRow -> builder.and(inputs.plot, selectedRow) }
        val pixels = writableRows.flatMap { writableRow ->
            selectedColumns.map { selectedColumn ->
                val writePixel = builder.or(inputs.plotAll, builder.and(selectedColumn, writableRow))
                builder.latch(inputs.pixelValue, builder.not(writePixel))
            }
        }
        val cell = library.specialize(DisplayMatrixCell.NAME, listOf(dimensions.width, dimensions.height))
        builder.instance(
            cell.logicalType,
            mapOf("pixels" to pixels),
            name = "output-$name",
        )
    }

    private fun decode(builder: BooleanNetlistBuilder, width: Int, address: List<Signal>): List<Signal> {
        val inverted = address.map(builder::not)
        return List(width) { decoded ->
            address.indices.map { bit ->
                if (decoded and (1 shl bit) == 0) inverted[bit] else address[bit]
            }.reduce(builder::and)
        }
    }

    private const val MINIMUM_DIMENSION: Int = 8
    private const val MAXIMUM_DIMENSION: Int = 64
}
