package org.kvxd.dust.physical.compilation

import java.util.BitSet

internal class GlobalTrackOccupancy(
    private val folded: Boolean,
    private val isolation: Int,
    private val rowGuard: Int,
) {
    private val foldedColumns = BitSet()
    private val columnsByRow = mutableMapOf<Int, BitSet>()

    fun conflicts(rowSpan: IntRange, footprint: IntRange): Boolean {
        if (folded) return foldedColumns.hasSetBitIn(footprint)
        return rowSpan.any { row -> columnsByRow[row]?.hasSetBitIn(footprint) == true }
    }

    fun add(rowSpan: IntRange, footprint: IntRange) {
        val firstColumn = (footprint.first - isolation).coerceAtLeast(0)
        val lastColumnExclusive = footprint.last + isolation + 1
        if (folded) {
            foldedColumns.set(firstColumn, lastColumnExclusive)
            return
        }
        val firstRow = (rowSpan.first - rowGuard).coerceAtLeast(0)
        val lastRow = rowSpan.last + rowGuard
        for (row in firstRow..lastRow) {
            columnsByRow.getOrPut(row, ::BitSet).set(firstColumn, lastColumnExclusive)
        }
    }

    private fun BitSet.hasSetBitIn(range: IntRange): Boolean {
        val column = nextSetBit(range.first)
        return column >= 0 && column <= range.last
    }
}
