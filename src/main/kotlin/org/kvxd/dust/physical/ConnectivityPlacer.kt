package org.kvxd.dust.physical

internal class ConnectivityPlacer(

    private val cellWidths: IntArray,

    private val netCells: List<IntArray>,
    private val netWeights: IntArray,

    private val netTouchesInput: BooleanArray,

    private val netTouchesOutput: BooleanArray,

    private val rowPitch: Int = DEFAULT_ROW_PITCH,

    private val lanePitch: Int = DEFAULT_LANE_PITCH,
) {
    private val cellNets: Array<IntArray> = buildCellNets()

    fun refine(start: List<List<Int>>): List<List<Int>> {
        var rows = start
        var best = cost(rows)
        repeat(PASSES) {
            var improved = false

            rows.indices.forEach { row ->
                val candidate = rows.toMutableList()
                candidate[row] = orderedByPull(rows, row)
                val cost = cost(candidate)
                if (cost < best) {
                    rows = candidate
                    best = cost
                    improved = true
                }
            }

            rows.indices.forEach { row ->
                val candidate = migrate(rows, row)
                if (candidate != null) {
                    val cost = cost(candidate)
                    if (cost < best) {
                        rows = candidate
                        best = cost
                        improved = true
                    }
                }
            }
            if (!improved) return rows
        }
        return rows
    }

    private fun orderedByPull(rows: List<List<Int>>, row: Int): List<Int> {
        val xOf = columnOf(rows)
        val pull = HashMap<Int, Double>()
        rows[row].forEach { cell ->
            val samples = mutableListOf<Int>()
            cellNets[cell].forEach { net ->
                netCells[net].forEach { other -> if (other != cell) samples += xOf[other] }
            }
            pull[cell] = median(samples) ?: xOf[cell].toDouble()
        }
        return rows[row].sortedWith(compareBy({ pull.getValue(it) }, { it }))
    }

    private fun migrate(rows: List<List<Int>>, row: Int): List<List<Int>>? {
        val rowOf = IntArray(cellWidths.size)
        rows.forEachIndexed { index, cells -> cells.forEach { rowOf[it] = index } }

        fun wantedRow(cell: Int): Int {
            val samples = mutableListOf<Int>()
            cellNets[cell].forEach { net ->
                netCells[net].forEach { other -> if (other != cell) samples += rowOf[other] }
                if (netTouchesInput[net]) samples += -1
                if (netTouchesOutput[net]) samples += rows.size
            }
            return (median(samples) ?: rowOf[cell].toDouble()).toInt().coerceIn(0, rows.size - 1)
        }

        val leaving = rows[row].firstOrNull { wantedRow(it) != row } ?: return null
        val target = wantedRow(leaving)

        val incoming = rows[target].minByOrNull { candidate ->
            val wrongness = if (wantedRow(candidate) == row) 0 else 1
            wrongness * 1000 + kotlin.math.abs(cellWidths[candidate] - cellWidths[leaving])
        } ?: return null

        val moved = rows.map { it.toMutableList() }
        moved[row].remove(leaving)
        moved[target].remove(incoming)
        moved[row] += incoming
        moved[target] += leaving
        return moved
    }

    private fun columnOf(rows: List<List<Int>>): IntArray {
        val xOf = IntArray(cellWidths.size)
        rows.forEach { cells ->
            var x = 0
            cells.forEach { cell ->
                xOf[cell] = x + cellWidths[cell] / 2
                x += cellWidths[cell]
            }
        }
        return xOf
    }

    private fun cost(rows: List<List<Int>>): Long {
        val rowOf = IntArray(cellWidths.size)
        val xOf = IntArray(cellWidths.size)
        val rowWidth = LongArray(rows.size)
        rows.forEachIndexed { index, cells ->
            var x = 0
            cells.forEach { cell ->
                rowOf[cell] = index
                xOf[cell] = x + cellWidths[cell] / 2
                x += cellWidths[cell]
            }
            rowWidth[index] = x.toLong()
        }

        var total = 0L
        val intervalsByRow = Array(rows.size) { mutableListOf<IntArray>() }
        val pinsByRow = LongArray(rows.size)
        netCells.forEachIndexed { net, cells ->
            if (cells.isEmpty()) return@forEachIndexed
            val weight = netWeights[net].toLong()
            var lowestRow = Int.MAX_VALUE
            var highestRow = Int.MIN_VALUE
            val extentByRow = HashMap<Int, IntArray>()
            cells.forEach { cell ->
                val row = rowOf[cell]
                lowestRow = minOf(lowestRow, row)
                highestRow = maxOf(highestRow, row)
                val extent = extentByRow.getOrPut(row) { intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, 0) }
                extent[0] = minOf(extent[0], xOf[cell])
                extent[1] = maxOf(extent[1], xOf[cell])
                extent[2]++
            }
            val spansRows = highestRow > lowestRow || netTouchesInput[net] || netTouchesOutput[net]
            extentByRow.forEach { (row, extent) ->
                val east = if (spansRows) rowWidth[row].toInt() else extent[1]
                total += weight * (east - extent[0])
                intervalsByRow[row] += intArrayOf(extent[0], east)
                pinsByRow[row] += extent[2]
            }
            total += weight * (highestRow - lowestRow).toLong() * rowPitch
        }

        rows.indices.forEach { row ->
            total += pinsByRow[row] * deepestOverlap(intervalsByRow[row]) * lanePitch
        }
        return total
    }

    private fun deepestOverlap(intervals: List<IntArray>): Long {
        if (intervals.isEmpty()) return 0
        val starts = intervals.map { it[0] }.sorted()
        val ends = intervals.map { it[1] }.sorted()
        var open = 0
        var deepest = 0
        var next = 0
        starts.forEach { start ->
            while (next < ends.size && ends[next] < start) {
                open--
                next++
            }
            open++
            deepest = maxOf(deepest, open)
        }
        return deepest.toLong()
    }

    private fun median(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun buildCellNets(): Array<IntArray> {
        val counts = IntArray(cellWidths.size)
        netCells.forEach { cells -> cells.forEach { counts[it]++ } }
        val result = Array(cellWidths.size) { IntArray(counts[it]) }
        val filled = IntArray(cellWidths.size)
        netCells.forEachIndexed { net, cells ->
            cells.forEach { cell -> result[cell][filled[cell]++] = net }
        }
        return result
    }

    private companion object {

        const val PASSES = 8

        const val DEFAULT_ROW_PITCH = 40

        const val DEFAULT_LANE_PITCH = 3

    }
}
