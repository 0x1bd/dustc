package org.kvxd.dust.technology.definition

import org.kvxd.dust.cell.library.CellParameter
import org.kvxd.dust.cell.timing.CellTiming

internal data class CellDefinition(
    val name: String,
    val parameters: List<CellParameter> = emptyList(),
    val arguments: Map<String, Int> = emptyMap(),
    val palette: List<CellPaletteEntry>,
    val pins: List<CellPinDefinition>,
    val layers: List<CellLayerDefinition>,
    val layout: List<CellLayoutEntry>,
    val blockEntities: List<CellBlockEntityDefinition> = emptyList(),
    val observations: List<CellObservationDefinition> = emptyList(),
    val timing: CellTiming? = null,
    val placement: CellPlacementDefinition? = null,
    val sourceLocations: Map<String, CellSourceLocation> = emptyMap(),
)
