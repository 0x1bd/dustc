package org.kvxd.dust.technology.definition

import org.kvxd.dust.netlist.InterfaceEdge

internal data class CellPlacementDefinition(
    val exclusiveRow: Boolean = true,
    val visibleEdge: InterfaceEdge? = InterfaceEdge.NORTH,
)
