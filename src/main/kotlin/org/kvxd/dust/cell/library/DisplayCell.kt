package org.kvxd.dust.cell.library

import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.Signal

interface DisplayCell {
    fun validate(dimensions: DisplayDimensions)

    fun instantiate(
        library: CellLibrary,
        builder: BooleanNetlistBuilder,
        name: String,
        dimensions: DisplayDimensions,
        inputs: Inputs,
    )

    data class Inputs(
        val x: List<Signal>,
        val y: List<Signal>,
        val pixelValue: Signal,
        val plot: Signal,
        val plotAll: Signal,
    )
}
