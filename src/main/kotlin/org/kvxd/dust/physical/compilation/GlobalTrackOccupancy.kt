package org.kvxd.dust.physical.compilation

import java.util.BitSet

internal class GlobalTrackOccupancy(
    private val isolation: Int,
    private val rowGuard: Int,
) {
    private val columnsByBand = mutableMapOf<Int, BitSet>()

    fun conflicts(bandSpan: IntRange, footprint: IntRange): Boolean =
        bandSpan.any { band -> columnsByBand[band]?.hasSetBitIn(footprint) == true }

    fun add(bandSpan: IntRange, footprint: IntRange) {
        val firstColumn = (footprint.first - isolation).coerceAtLeast(0)
        val lastColumnExclusive = footprint.last + isolation + 1
        val firstBand = (bandSpan.first - rowGuard).coerceAtLeast(0)
        val lastBand = bandSpan.last + rowGuard
        for (band in firstBand..lastBand) {
            columnsByBand.getOrPut(band, ::BitSet).set(firstColumn, lastColumnExclusive)
        }
    }

    private fun BitSet.hasSetBitIn(range: IntRange): Boolean {
        val column = nextSetBit(range.first)
        return column >= 0 && column <= range.last
    }
}
