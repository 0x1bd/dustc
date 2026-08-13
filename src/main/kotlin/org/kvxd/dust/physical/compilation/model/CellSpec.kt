package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.technology.StandardCell

internal data class CellSpec(
    val name: String,
    val cell: StandardCell,
    val nets: Map<String, Signal>,
    val index: Int,
    val forcedTier: Int? = null,
    val nearSignals: Set<Signal> = emptySet(),
    val forcedEdge: InterfaceEdge? = null,
    val panel: Boolean = false,
    val exclusiveRow: Boolean = false,
)
