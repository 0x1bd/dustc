package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class GlobalTrackRequest(
    val signal: Signal,
    val ordinal: Int,
    val driverRow: Int,
    val sinkKeys: Set<GlobalSinkKey>,
    val sinkRows: Set<Int>,
    val bandSpan: IntRange,
    val preferredX: Int,
)
