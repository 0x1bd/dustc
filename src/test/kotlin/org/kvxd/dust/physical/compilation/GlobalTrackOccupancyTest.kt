package org.kvxd.dust.physical.compilation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GlobalTrackOccupancyTest {
    @Test
    fun `indexed conflicts match pairwise conflicts`() {
        val isolation = 2
        val bandGuard = 1
        val occupancy = GlobalTrackOccupancy(isolation, bandGuard)
        val placed = mutableListOf<Pair<IntRange, IntRange>>()
        val random = Random(37)
        repeat(2_000) {
            val band = random.nextInt(30)
            val bandSpan = band..random.nextInt(band, 30)
            val column = random.nextInt(300)
            val footprint = column..random.nextInt(column, 310)
            val expected = placed.any { (placedBands, placedColumns) ->
                bandSpan.overlaps(placedBands, bandGuard) && footprint.overlaps(placedColumns, isolation)
            }
            assertEquals(expected, occupancy.conflicts(bandSpan, footprint))
            if (!expected) {
                occupancy.add(bandSpan, footprint)
                placed += bandSpan to footprint
            }
        }
    }

    private fun IntRange.overlaps(other: IntRange, guard: Int): Boolean =
        first <= other.last + guard && other.first <= last + guard
}
