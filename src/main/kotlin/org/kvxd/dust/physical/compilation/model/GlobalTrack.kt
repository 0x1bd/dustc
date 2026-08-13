package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

internal data class GlobalTrack(
    val signal: Signal,
    val ordinal: Int,
    val driverRow: Int,
    val sinkKeys: Set<GlobalSinkKey>,
    val sinkRows: Set<Int>,
    val trunkX: Int,
    val viaX: Int,
    val planeY: Int,
    val tierCount: Int,
) {
    fun viaXForTier(tier: Int): Int = viaX + tier * GLOBAL_TIER_VIA_PITCH

    val footprint: IntRange = minOf(trunkX, viaX)..maxOf(trunkX, viaXForTier(tierCount - 1))
    val rowSpan: IntRange = minOf(driverRow, sinkRows.min())..maxOf(driverRow, sinkRows.max())
}
