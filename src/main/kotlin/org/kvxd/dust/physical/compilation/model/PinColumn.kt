package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class PinColumn(val y: Int, val x: Int, val signal: Signal)
