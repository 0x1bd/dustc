package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class AbutmentSeam(
    val y: Int,
    val z: Int,
    val signal: Signal,
)
