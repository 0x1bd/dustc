package org.kvxd.dust.technology.definition

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.technology.PinDirection

internal data class CellDefinition(
    val name: String,
    val palette: List<CellPaletteEntry>,
    val pins: List<CellPinDefinition>,
    val layers: List<CellLayerDefinition>,
    val layout: List<CellLayoutEntry>,
)

internal data class CellPaletteEntry(val symbol: Char, val template: String)

internal data class CellPinDefinition(
    val name: String,
    val direction: PinDirection,
    val position: BlockPos,
    val allowsHorizontalAbutment: Boolean,
    val accessesFromSouth: Boolean,
    val branchOffsetX: Int,
    val driveStrength: Int,
    val requiredStrength: Int,
)

internal data class CellLayerDefinition(val y: Int, val rows: List<String>)

internal sealed interface CellLayoutEntry {
    data class Include(val cell: String, val origin: BlockPos) : CellLayoutEntry
    data class Wire(val template: String, val position: BlockPos) : CellLayoutEntry
}
