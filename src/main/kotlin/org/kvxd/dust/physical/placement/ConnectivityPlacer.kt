package org.kvxd.dust.physical.placement

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.max

internal class ConnectivityPlacer(
    private val cellWidths: IntArray,
    private val netEndpointCells: Array<IntArray>,
    private val netEndpointXs: Array<IntArray>,
    private val netWeights: IntArray,
    private val netDriverCells: IntArray,
    private val netTouchesInput: BooleanArray,
    private val netTouchesOutput: BooleanArray,
    private val cellGap: Int,
    private val rowPitch: Int = DEFAULT_ROW_PITCH,
    private val lanePitch: Int = DEFAULT_LANE_PITCH,
) {
    private val cellCount = cellWidths.size
    private val netCount = netEndpointCells.size
    private val netCells = Array(netCount) { net -> netEndpointCells[net].distinct().toIntArray() }
    private val cellNets = buildCellNets()
    private val cellEndpoints = buildCellEndpoints()
    private val weightedDegree = LongArray(cellCount) { cell ->
        cellNets[cell].sumOf { net ->
            netWeights[net].toLong() * max(1, netCells[net].size - 1)
        }
    }
    private val inputAffinity = LongArray(cellCount) { cell ->
        cellNets[cell].sumOf { net -> if (netTouchesInput[net]) netWeights[net].toLong() else 0L }
    }
    private val outputAffinity = LongArray(cellCount) { cell ->
        cellNets[cell].sumOf { net -> if (netTouchesOutput[net]) netWeights[net].toLong() else 0L }
    }
    private val cellsByDegree = (0 until cellCount).sortedWith(
        compareByDescending<Int> { weightedDegree[it] }
            .thenByDescending { cellWidths[it] }
            .thenBy { it },
    )

    init {
        require(cellCount > 0)
        require(netEndpointXs.size == netCount)
        require(netWeights.size == netCount)
        require(netDriverCells.size == netCount)
        require(netDriverCells.all { it in -1 until cellCount })
        require(netTouchesInput.size == netCount)
        require(netTouchesOutput.size == netCount)
        require(netEndpointCells.indices.all { netEndpointCells[it].size == netEndpointXs[it].size })
        require(netEndpointCells.indices.all { net -> netEndpointCells[net].all { it in 0 until cellCount } })
    }

    fun place(rowCount: Int, maximumCandidates: Int = MAX_OUTPUT_CANDIDATES): List<List<List<Int>>> {
        require(rowCount in 1..cellCount)
        require(maximumCandidates > 0)
        val candidates = LinkedHashMap<String, Candidate>()
        seedOrders().forEach { order ->
            var rows = partition(order, rowCount)
            val state = PartitionState(rows)
            refineMoves(state)
            refineSwaps(state)
            refineRowOrder(state)
            rows = rowsFromState(state, order)
            rows = refineHorizontal(rows)
            val immutable = rows.map { it.toList() }
            val signature = signature(immutable)
            val score = score(immutable)
            val previous = candidates[signature]
            if (previous == null || score < previous.score) candidates[signature] = Candidate(immutable, score)
        }
        return candidates.values
            .sortedWith(compareBy<Candidate>({ it.score }, { signature(it.rows) }))
            .take(maximumCandidates)
            .map { it.rows }
    }

    private fun seedOrders(): List<IntArray> {
        val anchors = linkedSetOf<Int>()
        anchors += bestCell { weightedDegree[it] }
        anchors += bestCell { inputAffinity[it] * 4 + weightedDegree[it] }
        anchors += bestCell { outputAffinity[it] * 4 + weightedDegree[it] }
        anchors += bestCell { cellWidths[it].toLong() * 16 + weightedDegree[it] }

        val orders = mutableListOf<IntArray>()
        anchors.forEach { anchor ->
            orders += traversalOrder(anchor, false)
            orders += traversalOrder(anchor, true)
        }
        orders += IntArray(cellCount) { it }
        orders += IntArray(cellCount) { cellCount - 1 - it }
        return orders.distinctBy { it.contentToString() }
    }

    private fun bestCell(score: (Int) -> Long): Int = (0 until cellCount).maxWith(
        compareBy<Int> { score(it) }
            .thenBy { weightedDegree[it] }
            .thenBy { cellWidths[it] }
            .thenByDescending { it },
    )

    private fun traversalOrder(anchor: Int, reverseTie: Boolean): IntArray {
        val visitedCells = BooleanArray(cellCount)
        val queuedCells = BooleanArray(cellCount)
        val expandedNets = BooleanArray(netCount)
        val result = IntArray(cellCount)
        var filled = 0
        val queue = PriorityQueue<Int> { left, right ->
            val degree = weightedDegree[right].compareTo(weightedDegree[left])
            if (degree != 0) {
                degree
            } else {
                val width = cellWidths[right].compareTo(cellWidths[left])
                if (width != 0) width else if (reverseTie) right.compareTo(left) else left.compareTo(right)
            }
        }

        fun enqueue(cell: Int) {
            if (!visitedCells[cell] && !queuedCells[cell]) {
                queuedCells[cell] = true
                queue += cell
            }
        }

        enqueue(anchor)
        while (filled < cellCount) {
            if (queue.isEmpty()) {
                val next = cellsByDegree.first { !visitedCells[it] }
                enqueue(next)
            }
            val cell = queue.remove()
            queuedCells[cell] = false
            if (visitedCells[cell]) continue
            visitedCells[cell] = true
            result[filled++] = cell

            for (net in cellNets[cell].sortedWith(compareByDescending<Int> { netWeights[it] }.thenBy { it })) {
                if (expandedNets[net]) continue
                expandedNets[net] = true
                val connected = if (reverseTie) {
                    netCells[net].sortedWith(
                        compareByDescending<Int> { weightedDegree[it] }
                            .thenByDescending { it },
                    )
                } else {
                    netCells[net].sortedWith(
                        compareByDescending<Int> { weightedDegree[it] }
                            .thenBy { it },
                    )
                }
                connected.forEach(::enqueue)
            }
        }
        return result
    }

    private fun partition(order: IntArray, rowCount: Int): Array<MutableList<Int>> {
        val rows = Array(rowCount) { mutableListOf<Int>() }
        var cursor = 0
        var remainingWidth = order.sumOf { cellWidths[it].toLong() }
        for (row in 0 until rowCount) {
            val remainingRows = rowCount - row
            val target = (remainingWidth + remainingRows - 1) / remainingRows
            var width = 0L
            while (cursor < order.size) {
                val remainingCells = order.size - cursor
                if (rows[row].isNotEmpty() && remainingCells == remainingRows - 1) break
                val cell = order[cursor]
                val nextWidth = width + cellWidths[cell]
                if (rows[row].isNotEmpty() && remainingRows > 1) {
                    val without = abs(width - target)
                    val with = abs(nextWidth - target)
                    if (without <= with) break
                }
                rows[row] += cell
                width = nextWidth
                cursor++
            }
            if (rows[row].isEmpty()) {
                val cell = order[cursor++]
                rows[row] += cell
                width += cellWidths[cell]
            }
            remainingWidth -= width
        }
        require(cursor == order.size)
        return rows
    }

    private fun refineMoves(state: PartitionState) {
        repeat(PARTITION_MOVE_PASSES) {
            var changed = false
            for (cell in cellsByDegree) {
                val from = state.rowOf[cell]
                if (state.rowCounts[from] <= 1) continue
                val targets = candidateRows(cell, state)
                var bestRow = from
                var bestDelta = 0L
                for (to in targets) {
                    if (to == from) continue
                    val delta = moveDelta(state, cell, to)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestRow = to
                    }
                }
                if (bestRow != from) {
                    applyMove(state, cell, bestRow)
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    private fun candidateRows(cell: Int, state: PartitionState): IntArray {
        val selected = BooleanArray(state.rowCount)
        val from = state.rowOf[cell]
        selected[from] = true
        if (from > 0) selected[from - 1] = true
        if (from + 1 < state.rowCount) selected[from + 1] = true
        cellNets[cell].forEach { net ->
            netCells[net].forEach { other -> selected[state.rowOf[other]] = true }
        }
        return selected.indices.filter { selected[it] }.toIntArray()
    }

    private fun moveDelta(state: PartitionState, cell: Int, to: Int): Long {
        val from = state.rowOf[cell]
        if (from == to || state.rowCounts[from] <= 1) return Long.MAX_VALUE
        val width = cellWidths[cell]
        val nextTargetWidth = state.rowWidths[to] + width
        if (nextTargetWidth > state.maximumRowWidth) return Long.MAX_VALUE

        var delta = balanceCost(state.rowWidths[from] - width, state.targetRowWidth) +
            balanceCost(nextTargetWidth, state.targetRowWidth) -
            balanceCost(state.rowWidths[from], state.targetRowWidth) -
            balanceCost(state.rowWidths[to], state.targetRowWidth)

        cellNets[cell].forEach { net ->
            val counts = state.netRowCounts[net]
            val before = partitionNetCost(net, counts)
            counts[from]--
            counts[to]++
            val after = partitionNetCost(net, counts)
            counts[to]--
            counts[from]++
            delta += after - before
        }
        return delta
    }

    private fun applyMove(state: PartitionState, cell: Int, to: Int) {
        val from = state.rowOf[cell]
        val width = cellWidths[cell]
        cellNets[cell].forEach { net ->
            state.netRowCounts[net][from]--
            state.netRowCounts[net][to]++
        }
        state.rowOf[cell] = to
        state.rowWidths[from] -= width
        state.rowWidths[to] += width
        state.rowCounts[from]--
        state.rowCounts[to]++
    }

    private fun refineSwaps(state: PartitionState) {
        repeat(PARTITION_SWAP_PASSES) {
            var changed = false
            for (first in cellsByDegree) {
                val firstRow = state.rowOf[first]
                var best = -1
                var bestDelta = 0L
                val candidates = linkedSetOf<Int>()
                cellNets[first].forEach { net -> netCells[net].forEach { candidates += it } }
                for (second in candidates) {
                    if (second == first || state.rowOf[second] == firstRow) continue
                    val delta = swapDelta(state, first, second)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        best = second
                    }
                }
                if (best >= 0) {
                    applySwap(state, first, best)
                    changed = true
                }
            }
            if (!changed) return
        }
    }

    private fun swapDelta(state: PartitionState, first: Int, second: Int): Long {
        val firstRow = state.rowOf[first]
        val secondRow = state.rowOf[second]
        if (firstRow == secondRow) return Long.MAX_VALUE
        val firstWidth = cellWidths[first]
        val secondWidth = cellWidths[second]
        val nextFirstWidth = state.rowWidths[firstRow] - firstWidth + secondWidth
        val nextSecondWidth = state.rowWidths[secondRow] - secondWidth + firstWidth
        if (nextFirstWidth > state.maximumRowWidth || nextSecondWidth > state.maximumRowWidth) return Long.MAX_VALUE

        var delta = balanceCost(nextFirstWidth, state.targetRowWidth) +
            balanceCost(nextSecondWidth, state.targetRowWidth) -
            balanceCost(state.rowWidths[firstRow], state.targetRowWidth) -
            balanceCost(state.rowWidths[secondRow], state.targetRowWidth)

        val affected = linkedSetOf<Int>()
        cellNets[first].forEach { affected += it }
        cellNets[second].forEach { affected += it }
        val before = affected.sumOf { partitionNetCost(it, state.netRowCounts[it]) }
        mutateSwapCounts(state, first, second, 1)
        val after = affected.sumOf { partitionNetCost(it, state.netRowCounts[it]) }
        mutateSwapCounts(state, first, second, -1)
        delta += after - before
        return delta
    }

    private fun mutateSwapCounts(state: PartitionState, first: Int, second: Int, direction: Int) {
        val firstRow = state.rowOf[first]
        val secondRow = state.rowOf[second]
        cellNets[first].forEach { net ->
            state.netRowCounts[net][firstRow] -= direction
            state.netRowCounts[net][secondRow] += direction
        }
        cellNets[second].forEach { net ->
            state.netRowCounts[net][secondRow] -= direction
            state.netRowCounts[net][firstRow] += direction
        }
    }

    private fun applySwap(state: PartitionState, first: Int, second: Int) {
        val firstRow = state.rowOf[first]
        val secondRow = state.rowOf[second]
        mutateSwapCounts(state, first, second, 1)
        state.rowOf[first] = secondRow
        state.rowOf[second] = firstRow
        val firstWidth = cellWidths[first]
        val secondWidth = cellWidths[second]
        state.rowWidths[firstRow] += secondWidth - firstWidth
        state.rowWidths[secondRow] += firstWidth - secondWidth
    }

    private fun refineRowOrder(state: PartitionState) {
        repeat(ROW_ORDER_PASSES) {
            var changed = false
            for (row in 0 until state.rowCount - 1) {
                val before = partitionScore(state)
                swapRows(state, row, row + 1)
                val after = partitionScore(state)
                if (after < before) {
                    changed = true
                } else {
                    swapRows(state, row, row + 1)
                }
            }
            if (!changed) return
        }
    }

    private fun swapRows(state: PartitionState, first: Int, second: Int) {
        for (cell in 0 until cellCount) {
            when (state.rowOf[cell]) {
                first -> state.rowOf[cell] = second
                second -> state.rowOf[cell] = first
            }
        }
        val width = state.rowWidths[first]
        state.rowWidths[first] = state.rowWidths[second]
        state.rowWidths[second] = width
        val count = state.rowCounts[first]
        state.rowCounts[first] = state.rowCounts[second]
        state.rowCounts[second] = count
        state.netRowCounts.forEach { counts ->
            val netCountAtFirst = counts[first]
            counts[first] = counts[second]
            counts[second] = netCountAtFirst
        }
    }

    private fun rowsFromState(state: PartitionState, order: IntArray): Array<MutableList<Int>> {
        val rows = Array(state.rowCount) { mutableListOf<Int>() }
        order.forEach { cell -> rows[state.rowOf[cell]] += cell }
        require(rows.all { it.isNotEmpty() })
        return rows
    }

    private fun refineHorizontal(start: Array<MutableList<Int>>): Array<MutableList<Int>> {
        val rows = Array(start.size) { start[it].toMutableList() }
        repeat(BARYCENTRIC_PASSES) { pass ->
            val rowOrder = if (pass % 2 == 0) rows.indices else rows.indices.reversed()
            rowOrder.forEach { row ->
                val geometry = geometry(rows)
                val keys = rows[row].associateWith { cell -> pullKey(cell, geometry) }
                rows[row].sortWith(
                    compareBy<Int> { keys.getValue(it) }
                        .thenByDescending { weightedDegree[it] }
                        .thenBy { it },
                )
            }
        }
        refineAdjacentOrder(rows)
        return rows
    }

    private fun pullKey(cell: Int, geometry: Geometry): Long {
        val points = mutableListOf<WeightedPoint>()
        val row = geometry.rowOf[cell]
        cellEndpoints[cell].forEach { endpoint ->
            val net = endpoint.net
            val weight = netWeights[net].toLong()
            for (index in netEndpointCells[net].indices) {
                val other = netEndpointCells[net][index]
                if (other == cell) continue
                val target = geometry.originX[other] + netEndpointXs[net][index] - endpoint.pinX
                points += WeightedPoint(target, weight)
            }
            if (geometry.netRows[net] > 1) {
                points += WeightedPoint(
                    geometry.rowWidths[row] - endpoint.pinX,
                    weight * GLOBAL_PULL_WEIGHT,
                )
            }
            if (netTouchesInput[net] && geometry.netRows[net] == 1) {
                points += WeightedPoint(-endpoint.pinX, weight * IO_PULL_WEIGHT)
            }
            if (netTouchesOutput[net] && netDriverCells[net] == cell) {
                points += WeightedPoint(
                    geometry.rowWidths[row] - endpoint.pinX,
                    weight * IO_PULL_WEIGHT,
                )
            }
        }
        return weightedMedian(points) ?: geometry.originX[cell].toLong()
    }

    private fun refineAdjacentOrder(rows: Array<MutableList<Int>>) {
        repeat(ADJACENT_PASSES) { pass ->
            var changed = false
            val geometry = geometry(rows)
            val rowOrder = if (pass % 2 == 0) rows.indices else rows.indices.reversed()
            for (row in rowOrder) {
                if (rows[row].size < 2) continue
                val indices = if (pass % 2 == 0) {
                    0 until rows[row].lastIndex
                } else {
                    (rows[row].lastIndex - 1 downTo 0)
                }
                indices.forEach { index ->
                    val first = rows[row][index]
                    val second = rows[row][index + 1]
                    val affected = linkedSetOf<Int>()
                    cellNets[first].forEach { affected += it }
                    cellNets[second].forEach { affected += it }
                    val before = affected.sumOf { geometryNetCost(it, geometry) }
                    val startX = geometry.originX[first]
                    val firstOrigin = geometry.originX[first]
                    val secondOrigin = geometry.originX[second]
                    geometry.originX[second] = startX
                    geometry.originX[first] = startX + cellWidths[second]
                    val after = affected.sumOf { geometryNetCost(it, geometry) }
                    if (after < before) {
                        rows[row][index] = second
                        rows[row][index + 1] = first
                        changed = true
                    } else {
                        geometry.originX[first] = firstOrigin
                        geometry.originX[second] = secondOrigin
                    }
                }
            }
            if (!changed) return
        }
    }

    private fun score(rows: List<List<Int>>): Long {
        val geometry = geometry(rows)
        var total = 0L
        for (net in 0 until netCount) total += geometryNetCost(net, geometry)
        total += congestionCost(geometry)
        val widest = geometry.rowWidths.maxOrNull() ?: 0
        total += widest.toLong() * WIDTH_COST
        return total
    }

    private fun geometry(rows: Array<out List<Int>>): Geometry = geometry(rows.asList())

    private fun geometry(rows: List<List<Int>>): Geometry {
        val rowOf = IntArray(cellCount)
        val originX = IntArray(cellCount)
        val rowWidths = IntArray(rows.size)
        rows.forEachIndexed { row, cells ->
            var x = 0
            cells.forEach { cell ->
                rowOf[cell] = row
                originX[cell] = x
                x += cellWidths[cell]
            }
            rowWidths[row] = max(0, x - cellGap)
        }
        val netRows = IntArray(netCount)
        for (net in 0 until netCount) {
            val occupied = BooleanArray(rows.size)
            netCells[net].forEach { cell -> occupied[rowOf[cell]] = true }
            netRows[net] = occupied.count { it }
        }
        return Geometry(rowOf, originX, rowWidths, netRows)
    }

    private fun geometryNetCost(net: Int, geometry: Geometry): Long {
        if (netEndpointCells[net].isEmpty()) return 0L
        val weight = netWeights[net].toLong()
        val minByRow = IntArray(geometry.rowWidths.size) { Int.MAX_VALUE }
        val maxByRow = IntArray(geometry.rowWidths.size) { Int.MIN_VALUE }
        var minimumRow = Int.MAX_VALUE
        var maximumRow = Int.MIN_VALUE
        netEndpointCells[net].indices.forEach { index ->
            val cell = netEndpointCells[net][index]
            val row = geometry.rowOf[cell]
            val x = geometry.originX[cell] + netEndpointXs[net][index]
            minByRow[row] = minOf(minByRow[row], x)
            maxByRow[row] = maxOf(maxByRow[row], x)
            minimumRow = minOf(minimumRow, row)
            maximumRow = maxOf(maximumRow, row)
        }

        var cost = 0L
        val global = maximumRow > minimumRow
        for (row in minByRow.indices) {
            if (minByRow[row] == Int.MAX_VALUE) continue
            val left = if (netTouchesInput[net] && !global) 0 else minByRow[row]
            val outputRow = netDriverCells[net].takeIf { it >= 0 }?.let { geometry.rowOf[it] }
            val right = if (global || netTouchesOutput[net] && row == outputRow) {
                geometry.rowWidths[row]
            } else {
                maxByRow[row]
            }
            cost += weight * max(0, right - left)
            if (global) cost += weight * GLOBAL_GEOMETRY_BASE
        }
        if (global) {
            cost += weight * (maximumRow - minimumRow).toLong() * rowPitch * ROW_SPAN_WEIGHT
        }
        return cost
    }

    private fun congestionCost(geometry: Geometry): Long {
        val intervals = Array(geometry.rowWidths.size) { mutableListOf<IntRange>() }
        val pins = LongArray(geometry.rowWidths.size)
        for (net in 0 until netCount) {
            if (netEndpointCells[net].isEmpty()) continue
            val minByRow = IntArray(geometry.rowWidths.size) { Int.MAX_VALUE }
            val maxByRow = IntArray(geometry.rowWidths.size) { Int.MIN_VALUE }
            netEndpointCells[net].indices.forEach { index ->
                val cell = netEndpointCells[net][index]
                val row = geometry.rowOf[cell]
                val x = geometry.originX[cell] + netEndpointXs[net][index]
                minByRow[row] = minOf(minByRow[row], x)
                maxByRow[row] = maxOf(maxByRow[row], x)
                pins[row]++
            }
            val global = geometry.netRows[net] > 1
            for (row in minByRow.indices) {
                if (minByRow[row] == Int.MAX_VALUE) continue
                val left = if (netTouchesInput[net] && !global) 0 else minByRow[row]
                val outputRow = netDriverCells[net].takeIf { it >= 0 }?.let { geometry.rowOf[it] }
                val right = if (global || netTouchesOutput[net] && row == outputRow) {
                    geometry.rowWidths[row]
                } else {
                    maxByRow[row]
                }
                intervals[row] += left..right
            }
        }
        return intervals.indices.sumOf { row -> pins[row] * deepestOverlap(intervals[row]) * lanePitch }
    }

    private fun deepestOverlap(intervals: List<IntRange>): Long {
        if (intervals.isEmpty()) return 0L
        val starts = intervals.map { it.first }.sorted()
        val ends = intervals.map { it.last }.sorted()
        var open = 0
        var deepest = 0
        var nextEnd = 0
        starts.forEach { start ->
            while (nextEnd < ends.size && ends[nextEnd] < start) {
                open--
                nextEnd++
            }
            open++
            deepest = max(deepest, open)
        }
        return deepest.toLong()
    }

    private fun weightedMedian(points: List<WeightedPoint>): Long? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.value }
        val total = sorted.sumOf { it.weight }
        val threshold = (total + 1) / 2
        var accumulated = 0L
        sorted.forEach { point ->
            accumulated += point.weight
            if (accumulated >= threshold) return point.value.toLong()
        }
        return sorted.last().value.toLong()
    }

    private fun partitionScore(state: PartitionState): Long =
        (0 until netCount).sumOf { partitionNetCost(it, state.netRowCounts[it]) }

    private fun partitionNetCost(net: Int, counts: IntArray): Long {
        var occupied = 0
        var first = -1
        var last = -1
        counts.indices.forEach { row ->
            if (counts[row] > 0) {
                if (first < 0) first = row
                last = row
                occupied++
            }
        }
        if (occupied <= 1) return 0L
        val weight = netWeights[net].toLong()
        return weight * (
            rowPitch.toLong() * GLOBAL_PARTITION_BASE +
                (occupied - 1).toLong() * rowPitch * GLOBAL_PARTITION_ROW +
                (last - first).toLong() * rowPitch * ROW_SPAN_WEIGHT
            )
    }

    private fun balanceCost(width: Int, target: Int): Long {
        val delta = width.toLong() - target
        return delta * delta * BALANCE_WEIGHT / max(1, target)
    }

    private fun buildCellNets(): Array<IntArray> {
        val counts = IntArray(cellCount)
        netCells.forEach { cells -> cells.forEach { counts[it]++ } }
        val result = Array(cellCount) { IntArray(counts[it]) }
        val filled = IntArray(cellCount)
        netCells.forEachIndexed { net, cells ->
            cells.forEach { cell -> result[cell][filled[cell]++] = net }
        }
        return result
    }

    private fun buildCellEndpoints(): Array<List<CellEndpoint>> {
        val result = Array(cellCount) { mutableListOf<CellEndpoint>() }
        for (net in 0 until netCount) {
            netEndpointCells[net].indices.forEach { index ->
                result[netEndpointCells[net][index]] += CellEndpoint(net, netEndpointXs[net][index])
            }
        }
        return Array(cellCount) { result[it].toList() }
    }

    private fun signature(rows: List<List<Int>>): String = rows.joinToString("|") { it.joinToString(",") }

    private inner class PartitionState(rows: Array<MutableList<Int>>) {
        val rowCount = rows.size
        val rowOf = IntArray(cellCount)
        val rowWidths = IntArray(rowCount)
        val rowCounts = IntArray(rowCount)
        val netRowCounts = Array(netCount) { IntArray(rowCount) }
        val targetRowWidth = (cellWidths.sumOf { it.toLong() } + rowCount - 1).div(rowCount).toInt()
        val maximumRowWidth = max(
            targetRowWidth + (cellWidths.maxOrNull() ?: 0),
            targetRowWidth * MAXIMUM_ROW_WIDTH_PERCENT / 100,
        )

        init {
            rows.forEachIndexed { row, cells ->
                cells.forEach { cell ->
                    rowOf[cell] = row
                    rowWidths[row] += cellWidths[cell]
                    rowCounts[row]++
                }
            }
            for (net in 0 until netCount) {
                netCells[net].forEach { cell -> netRowCounts[net][rowOf[cell]]++ }
            }
        }
    }

    private data class Geometry(
        val rowOf: IntArray,
        val originX: IntArray,
        val rowWidths: IntArray,
        val netRows: IntArray,
    )

    private data class CellEndpoint(val net: Int, val pinX: Int)

    private data class WeightedPoint(val value: Int, val weight: Long)

    private data class Candidate(val rows: List<List<Int>>, val score: Long)

    private companion object {
        const val PARTITION_MOVE_PASSES = 6
        const val PARTITION_SWAP_PASSES = 2
        const val ROW_ORDER_PASSES = 4
        const val BARYCENTRIC_PASSES = 6
        const val ADJACENT_PASSES = 8
        const val MAX_OUTPUT_CANDIDATES = 3
        const val MAXIMUM_ROW_WIDTH_PERCENT = 150
        const val DEFAULT_ROW_PITCH = 40
        const val DEFAULT_LANE_PITCH = 3
        const val GLOBAL_PARTITION_BASE = 8L
        const val GLOBAL_PARTITION_ROW = 3L
        const val ROW_SPAN_WEIGHT = 2L
        const val BALANCE_WEIGHT = 4L
        const val GLOBAL_PULL_WEIGHT = 3L
        const val IO_PULL_WEIGHT = 2L
        const val GLOBAL_GEOMETRY_BASE = 24L
        const val WIDTH_COST = 2L
    }
}
