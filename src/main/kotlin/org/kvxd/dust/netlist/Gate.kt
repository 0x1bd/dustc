package org.kvxd.dust.netlist

data class Gate(
    val primitive: Primitive,
    val inputs: List<Signal>,
    val output: Signal,
)
