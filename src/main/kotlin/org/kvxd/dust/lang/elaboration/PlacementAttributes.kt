package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.netlist.InterfaceEdge

internal data class PlacementAttributes(
    val tier: Int?,
    val near: List<String>,
    val edge: InterfaceEdge?,
    val panel: Boolean,
)
