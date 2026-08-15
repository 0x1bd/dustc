package org.kvxd.dust.physical.compilation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalTrackOccupancyTest {
    @Test
    fun `indexed conflicts match pairwise conflicts`() {
        listOf(false, true).forEach { folded ->
            val isolation = 2
            val rowGuard = 1
            val occupancy = GlobalTrackOccupancy(folded, isolation, rowGuard)
            val placed = mutableListOf<Pair<IntRange, IntRange>>()
            val random = Random(if (folded) 71 else 37)
            repeat(2_000) {
                val row = random.nextInt(30)
                val rowSpan = row..random.nextInt(row, 30)
                val column = random.nextInt(300)
                val footprint = column..random.nextInt(column, 310)
                val expected = placed.any { (placedRows, placedColumns) ->
                    (folded || rowSpan.overlaps(placedRows, rowGuard)) &&
                        footprint.overlaps(placedColumns, isolation)
                }
                assertEquals(expected, occupancy.conflicts(rowSpan, footprint))
                if (!expected) {
                    occupancy.add(rowSpan, footprint)
                    placed += rowSpan to footprint
                }
            }
        }
    }

    private fun IntRange.overlaps(other: IntRange, guard: Int): Boolean =
        first <= other.last + guard && other.first <= last + guard
}
