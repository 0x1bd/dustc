package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class Abutment(
    val signal: Signal,
    val columns: IntRange,
    val y: Int,
    val z: Int,
    val sinkX: Int,
)
