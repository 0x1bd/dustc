package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.geometry.BlockPos

internal sealed interface CellLayoutEntry {
    data class Include(
        val cell: String,
        val arguments: List<Int>,
        val origin: BlockPos,
    ) : CellLayoutEntry {
        constructor(cell: String, origin: BlockPos) : this(cell, emptyList(), origin)
    }

    data class Wire(val template: String, val position: BlockPos) : CellLayoutEntry
    data class Block(val paletteSymbol: Char, val position: BlockPos) : CellLayoutEntry
}
