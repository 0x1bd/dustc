package org.kvxd.dust.physical.compilation

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.io.PhysicalIo
import org.kvxd.dust.physical.compilation.model.*
import org.kvxd.dust.physical.io.PhysicalIoLayout
import org.kvxd.dust.physical.progress.PhysicalProgressEvent
import org.kvxd.dust.physical.progress.PhysicalProgressListener
import org.kvxd.dust.physical.progress.PhysicalProgressStage
import org.kvxd.dust.physical.placement.ConnectivityPlacer
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneTechnology
import org.kvxd.dust.technology.CellImplementation

internal class PhysicalPlacementPlanner(
    private val technology: RedstoneTechnology,
    private val ioCompiler: PhysicalIoCompiler,
    private val floorplanner: PhysicalFloorplanner,
) {
    internal fun cellInstances(netlist: BooleanNetlist): List<CellSpec> =
        netlist.instances.mapIndexed { index, instance ->
            val cell = checkNotNull(technology.physicalCell(instance.type)) {
                "technology has no physical view for ${instance.type.id}"
            }
            val nets = cell.pins.associate { pin ->
                val bits = checkNotNull(instance.connections[pin.port]) {
                    "${instance.name} does not connect physical port ${pin.port}"
                }
                pin.name to bits[pin.bit]
            }
            val outputs =
                cell.pins.filter { it.direction == PinDirection.OUTPUT }.map { nets.getValue(it.name) }.distinct()
            val tiers = outputs.mapNotNull { netlist.placements[it]?.tier }.distinct()
            require(tiers.size <= 1) { "${instance.name} has conflicting #[tier] constraints $tiers" }
            val near = outputs.flatMapTo(linkedSetOf()) { netlist.placements[it]?.near.orEmpty() }
            val hardMacro = cell.implementation as? CellImplementation.HardMacro
            CellSpec(
                instance.name,
                cell,
                nets,
                index,
                tiers.singleOrNull(),
                near,
                hardMacro?.visibleEdge,
                exclusiveRow = hardMacro?.exclusiveRow == true,
            )
        }

    internal fun searchFloorplan(
        netlist: BooleanNetlist,
        specs: List<CellSpec>,
        io: PhysicalIo,
        layout: PhysicalIoLayout?,
        progress: PhysicalProgressListener,
    ): FloorplanSelection {
        val placer = connectivityPlacer(netlist, specs)
        val candidates = rowCandidates(specs)
        val reserveIoSigns = io == PhysicalIo.DEBUG_PADS && layout != null
        val plans = mutableListOf<FloorplanCandidate>()
        var lastGeometryFailure: CandidateGeometryException? = null
        progress.onProgress(
            PhysicalProgressEvent(
                PhysicalProgressStage.PLACEMENT,
                completed = 0,
                total = candidates.size,
                candidateTotal = candidates.size,
                approximate = true
            )
        )
        candidates.forEachIndexed { candidateIndex, rows ->
            placer.place(rows).forEach { placement ->
                val gatePartitions = splitForcedTierRows(placement, specs)
                    .map { row -> row.map { index -> specs[index] } }
                val partitions = applyPlacementRequirements(
                    ioCompiler.attachPads(netlist, gatePartitions, io, layout),
                )
                tierCounts(partitions).forEach tierLoop@{ tierCount ->
                    val assignment = tierAssignments(netlist, partitions, tierCount) ?: return@tierLoop
                    try {
                        plans += FloorplanCandidate(
                            floorplanner.floorplan(
                                netlist,
                                partitions,
                                assignment,
                                tierCount,
                                ViaPolicy.STAIRS,
                                reserveIoSigns,
                                exactRouting = false,
                            ),
                            partitions,
                            assignment,
                            tierCount,
                            candidateIndex + 1,
                        )
                    } catch (cause: CandidateGeometryException) {
                        lastGeometryFailure = cause
                    }
                }
            }
            progress.onProgress(
                PhysicalProgressEvent(
                    PhysicalProgressStage.PLACEMENT,
                    completed = candidateIndex + 1,
                    total = candidates.size,
                    candidate = candidateIndex + 1,
                    candidateTotal = candidates.size,
                    detail = "$rows rows",
                    approximate = true,
                ),
            )
        }
        require(plans.isNotEmpty()) {
            "no feasible floorplan for ${specs.size} cells" +
                (lastGeometryFailure?.message?.let { ": $it" } ?: "")
        }
        val exactPlans = mutableListOf<FloorplanCandidate>()
        for (candidate in plans.sortedWith(compareByFloorplanCandidate())) {
            try {
                exactPlans += candidate.copy(
                    plan = floorplanner.floorplan(
                        netlist,
                        candidate.partitions,
                        candidate.assignment,
                        candidate.tierCount,
                        ViaPolicy.STAIRS,
                        reserveIoSigns,
                    ),
                )
            } catch (cause: CandidateGeometryException) {
                lastGeometryFailure = cause
            }
            if (exactPlans.size == EXACT_ROUTING_FINALISTS) break
        }
        require(exactPlans.isNotEmpty()) {
            "no electrically feasible floorplan for ${specs.size} cells" +
                (lastGeometryFailure?.message?.let { ": $it" } ?: "")
        }
        val selected = exactPlans.minWith(compareByFloorplanCandidate())
        if (specs.size > UPWARD_GLASS_PLACEMENT_GATE_LIMIT) {
            val glass = try {
                floorplanner.floorplan(
                    netlist,
                    selected.partitions,
                    selected.assignment,
                    selected.tierCount,
                    ViaPolicy.UPWARD_GLASS,
                    reserveIoSigns
                )
            } catch (_: CandidateGeometryException) {
                null
            }
            val final = listOfNotNull(
                selected,
                glass?.let { selected.copy(plan = it) },
            ).minWith(compareByFloorplanCandidate())
            return FloorplanSelection(final.plan, final.candidate, candidates.size)
        }
        val finalists = mutableListOf(selected)
        for (candidate in exactPlans.take(UPWARD_GLASS_CANDIDATES)) {
            try {
                finalists += candidate.copy(
                    plan = floorplanner.floorplan(
                        netlist,
                        candidate.partitions,
                        candidate.assignment,
                        candidate.tierCount,
                        ViaPolicy.UPWARD_GLASS,
                        reserveIoSigns,
                    ),
                )
            } catch (_: CandidateGeometryException) {

            }
        }
        val final = finalists.minWith(compareByFloorplanCandidate())
        return FloorplanSelection(final.plan, final.candidate, candidates.size)
    }

    private fun compareFloorplans(): Comparator<Floorplan> = compareBy<Floorplan> { it.timingViolationCount }
        .thenBy { it.timingDeficitTicks }
        .thenBy { it.selectionCost }
        .thenBy { it.routingBlocks }
        .thenBy { it.maximumDimension }
        .thenBy { it.area }
        .thenBy { it.routingRepeaters }
        .thenBy { it.clockSkewTicks }
        .thenBy { it.timingCutCost }
        .thenBy { it.tierCount }

    private fun compareByFloorplanCandidate(): Comparator<FloorplanCandidate> =
        Comparator { left, right -> compareFloorplans().compare(left.plan, right.plan) }

    private fun splitForcedTierRows(rows: List<List<Int>>, specs: List<CellSpec>): List<List<Int>> =
        rows.flatMap { row ->
            val forcedTiers = row.mapNotNull { specs[it].forcedTier }.distinct()
            if (forcedTiers.size <= 1) {
                listOf(row)
            } else {
                row.groupBy { specs[it].forcedTier }.values.filter { it.isNotEmpty() }
            }
        }

    private fun applyPlacementRequirements(rows: List<List<CellSpec>>): List<List<CellSpec>> {
        val separated = rows.flatMap { row ->
            buildList {
                val ordinary = mutableListOf<CellSpec>()
                fun flushOrdinary() {
                    if (ordinary.isNotEmpty()) {
                        add(ordinary.toList())
                        ordinary.clear()
                    }
                }
                row.forEach { spec ->
                    if (spec.exclusiveRow) {
                        flushOrdinary()
                        add(listOf(spec))
                    } else {
                        ordinary += spec
                    }
                }
                flushOrdinary()
            }
        }
        listOf(InterfaceEdge.NORTH, InterfaceEdge.SOUTH).forEach { edge ->
            val visible = separated.filter { row -> row.singleOrNull()?.takeIf { it.exclusiveRow }?.forcedEdge == edge }
            require(visible.size <= 1) { "more than one exclusive hard macro requests the $edge edge" }
        }
        return separated.sortedBy { row ->
            val edge = row.singleOrNull()?.takeIf { it.exclusiveRow }?.forcedEdge
            when (edge) {
                InterfaceEdge.NORTH -> 0
                InterfaceEdge.SOUTH -> 2
                else -> 1
            }
        }
    }

    private fun tierCounts(rows: List<List<CellSpec>>): List<Int> {
        val required = rows.flatten().mapNotNull { it.forcedTier }.maxOrNull()?.plus(1) ?: 1
        if (required > 1) return listOf(required)
        if (rows.flatten().any { it.forcedEdge != null }) return listOf(1)
        return if (rows.size >= 2) listOf(1, 2) else listOf(1)
    }

    private fun tierAssignments(
        netlist: BooleanNetlist,
        rows: List<List<CellSpec>>,
        tierCount: Int,
    ): IntArray? {
        if (tierCount == 1) {
            return if (rows.flatten().any { it.forcedTier != null && it.forcedTier != 0 }) null else IntArray(rows.size)
        }
        val forced = IntArray(rows.size) { -1 }
        rows.forEachIndexed { row, rowSpecs ->
            val tiers = rowSpecs.mapNotNull { it.forcedTier }.distinct()
            if (tiers.size > 1 || tiers.singleOrNull()?.let { it !in 0 until tierCount } == true) return null
            forced[row] = tiers.singleOrNull() ?: -1
        }
        val logicRows = rows.indices.filter { row -> rows[row].any { it.index >= 0 } }
        val logicRequiredTiers =
            logicRows.mapNotNull { row -> forced[row].takeIf { it >= 0 } }.maxOrNull()?.plus(1) ?: 1
        val logicTierCount = minOf(tierCount, maxOf(logicRequiredTiers, if (logicRows.size >= 2) 2 else 1))
        val signalRows = Array(netlist.signals) { linkedSetOf<Int>() }
        rows.forEachIndexed { row, rowSpecs ->
            rowSpecs.forEach { spec -> spec.nets.values.forEach { signalRows[it.index] += row } }
        }
        val criticality = signalCriticality(netlist)

        fun valid(assignment: IntArray): Boolean {
            if (assignment.indices.any { forced[it] >= 0 && forced[it] != assignment[it] }) return false
            if (logicRows.any { forced[it] < 0 && assignment[it] >= logicTierCount }) return false
            val occupiedTiers = assignment.toSet()
            val requiredTiers = forced.filter { it >= 0 }.toSet()
            if (!occupiedTiers.containsAll(requiredTiers)) return false
            val logicCounts = IntArray(logicTierCount)
            logicRows.forEach { row -> if (assignment[row] < logicTierCount) logicCounts[assignment[row]]++ }
            return logicCounts.maxOrNull()!! - logicCounts.minOrNull()!! <= TIER_ROW_IMBALANCE
        }

        fun score(assignment: IntArray): Long {
            val nextBand = IntArray(tierCount)
            val bands = IntArray(rows.size)
            rows.indices.forEach { row -> bands[row] = nextBand[assignment[row]]++ }
            var total = nextBand.maxOrNull()!!.toLong() * TIER_BALANCE_COST
            signalRows.forEachIndexed { index, touched ->
                if (touched.size < 2) return@forEachIndexed
                val minBand = touched.minOf { bands[it] }
                val maxBand = touched.maxOf { bands[it] }
                val minTier = touched.minOf { assignment[it] }
                val maxTier = touched.maxOf { assignment[it] }
                total += criticality[index].toLong() * (
                        (maxBand - minBand) * TIER_BAND_SPAN_COST +
                                (maxTier - minTier) * TIER_VERTICAL_SPAN_COST
                        )
            }
            rows.forEachIndexed { row, rowSpecs ->
                rowSpecs.forEach { spec ->
                    spec.nearSignals.forEach nearLoop@{ target ->
                        val targets = signalRows[target.index]
                        if (targets.isEmpty()) return@nearLoop
                        val distance = targets.minOf { other ->
                            abs(bands[row] - bands[other]) * TIER_BAND_SPAN_COST +
                                    abs(assignment[row] - assignment[other]) * TIER_VERTICAL_SPAN_COST
                        }
                        total += distance.toLong() * TIER_NEAR_WEIGHT
                    }
                }
            }
            return total
        }

        val candidates = linkedMapOf<String, IntArray>()
        fun consider(assignment: IntArray) {
            if (!valid(assignment)) return
            candidates.putIfAbsent(assignment.joinToString(","), assignment.copyOf())
        }
        if (rows.size <= EXACT_TIER_ROWS) {
            val assignment = IntArray(rows.size)
            fun visit(row: Int) {
                if (row == rows.size) {
                    consider(assignment)
                    return
                }
                if (forced[row] >= 0) {
                    assignment[row] = forced[row]
                    visit(row + 1)
                } else {
                    val availableTiers = if (row in logicRows) logicTierCount else tierCount
                    for (tier in 0 until availableTiers) {
                        assignment[row] = tier
                        visit(row + 1)
                    }
                }
            }
            visit(0)
        } else {
            consider(IntArray(rows.size) { it % tierCount })
            consider(IntArray(rows.size) { tierCount - 1 - it % tierCount })
            consider(IntArray(rows.size) { row -> minOf(tierCount - 1, row * tierCount / rows.size) })
            consider(IntArray(rows.size) { row ->
                val block = row / tierCount
                val local = row % tierCount
                if (block % 2 == 0) local else tierCount - 1 - local
            })
        }
        return candidates.values.minWithOrNull(compareBy<IntArray>({ score(it) }, { it.joinToString(",") }))
    }

    private fun connectivityPlacer(netlist: BooleanNetlist, specs: List<CellSpec>): ConnectivityPlacer {
        val criticality = signalCriticality(netlist)
        val endpointCells = Array(netlist.signals) { mutableListOf<Int>() }
        val endpointXs = Array(netlist.signals) { mutableListOf<Int>() }
        val driverCells = IntArray(netlist.signals) { -1 }
        specs.forEachIndexed { position, spec ->
            spec.cell.pins.forEach { pin ->
                val signal = spec.nets.getValue(pin.name)
                endpointCells[signal.index] += position
                endpointXs[signal.index] += pin.position.x
                if (pin.direction == PinDirection.OUTPUT) driverCells[signal.index] = position
            }
        }

        val netCells = mutableListOf<IntArray>()
        val netXs = mutableListOf<IntArray>()
        val weights = mutableListOf<Int>()
        val drivers = mutableListOf<Int>()
        val touchesInput = mutableListOf<Boolean>()
        val touchesOutput = mutableListOf<Boolean>()
        val primaryInputs = netlist.inputs.values.map { it.index }.toSet()
        val primaryOutputs = netlist.outputs.values.map { it.index }.toSet()
        (0 until netlist.signals).forEach { index ->
            val cells = endpointCells[index]
            val fromInput = index in primaryInputs
            val toOutput = index in primaryOutputs
            if (cells.size + (if (fromInput) 1 else 0) + (if (toOutput) 1 else 0) < 2) return@forEach
            netCells += cells.toIntArray()
            netXs += endpointXs[index].toIntArray()
            weights += criticality[index]
            drivers += driverCells[index]
            touchesInput += fromInput
            touchesOutput += toOutput
        }

        fun anchorCells(signal: Signal): List<Int> {
            val driver = driverCells[signal.index]
            return if (driver >= 0) listOf(driver) else endpointCells[signal.index].distinct()
        }

        val nearSignatures = hashSetOf<String>()
        (netlist.placements.entries + netlist.terminalPlacements.entries)
            .sortedBy { it.key.index }
            .forEach { (signal, placement) ->
                placement.near.sortedBy { it.index }.forEach nearLoop@{ target ->
                    val cells = (anchorCells(signal) + anchorCells(target)).distinct()
                    if (cells.size < 2) return@nearLoop
                    val signature = cells.sorted().joinToString(",")
                    if (!nearSignatures.add(signature)) return@nearLoop
                    netCells += cells.toIntArray()
                    netXs += cells.map { specs[it].cell.size.x / 2 }.toIntArray()
                    weights += maxOf(NEAR_AFFINITY, criticality[signal.index] * NEAR_AFFINITY)
                    drivers += -1
                    touchesInput += false
                    touchesOutput += false
                }
            }

        return ConnectivityPlacer(
            cellWidths = IntArray(specs.size) { specs[it].cell.size.x + technology.cellGap },
            netEndpointCells = netCells.toTypedArray(),
            netEndpointXs = netXs.toTypedArray(),
            netWeights = weights.toIntArray(),
            netDriverCells = drivers.toIntArray(),
            netTouchesInput = touchesInput.toBooleanArray(),
            netTouchesOutput = touchesOutput.toBooleanArray(),
            cellGap = technology.cellGap,
            lanePitch = technology.lanePitch,
        )
    }

    private fun rowCandidates(specs: List<CellSpec>): List<Int> {
        val cellCount = specs.size
        val totalWidth = specs.sumOf { it.cell.size.x + technology.cellGap }.toDouble()
        val target = sqrt(totalWidth / SHAPE_ROW_PITCH).coerceAtLeast(1.0)
        return buildSet {
            add(1)
            listOf(0.5, 0.75, 1.0, 1.4, 2.0).forEach { ratio ->
                add(ceil(target * ratio).toInt().coerceIn(1, cellCount))
            }
            if (cellCount <= SMALL_EXHAUSTIVE_ROWS) addAll(1..cellCount)
        }.sorted()
    }

}
