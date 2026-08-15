package org.kvxd.dust.physical.compilation

import kotlin.math.abs
import org.kvxd.dust.physical.compilation.model.GlobalSinkPoint
import org.kvxd.dust.physical.compilation.model.STEINER_ROW_COST
import org.kvxd.dust.physical.compilation.model.STEINER_TRACK_COST

internal class GlobalPartitionCosts(
    private val driverRow: Int,
    private val driverX: Int,
    private val sinks: List<GlobalSinkPoint>,
) {
    private val prefixXs = LongArray(sinks.size + 1)
    private val driverInsertion = sinks.indexOfFirst { it.x >= driverX }.let { if (it < 0) sinks.size else it }
    private val costs = Array(sinks.size) { LongArray(sinks.size + 1) }

    init {
        sinks.indices.forEach { index -> prefixXs[index + 1] = prefixXs[index] + sinks[index].x }
        for (start in sinks.indices) {
            var firstRow = driverRow
            var lastRow = driverRow
            for (end in start + 1..sinks.size) {
                val sink = sinks[end - 1]
                firstRow = minOf(firstRow, sink.row)
                lastRow = maxOf(lastRow, sink.row)
                costs[start][end] = horizontalCost(start, end) +
                        (lastRow - firstRow).toLong() * STEINER_ROW_COST + STEINER_TRACK_COST
            }
        }
    }

    fun cost(start: Int, end: Int): Long {
        require(start in sinks.indices && end in start + 1..sinks.size)
        return costs[start][end]
    }

    private fun horizontalCost(start: Int, end: Int): Long {
        val insertion = driverInsertion.coerceIn(start, end)
        val medianRank = (end - start + 1) / 2
        val insertionRank = insertion - start
        if (medianRank == insertionRank) {
            val left = driverX.toLong() * (insertion - start) - (prefixXs[insertion] - prefixXs[start])
            val right = (prefixXs[end] - prefixXs[insertion]) - driverX.toLong() * (end - insertion)
            return left + right
        }
        val medianIndex = if (medianRank < insertionRank) start + medianRank else start + medianRank - 1
        val median = sinks[medianIndex].x
        val left = median.toLong() * (medianIndex - start) - (prefixXs[medianIndex] - prefixXs[start])
        val right = (prefixXs[end] - prefixXs[medianIndex + 1]) - median.toLong() * (end - medianIndex - 1)
        return left + right + abs(driverX - median)
    }
}
