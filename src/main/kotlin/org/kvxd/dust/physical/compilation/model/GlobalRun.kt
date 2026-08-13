package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class GlobalRun(
    val signal: Signal,
    val track: GlobalTrack,
    val starts: List<GlobalStart>,
    val taps: List<GlobalTap>,
    val viaPolicy: ViaPolicy,
)
