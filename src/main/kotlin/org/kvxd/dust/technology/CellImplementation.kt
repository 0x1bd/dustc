package org.kvxd.dust.technology

import org.kvxd.dust.netlist.InterfaceEdge

sealed interface CellImplementation {
    data object Standard : CellImplementation

    data class HardMacro(
        val exclusiveRow: Boolean = true,
        val visibleEdge: InterfaceEdge? = InterfaceEdge.NORTH,
    ) : CellImplementation
}
