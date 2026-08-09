package org.kvxd.dust.netlist

enum class InterfaceEdge {
    NORTH,
    SOUTH,
    EAST,
    WEST,
}

data class SignalPlacement(
    val tier: Int? = null,
    val near: Set<Signal> = emptySet(),
    val edge: InterfaceEdge? = null,
)
