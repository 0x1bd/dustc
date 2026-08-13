package org.kvxd.dust.netlist

data class SignalPlacement(
    val tier: Int? = null,
    val near: Set<Signal> = emptySet(),
    val edge: InterfaceEdge? = null,
)
