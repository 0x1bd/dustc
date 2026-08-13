package org.kvxd.dust.technology.definition

internal data class CellDefinition(
    val name: String,
    val palette: List<CellPaletteEntry>,
    val pins: List<CellPinDefinition>,
    val layers: List<CellLayerDefinition>,
    val layout: List<CellLayoutEntry>,
)
