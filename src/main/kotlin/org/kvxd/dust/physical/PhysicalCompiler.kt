package org.kvxd.dust.physical

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockState
import org.kvxd.dust.device.ComponentKind
import org.kvxd.dust.device.Direction
import org.kvxd.dust.device.Properties
import org.kvxd.dust.device.SignBlockEntity
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.technology.CellPin
import org.kvxd.dust.technology.MinecraftRedstone
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneTechnology
import org.kvxd.dust.technology.StandardCell
import org.kvxd.dust.technology.placeChecked

class PhysicalCompiler(
    private val technology: RedstoneTechnology = MinecraftRedstone.technology,
) {
    fun compile(
        netlist: BooleanNetlist,
        io: PhysicalIo = PhysicalIo.DEBUG_PADS,
        layout: PhysicalIoLayout? = null,
        progress: PhysicalProgressListener = PhysicalProgressListener.NONE,
    ): PhysicalDesign {
        val specs = cellInstances(netlist)
        require(specs.isNotEmpty()) { "a physical design needs at least one gate" }
        validateIoLayout(netlist, layout)
        val selection = searchFloorplan(netlist, specs, io, layout, progress)
        val plan = selection.plan

        val matrix = BlockMatrix(plan.width, plan.height, plan.length)
        plan.cells.forEach { technology.placeCell(matrix, it.cell, it.origin) }
        val sink = MatrixSink(matrix)
        val routingWork = routeWork(plan.rows, plan.globalTracks)
        progress.onProgress(
            PhysicalProgressEvent(
                PhysicalProgressStage.ROUTING,
                completed = 0,
                total = routingWork,
                candidate = selection.candidate,
                candidateTotal = selection.candidateTotal,
                net = 0,
                netTotal = netlist.signals,
                approximate = true,
            ),
        )
        route(plan.rows, plan.globalTracks, sink) { completed, total, signal ->
            progress.onProgress(
                PhysicalProgressEvent(
                    PhysicalProgressStage.ROUTING,
                    completed = completed,
                    total = total,
                    candidate = selection.candidate,
                    candidateTotal = selection.candidateTotal,
                    net = signal.index + 1,
                    netTotal = netlist.signals,
                    approximate = true,
                ),
            )
        }
        progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.ELECTRICAL_FINALIZATION, completed = 0, total = 1))
        val owned = sink.owners
        verifyRouteIsolation(owned)
        placeIoSigns(matrix, plan.cells, layout)

        val connections = connections(plan.cells)

        val blocksBySignal = owned.entries.groupBy({ it.value }, { it.key })
        val signalsByIndex = connections.keys.associateBy { it.index }
        val routes = (0 until netlist.signals).map { index ->
            val signal = checkNotNull(signalsByIndex[index])
            val pins = checkNotNull(connections[signal])
            RoutedNet(
                signal,
                pins.single { it.pin.direction == PinDirection.OUTPUT }.position,
                pins.filter { it.pin.direction == PinDirection.INPUT }.map { it.position },
                blocksBySignal[signal].orEmpty().toSet(),
            )
        }
        val inputs = netlist.inputs.mapValues { (name, _) ->
            val cell = plan.cells.single { it.name == "input-$name" }
            if (io == PhysicalIo.DEBUG_PADS) cell.origin + BlockPos(0, 1, 0) else cell.pin("y")
        }
        val outputs = netlist.outputs.mapValues { (name, _) ->
            val cell = plan.cells.single { it.name == "output-$name" }
            if (io == PhysicalIo.DEBUG_PADS) cell.origin + BlockPos(0, OUTPUT_PLANE_OFFSET, 0) else cell.pin("a")
        }
        val delays = measureDelays(plan.rows, plan.globalTracks)
        progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.ELECTRICAL_FINALIZATION, completed = 1, total = 1))
        return PhysicalDesign(
            netlist,
            technology,
            matrix,
            plan.cells,
            routes,
            inputs,
            outputs,
            plan.rows.size,
            plan.rows.maxOf { it.routes.maxOfOrNull { route -> route.lane + 1 } ?: 0 },
            plan.globalTracks.map { it.signal }.distinct().size,
            delays,
        )
    }

    private fun cellInstances(netlist: BooleanNetlist): List<CellSpec> = netlist.instances.mapIndexed { index, instance ->
        val cell = checkNotNull(technology.cells[instance.type.id]) {
            "technology has no physical view for ${instance.type.id}"
        }
        val nets = cell.pins.associate { pin ->
            val bits = checkNotNull(instance.connections[pin.port]) {
                "${instance.name} does not connect physical port ${pin.port}"
            }
            pin.name to bits[pin.bit]
        }
        val outputs = cell.pins.filter { it.direction == PinDirection.OUTPUT }.map { nets.getValue(it.name) }.distinct()
        val tiers = outputs.mapNotNull { netlist.placements[it]?.tier }.distinct()
        require(tiers.size <= 1) { "${instance.name} has conflicting #[tier] constraints $tiers" }
        val near = outputs.flatMapTo(linkedSetOf()) { netlist.placements[it]?.near.orEmpty() }
        CellSpec(instance.name, cell, nets, index, tiers.singleOrNull(), near)
    }

    private fun searchFloorplan(
        netlist: BooleanNetlist,
        specs: List<CellSpec>,
        io: PhysicalIo,
        layout: PhysicalIoLayout?,
        progress: PhysicalProgressListener,
    ): FloorplanSelection {
        val placer = connectivityPlacer(netlist, specs)
        val candidates = rowCandidates(specs)
        val plans = mutableListOf<FloorplanCandidate>()
        progress.onProgress(PhysicalProgressEvent(PhysicalProgressStage.PLACEMENT, completed = 0, total = candidates.size, candidateTotal = candidates.size, approximate = true))
        candidates.forEachIndexed { candidateIndex, rows ->
            placer.place(rows).forEach { placement ->
                val gatePartitions = splitForcedTierRows(placement, specs)
                    .map { row -> row.map { index -> specs[index] } }
                val partitions = attachPads(netlist, gatePartitions, io, layout)
                tierCounts(partitions).forEach tierLoop@ { tierCount ->
                    val assignment = tierAssignments(netlist, partitions, tierCount) ?: return@tierLoop
                    try {
                        plans += FloorplanCandidate(
                            floorplan(netlist, partitions, assignment, tierCount, ViaPolicy.STAIRS),
                            partitions,
                            assignment,
                            tierCount,
                            candidateIndex + 1,
                        )
                    } catch (_: CandidateGeometryException) {

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
        require(plans.isNotEmpty()) { "no feasible floorplan for ${specs.size} cells" }
        val selected = plans.minWith(compareByFloorplanCandidate())
        if (specs.size > UPWARD_GLASS_PLACEMENT_GATE_LIMIT) {
            val glass = try {
                floorplan(netlist, selected.partitions, selected.assignment, selected.tierCount, ViaPolicy.UPWARD_GLASS)
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
        for (candidate in plans.sortedWith(compareByFloorplanCandidate()).take(UPWARD_GLASS_CANDIDATES)) {
            try {
                finalists += candidate.copy(
                    plan = floorplan(
                        netlist,
                        candidate.partitions,
                        candidate.assignment,
                        candidate.tierCount,
                        ViaPolicy.UPWARD_GLASS,
                    ),
                )
            } catch (_: CandidateGeometryException) {

            }
        }
        val final = finalists.minWith(compareByFloorplanCandidate())
        return FloorplanSelection(final.plan, final.candidate, candidates.size)
    }

    private fun compareFloorplans(): Comparator<Floorplan> = compareBy<Floorplan> { it.selectionCost }
        .thenBy { it.routingBlocks }
        .thenBy { it.maximumDimension }
        .thenBy { it.area }
        .thenBy { it.routingRepeaters }
        .thenBy { it.timingCutCost }
        .thenBy { it.tierCount }

    private fun compareByFloorplanCandidate(): Comparator<FloorplanCandidate> =
        Comparator { left, right -> compareFloorplans().compare(left.plan, right.plan) }

    private fun splitForcedTierRows(rows: List<List<Int>>, specs: List<CellSpec>): List<List<Int>> = rows.flatMap { row ->
        val forcedTiers = row.mapNotNull { specs[it].forcedTier }.distinct()
        if (forcedTiers.size <= 1) {
            listOf(row)
        } else {
            row.groupBy { specs[it].forcedTier }.values.filter { it.isNotEmpty() }
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
        val logicRequiredTiers = logicRows.mapNotNull { row -> forced[row].takeIf { it >= 0 } }.maxOrNull()?.plus(1) ?: 1
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

    private fun signalCriticality(netlist: BooleanNetlist): IntArray {
        val remaining = IntArray(netlist.signals)
        netlist.instances.asReversed().forEach { instance ->
            val outputs = instance.type.ports.filter { it.direction == org.kvxd.dust.cell.PortDirection.OUTPUT }
                .flatMap { instance.connections.getValue(it.name) }
            val latency = instance.type.timing.arcs.maxOfOrNull {
                maxOf(it.rise.maxTicks, it.fall.maxTicks)
            } ?: 0
            instance.type.ports.filter { it.direction == org.kvxd.dust.cell.PortDirection.INPUT }.forEach { port ->
                instance.connections.getValue(port.name).forEach { input ->
                    remaining[input.index] = maxOf(
                        remaining[input.index],
                        latency + (outputs.maxOfOrNull { remaining[it.index] } ?: 0),
                    )
                }
            }
        }
        return IntArray(netlist.signals) { remaining[it] + 1 }
    }

    private fun floorplan(
        netlist: BooleanNetlist,
        partitions: List<List<CellSpec>>,
        tierAssignment: IntArray,
        tierCount: Int,
        viaPolicy: ViaPolicy,
    ): Floorplan {
        require(partitions.size == tierAssignment.size)
        val activeCellHeight = partitions.flatten().maxOf { it.cell.size.y }
        val tierPitch = if (tierCount == 1) 0 else activeCellHeight + TIER_CLEARANCE
        val preliminaryRows = partitions.mapIndexed { row, specs ->
            placeRowCells(row, specs, tierAssignment[row] * tierPitch)
        }
        val contentWidth = preliminaryRows.maxOf { cells -> cells.maxOf { it.origin.x + it.cell.size.x } }
        val hasWestEdge = partitions.flatten().any { it.forcedEdge == InterfaceEdge.WEST }
        val hasEastEdge = partitions.flatten().any { it.forcedEdge == InterfaceEdge.EAST }
        val interiorX = if (hasWestEdge) EDGE_CELL_MARGIN else 0
        val edgeWidth = interiorX + contentWidth + if (hasEastEdge) EDGE_CELL_MARGIN else 0
        val localCells = preliminaryRows.flatMapIndexed { row, cells ->
            val specs = partitions[row]
            val rowWidth = cells.maxOf { it.origin.x + it.cell.size.x }
            val offsetX = when {
                specs.all { it.forcedEdge == InterfaceEdge.WEST } -> 0
                specs.all { it.forcedEdge == InterfaceEdge.EAST } -> edgeWidth - rowWidth
                specs.all { it.panel } -> interiorX + (contentWidth - rowWidth) / 2
                else -> interiorX
            }
            cells.map { it.copy(origin = it.origin + BlockPos(offsetX, 0, 0)) }
        }
        verifyPinColumns(localCells)
        val localConnections = connections(localCells)
        require(localConnections.keys.map { it.index }.toSet() == (0 until netlist.signals).toSet())
        val globalSignals = localConnections.filterValues { pins -> pins.map { it.cell.row }.distinct().size > 1 }.keys
            .sortedBy { it.index }
        val cellWidth = partitions.indices.maxOf { row ->
            val rowCells = localCells.filter { it.row == row }
            rowCells.maxOf { it.origin.x + it.cell.size.x }
        }
        val blockedGlobalViaColumns = localCells.flatMapTo(mutableSetOf()) { cell ->
            cell.cell.pins.flatMap { pin ->
                buildList {
                    add(cell.origin.x + pin.position.x)
                    if (pin.branchOffsetX != 0) add(cell.origin.x + pin.position.x + pin.branchOffsetX)
                }
            }
        }
        val activeRouteHeight = maxOf(
            technology.upperPlaneY + (tierCount - 1) * tierPitch + 1,
            localCells.maxOf { it.origin.y + it.cell.size.y },
        )
        val activeGlobalPlaneY = activeRouteHeight + GLOBAL_PLANE_CLEARANCE
        val globalTracks = assignGlobalTracks(
            planGlobalTracks(globalSignals, localConnections),
            cellWidth,
            blockedGlobalViaColumns,
            activeGlobalPlaneY,
            tierCount,
        )
        val tracksBySignal = globalTracks.groupBy { it.signal }
        val foldRows = tierCount > 1 && partitions.flatten().none { it.forcedEdge != null }

        val rowDrafts = partitions.indices.map { row ->
            val cells = localCells.filter { it.row == row }
            val drafts = localConnections.flatMap { (signal, pins) ->
                val rowPins = pins.filter { it.cell.row == row }
                if (rowPins.isEmpty()) return@flatMap emptyList()
                val driver = pins.single { it.pin.direction == PinDirection.OUTPUT }
                val rowSinks = rowPins.filter { it.pin.direction == PinDirection.INPUT }
                if (driver.cell.row == row) {
                    val source = driver.cellEndpoint()
                    val sinks = rowSinks.map { it.cellEndpoint() }.toMutableList<Endpoint>()
                    tracksBySignal[signal].orEmpty().forEach { track ->
                        val viaX = track.viaXForTier(tierAssignment[row])
                        if (foldRows) {
                            sinks += Endpoint.Global(track, ViaSense.DOWN, viaX)
                        } else {
                            if (track.sinkRows.any { it > row }) sinks += Endpoint.Global(track, ViaSense.UP, viaX)
                            if (track.sinkRows.any { it < row }) sinks += Endpoint.Global(track, ViaSense.DOWN, viaX)
                        }
                    }
                    if (sinks.isEmpty()) emptyList() else listOf(LocalRouteDraft(signal, row, source, sinks))
                } else {
                    val sense = if (foldRows) ViaSense.DOWN else if (row < driver.cell.row) ViaSense.UP else ViaSense.DOWN
                    checkNotNull(tracksBySignal[signal])
                        .filter { row in it.sinkRows }
                        .map { track ->
                            val sinks = rowSinks.filter { it.globalSinkKey() in track.sinkKeys }
                                .map { it.cellEndpoint() }
                            require(sinks.isNotEmpty())
                            LocalRouteDraft(
                                signal,
                                row,
                                Endpoint.Global(track, sense, track.viaXForTier(tierAssignment[row])),
                                sinks,
                            )
                        }
                }
            }

            val (abutting, channelled) = drafts.partition { it.abutment(technology.cellGap) != null }
            RowDraft(
                row,
                cells,
                assignLanes(channelled),
                abutting.map { checkNotNull(it.abutment(technology.cellGap)) },
                cells.maxOf { it.origin.z + it.cell.size.z },
            )
        }

        val bands = IntArray(partitions.size)
        if (foldRows) {
            val nextBand = IntArray(tierCount)
            partitions.indices.forEach { row -> bands[row] = nextBand[tierAssignment[row]]++ }
        } else {
            partitions.indices.forEach { row -> bands[row] = row }
        }
        val bandCount = bands.maxOrNull()?.plus(1) ?: 0
        val bandCellDepths = IntArray(bandCount)
        rowDrafts.forEach { draft ->
            val band = bands[draft.index]
            bandCellDepths[band] = maxOf(bandCellDepths[band], draft.cellDepth)
        }
        val tierLaneOffsets = IntArray(partitions.size)
        if (foldRows) {
            for (band in 0 until bandCount) {
                var offset = 0
                rowDrafts.filter { bands[it.index] == band }
                    .sortedBy { tierAssignment[it.index] }
                    .forEach { draft ->
                        tierLaneOffsets[draft.index] = offset
                        val laneCount = draft.routes.maxOfOrNull { it.lane + 1 } ?: 0
                        if (laneCount > 0) offset += (laneCount - 1) * technology.lanePitch + technology.isolation + 1
                    }
            }
        }
        val prepared = if (viaPolicy == ViaPolicy.STAIRS) {
            rowDrafts.map { draft ->
                val laneY = technology.lowerPlaneY + tierAssignment[draft.index] * tierPitch
                val baseViaReach = technology.viaSignalOffsets.maxOf { abs(it.z) }
                val globalViaReach = abs(
                    viaOffsets(
                        ViaSense.DOWN,
                        laneY,
                        activeGlobalPlaneY,
                        ViaFlow.DOWNWARD,
                        true,
                        viaPolicy,
                    ).last().z,
                )
                val endpointViaReach = draft.routes
                    .flatMap { route -> listOf(route.source) + route.sinks }
                    .maxOfOrNull { endpoint ->
                        when (endpoint) {
                            is Endpoint.Cell -> if (endpoint.sense == ViaSense.DOWN) {
                                abs(
                                    viaOffsets(
                                        endpoint.sense,
                                        laneY,
                                        endpoint.position.y,
                                        ViaFlow.DOWNWARD,
                                        false,
                                        viaPolicy,
                                    ).last().z,
                                )
                            } else {
                                baseViaReach
                            }
                            is Endpoint.Global -> globalViaReach
                        }
                    } ?: baseViaReach
                val laneReach = if (foldRows) globalViaReach else endpointViaReach
                val laneBase = bandCellDepths[bands[draft.index]] + technology.isolation + laneReach + tierLaneOffsets[draft.index]
                val routes = draft.routes.map { route ->
                    route.placeAt(0, laneY, laneBase + route.lane * technology.lanePitch, viaPolicy)
                }
                val southExtent = routes.maxOfOrNull { it.southernExtent(globalViaReach) }
                    ?: (bandCellDepths[bands[draft.index]] - 1)
                val outputPlaneExtent = routes.maxOfOrNull { route ->
                    (listOf(route.source) + route.sinks)
                        .filterIsInstance<Endpoint.Cell>()
                        .filter { it.sense == ViaSense.UP }
                        .maxOfOrNull { endpoint ->
                            route.laneZ + viaOffsets(
                                endpoint.sense,
                                laneY,
                                endpoint.position.y,
                                ViaFlow.DOWNWARD,
                                false,
                                viaPolicy,
                            ).last().z
                        } ?: Int.MIN_VALUE
                } ?: Int.MIN_VALUE
                PreparedRow(
                    draft,
                    laneY,
                    laneBase,
                    maxOf(southExtent, outputPlaneExtent) + technology.isolation + 1,
                )
            }
        } else {
            rowDrafts.map { draft ->
                val laneY = technology.lowerPlaneY + tierAssignment[draft.index] * tierPitch
                val laneReach = draft.routes.maxOfOrNull { route ->
                    maxOf(
                        viaReach(route.source, laneY, ViaFlow.DOWNWARD, viaPolicy),
                        route.sinks.maxOfOrNull { viaReach(it, laneY, ViaFlow.UPWARD, viaPolicy) } ?: 0,
                    )
                } ?: 0
                val laneBase = bandCellDepths[bands[draft.index]] + technology.isolation + laneReach + tierLaneOffsets[draft.index]
                val routes = draft.routes.map { route ->
                    route.placeAt(0, laneY, laneBase + route.lane * technology.lanePitch, viaPolicy)
                }
                val southExtent = routes.maxOfOrNull { route ->
                    maxOf(
                        route.laneZ + viaOffsets(
                            route.source.viaSense(),
                            laneY,
                            route.source.targetY(),
                            ViaFlow.DOWNWARD,
                            route.source is Endpoint.Global,
                            viaPolicy,
                        ).maxOf { it.z },
                        route.sinks.maxOfOrNull { endpoint ->
                            route.laneZ + viaOffsets(
                                endpoint.viaSense(),
                                laneY,
                                endpoint.targetY(),
                                ViaFlow.UPWARD,
                                endpoint is Endpoint.Global,
                                viaPolicy,
                            ).maxOf { it.z }
                        } ?: route.laneZ,
                    )
                } ?: (bandCellDepths[bands[draft.index]] - 1)
                PreparedRow(
                    draft,
                    laneY,
                    laneBase,
                    southExtent + technology.isolation + 1,
                )
            }
        }
        val bandDepths = IntArray(bandCount)
        prepared.forEach { row ->
            val band = bands[row.draft.index]
            bandDepths[band] = maxOf(bandDepths[band], row.depth)
        }
        val bandStarts = IntArray(bandCount)
        for (band in 1 until bandCount) {
            bandStarts[band] = bandStarts[band - 1] + bandDepths[band - 1]
        }
        val rows = prepared.map { preparedRow ->
            val baseZ = bandStarts[bands[preparedRow.draft.index]]
            val routed = preparedRow.draft.routes.map { route ->
                route.placeAt(
                    baseZ,
                    preparedRow.laneY,
                    baseZ + preparedRow.laneBase + route.lane * technology.lanePitch,
                    viaPolicy,
                )
            }
            val translatedCells = preparedRow.draft.cells.map { cell ->
                cell.copy(origin = cell.origin + BlockPos(0, 0, baseZ))
            }
            val translatedAbutments = preparedRow.draft.abutments.map { it.copy(z = it.z + baseZ) }
            PlacedRow(preparedRow.draft.index, translatedCells, routed, translatedAbutments)
        }
        val cells = rows.flatMap { it.cells }
        val width = maxOf(cellWidth, globalTracks.maxOfOrNull { it.footprint.last + 1 } ?: 0)
        val height = if (globalTracks.isEmpty()) activeRouteHeight else activeGlobalPlaneY + 1
        val length = bandDepths.sum()

        val criticality = signalCriticality(netlist)
        val timingCost = globalSignals.sumOf { criticality[it.index].toLong() }
        val routing = routingCost(rows, globalTracks)
        return Floorplan(
            rows,
            cells,
            globalTracks,
            width,
            height,
            length,
            timingCost,
            routing.repeaters,
            routing.blocks,
            tierCount,
        )
    }

    private fun placeRowCells(
        row: Int,
        specs: List<CellSpec>,
        yOffset: Int = 0,
        xOffset: Int = 0,
    ): List<PlacedCell> {
        val rowDepth = specs.maxOf { it.cell.size.z }
        var nextX = xOffset
        return specs.mapIndexed { index, spec ->
            val placed = PlacedCell(
                spec.name,
                spec.cell,
                BlockPos(nextX, technology.cellOriginY + yOffset, rowDepth - spec.cell.size.z),
                row,
                spec.nets,
            )
            val next = specs.getOrNull(index + 1)
            nextX += spec.cell.size.x + if (next != null && canAbut(spec, next, rowDepth)) 0 else technology.cellGap
            placed
        }
    }

    private fun canAbut(left: CellSpec, right: CellSpec, rowDepth: Int): Boolean {
        if (left.index < 0 || right.index < 0) return false
        val leftPins = left.cell.pins.filter { it.position.x == left.cell.size.x - 1 }
        val rightPins = right.cell.pins.filter { it.position.x == 0 }
        val joins = leftPins.flatMap { output ->
            if (output.direction != PinDirection.OUTPUT || !output.allowsHorizontalAbutment) return@flatMap emptyList()
            rightPins.mapNotNull { input ->
                if (input.direction != PinDirection.INPUT || !input.allowsHorizontalAbutment) return@mapNotNull null
                val leftSignal = left.nets.getValue(output.name)
                if (leftSignal != right.nets.getValue(input.name)) return@mapNotNull null
                val leftZ = rowDepth - left.cell.size.z + output.position.z
                val rightZ = rowDepth - right.cell.size.z + input.position.z
                if (output.position.y != input.position.y || leftZ != rightZ) return@mapNotNull null
                AbutmentSeam(output.position.y, leftZ, leftSignal)
            }
        }.associateBy { it.y to it.z }
        if (joins.isEmpty()) return false

        val leftBoundary = left.cell.blocks
            .filter { it.first.x == left.cell.size.x - 1 }
            .associate { (pos, state) ->
                (pos.y to (rowDepth - left.cell.size.z + pos.z)) to state.type.component
            }
        val rightBoundary = right.cell.blocks
            .filter { it.first.x == 0 }
            .associate { (pos, state) ->
                (pos.y to (rowDepth - right.cell.size.z + pos.z)) to state.type.component
            }
        val coordinates = leftBoundary.keys + rightBoundary.keys
        return coordinates.all { coordinate ->
            val leftComponent = leftBoundary[coordinate] ?: ComponentKind.NONE
            val rightComponent = rightBoundary[coordinate] ?: ComponentKind.NONE
            val leftElectrical = leftComponent != ComponentKind.NONE && leftComponent != ComponentKind.SUBSTRATE
            val rightElectrical = rightComponent != ComponentKind.NONE && rightComponent != ComponentKind.SUBSTRATE
            if (!leftElectrical && !rightElectrical) return@all true
            if (leftComponent == ComponentKind.NONE || rightComponent == ComponentKind.NONE) return@all true
            if (!leftElectrical || !rightElectrical) return@all false
            val join = joins[coordinate] ?: return@all false
            leftComponent == ComponentKind.WIRE && rightComponent == ComponentKind.WIRE && join.signal in left.nets.values
        }
    }

    private fun attachPads(
        netlist: BooleanNetlist,
        gatePartitions: List<List<CellSpec>>,
        io: PhysicalIo,
        layout: PhysicalIoLayout?,
    ): List<List<CellSpec>> {
        val inputCell = if (io == PhysicalIo.DEBUG_PADS) technology.debugInputPad else technology.inputTerminal
        val outputCell = if (io == PhysicalIo.DEBUG_PADS) technology.debugOutputPad else technology.outputTerminal
        val gateRows = IntArray(netlist.gates.size)
        val gatePositions = IntArray(netlist.gates.size)
        gatePartitions.forEachIndexed { row, specs ->
            specs.forEachIndexed { position, spec ->
                if (spec.index >= 0) {
                    gateRows[spec.index] = row
                    gatePositions[spec.index] = position
                }
            }
        }
        val layoutEdges = buildMap<Signal, InterfaceEdge> {
            layout?.groups?.forEach { group ->
                val edge = group.edge ?: return@forEach
                group.signals.forEach { name ->
                    val signal = when (group.direction) {
                        PhysicalIoDirection.INPUT -> netlist.inputs.getValue(name)
                        PhysicalIoDirection.OUTPUT -> netlist.outputs.getValue(name)
                    }
                    put(signal, InterfaceEdge.valueOf(edge.name))
                }
            }
        }
        val panelOrder = buildMap<Signal, Int> {
            var order = 0
            layout?.groups?.filter { it.panel }?.forEach { group ->
                group.signals.forEach { name ->
                    val signal = when (group.direction) {
                        PhysicalIoDirection.INPUT -> netlist.inputs.getValue(name)
                        PhysicalIoDirection.OUTPUT -> netlist.outputs.getValue(name)
                    }
                    put(signal, order++)
                }
            }
        }

        data class Terminal(val signal: Signal, val spec: CellSpec, val row: Int, val anchor: Int, val before: Boolean)

        fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

        fun terminalEdge(signal: Signal): InterfaceEdge? {
            val attribute = netlist.terminalPlacements[signal]?.edge
            val external = layoutEdges[signal]
            require(attribute == null || external == null || attribute == external) {
                "conflicting edge constraints for signal ${signal.index}"
            }
            return attribute ?: external
        }

        val terminals = buildList {
            netlist.inputs.forEach { (name, signal) ->
                val consumers = netlist.gates.indices.filter { signal in netlist.gates[it].inputs }
                val row = if (consumers.isEmpty()) 0 else median(consumers.map { gateRows[it] })
                val inRow = consumers.filter { gateRows[it] == row }
                val anchor = if (inRow.isEmpty()) 0 else median(inRow.map { gatePositions[it] })
                val placement = netlist.terminalPlacements[signal]
                add(
                    Terminal(
                        signal,
                        CellSpec(
                            "input-$name",
                            inputCell,
                            mapOf("y" to signal),
                            -1,
                            placement?.tier,
                            placement?.near.orEmpty(),
                            terminalEdge(signal),
                            signal in panelOrder,
                        ),
                        row,
                        anchor,
                        true,
                    ),
                )
            }
            netlist.outputs.forEach { (name, signal) ->
                val producer = netlist.gates.indexOfFirst { it.output == signal }
                val row = if (producer >= 0) gateRows[producer] else {
                    val consumers = netlist.gates.indices.filter { signal in netlist.gates[it].inputs }
                    if (consumers.isEmpty()) 0 else median(consumers.map { gateRows[it] })
                }
                val anchor = if (producer >= 0) gatePositions[producer] else gatePartitions[row].lastIndex.coerceAtLeast(0)
                val placement = netlist.terminalPlacements[signal]
                add(
                    Terminal(
                        signal,
                        CellSpec(
                            "output-$name",
                            outputCell,
                            mapOf("a" to signal),
                            -1,
                            placement?.tier,
                            placement?.near.orEmpty(),
                            terminalEdge(signal),
                            signal in panelOrder,
                        ),
                        row,
                        anchor,
                        false,
                    ),
                )
            }
        }

        val panelTerminals = terminals.filter { it.signal in panelOrder }
        val regularTerminals = terminals.filter {
            it.signal !in panelOrder && it.spec.forcedTier == null && it.spec.forcedEdge == null
        }
        val regularRows = gatePartitions.mapIndexed { row, gates ->
            val additions = regularTerminals.filter { it.row == row }.sortedWith(
                compareBy<Terminal>({ it.anchor }, { !it.before }, { it.spec.name }),
            )
            if (additions.isEmpty()) return@mapIndexed gates
            val slots = Array(gates.size + 1) { mutableListOf<CellSpec>() }
            val rowDepth = gates.maxOfOrNull { it.cell.size.z } ?: 1
            additions.forEach { terminal ->
                var slot = (terminal.anchor + if (terminal.before) 0 else 1).coerceIn(0, gates.size)
                if (slot in 1 until gates.size && canAbut(gates[slot - 1], gates[slot], rowDepth)) {
                    slot = if (terminal.before) slot - 1 else slot + 1
                }
                slots[slot] += terminal.spec
            }
            buildList {
                for (slot in slots.indices) {
                    addAll(slots[slot])
                    if (slot < gates.size) add(gates[slot])
                }
            }
        }

        val constrained = terminals.filter { it.signal !in panelOrder && it !in regularTerminals }
        val north = constrained.filter { it.spec.forcedEdge == InterfaceEdge.NORTH }.map { it.spec }
        val south = constrained.filter { it.spec.forcedEdge == InterfaceEdge.SOUTH }.map { it.spec }
        val west = constrained.filter { it.spec.forcedEdge == InterfaceEdge.WEST }.map { listOf(it.spec) }
        val east = constrained.filter { it.spec.forcedEdge == InterfaceEdge.EAST }.map { listOf(it.spec) }
        val tierOnly = constrained.filter { it.spec.forcedEdge == null }.map { listOf(it.spec) }
        fun panelRows(edge: InterfaceEdge): List<List<CellSpec>> = panelTerminals
            .filter { (it.spec.forcedEdge ?: InterfaceEdge.NORTH) == edge }
            .sortedBy { panelOrder.getValue(it.signal) }
            .groupBy { it.spec.forcedTier }
            .values
            .map { group -> group.map { it.spec } }
        val northPanels = panelRows(InterfaceEdge.NORTH)
        val southPanels = panelRows(InterfaceEdge.SOUTH)
        return buildList {
            addAll(northPanels)
            if (north.isNotEmpty()) add(north)
            addAll(regularRows)
            addAll(west)
            addAll(east)
            addAll(tierOnly)
            if (south.isNotEmpty()) add(south)
            addAll(southPanels)
        }
    }

    private fun validateIoLayout(netlist: BooleanNetlist, layout: PhysicalIoLayout?) {
        if (layout == null) return
        val inputs = layout.groups.filter { it.direction == PhysicalIoDirection.INPUT }.flatMap { it.signals }
        val outputs = layout.groups.filter { it.direction == PhysicalIoDirection.OUTPUT }.flatMap { it.signals }
        require(inputs.toSet() == netlist.inputs.keys && inputs.size == netlist.inputs.size) {
            "I/O layout inputs do not match ${netlist.inputs.keys}"
        }
        require(outputs.toSet() == netlist.outputs.keys && outputs.size == netlist.outputs.size) {
            "I/O layout outputs do not match ${netlist.outputs.keys}"
        }
        layout.groups.filter { it.panel }.forEach { group ->
            require(group.name != null) { "a panel requires a named I/O group" }
            val signals = group.signals.map { name ->
                when (group.direction) {
                    PhysicalIoDirection.INPUT -> netlist.inputs.getValue(name)
                    PhysicalIoDirection.OUTPUT -> netlist.outputs.getValue(name)
                }
            }
            val edges = buildSet {
                group.edge?.let { add(InterfaceEdge.valueOf(it.name)) }
                signals.mapNotNullTo(this) { netlist.terminalPlacements[it]?.edge }
            }
            require(edges.size <= 1) { "panel '${group.name}' has conflicting edge constraints" }
            require(edges.none { it == InterfaceEdge.EAST || it == InterfaceEdge.WEST }) {
                "a panel currently supports north/south edges"
            }
            require(signals.mapNotNull { netlist.terminalPlacements[it]?.tier }.distinct().size <= 1) {
                "panel '${group.name}' has conflicting tier constraints"
            }
        }
    }

    private fun placeIoSigns(
        matrix: BlockMatrix,
        cells: List<PlacedCell>,
        layout: PhysicalIoLayout?,
    ) {
        if (layout == null) return
        layout.groups.forEach { group ->
            group.signals.forEach signalLoop@ { signal ->
                val cellPrefix = if (group.direction == PhysicalIoDirection.INPUT) "input-" else "output-"
                val cell = cells.single { it.name == cellPrefix + signal }
                val y = if (group.direction == PhysicalIoDirection.INPUT) {
                    cell.origin.y
                } else if (cell.cell.name == "output-pad") {
                    cell.origin.y + OUTPUT_PLANE_OFFSET
                } else {
                    cell.origin.y + OUTPUT_PLANE_OFFSET - 1
                }
                val north = BlockPos(cell.origin.x, y, cell.origin.z - 1)
                val south = BlockPos(cell.origin.x, y, cell.origin.z + cell.cell.size.z)
                val west = BlockPos(cell.origin.x - 1, y, cell.origin.z)
                val east = BlockPos(cell.origin.x + cell.cell.size.x, y, cell.origin.z)
                val candidates = when (group.edge) {
                    PhysicalIoEdge.NORTH -> listOf(north, west, east, south)
                    PhysicalIoEdge.SOUTH -> listOf(south, east, west, north)
                    PhysicalIoEdge.WEST -> listOf(west, north, south, east)
                    PhysicalIoEdge.EAST -> listOf(east, south, north, west)
                    null -> listOf(north, south, west, east)
                }
                val position = candidates.firstOrNull { matrix.contains(it) && matrix.blockAt(it).isAir }
                    ?: return@signalLoop
                val state = group.edge?.let { technology.ioSign.with(Properties.FACING, it.outward) } ?: technology.ioSign
                matrix.placeChecked(position, state)
                val heading = group.name?.let { name ->
                    if (group.direction == PhysicalIoDirection.INPUT) "IN $name" else "OUT $name"
                } ?: if (group.direction == PhysicalIoDirection.INPUT) {
                    "INPUT"
                } else {
                    "OUTPUT"
                }
                matrix.setBlockEntityAt(position, SignBlockEntity(listOf(heading, signal)))
            }
        }
    }

    private fun planGlobalTracks(
        globalSignals: List<Signal>,
        connections: Map<Signal, List<ConnectedPin>>,
    ): List<GlobalTrackRequest> = globalSignals.flatMap { signal ->
        val pins = checkNotNull(connections[signal])
        val driver = pins.single { it.pin.direction == PinDirection.OUTPUT }
        val sinkPoints = pins.filter { it.pin.direction == PinDirection.INPUT && it.cell.row != driver.cell.row }
            .map { GlobalSinkPoint(it.globalSinkKey(), it.cell.row, it.position.x) }
            .sortedWith(compareBy<GlobalSinkPoint> { it.x }.thenBy { it.row }.thenBy { it.key.cell })
        val partitions = globalTrackPartitions(driver.cell.row, driver.position.x, sinkPoints)
        partitions.mapIndexed { ordinal, group ->
            val xs = (group.map { it.x } + driver.position.x).sorted()
            GlobalTrackRequest(
                signal,
                ordinal,
                driver.cell.row,
                group.mapTo(linkedSetOf()) { it.key },
                group.mapTo(linkedSetOf()) { it.row },
                xs[xs.size / 2],
            )
        }
    }

    private fun globalTrackPartitions(
        driverRow: Int,
        driverX: Int,
        sinks: List<GlobalSinkPoint>,
    ): List<List<GlobalSinkPoint>> {
        if (sinks.size <= 1) return listOf(sinks)
        val maximumTracks = minOf(STEINER_MAX_TRACKS, sinks.size)
        var best = listOf(sinks)
        var bestCost = globalPartitionCost(driverRow, driverX, best)
        if (maximumTracks >= 2) {
            for (first in 1 until sinks.size) {
                val candidate = listOf(sinks.subList(0, first), sinks.subList(first, sinks.size))
                val cost = globalPartitionCost(driverRow, driverX, candidate)
                if (cost < bestCost) {
                    best = candidate
                    bestCost = cost
                }
            }
        }
        if (maximumTracks >= 3) {
            for (first in 1 until sinks.size - 1) {
                for (second in first + 1 until sinks.size) {
                    val candidate = listOf(
                        sinks.subList(0, first),
                        sinks.subList(first, second),
                        sinks.subList(second, sinks.size),
                    )
                    val cost = globalPartitionCost(driverRow, driverX, candidate)
                    if (cost < bestCost) {
                        best = candidate
                        bestCost = cost
                    }
                }
            }
        }
        return best
    }

    private fun globalPartitionCost(
        driverRow: Int,
        driverX: Int,
        groups: List<List<GlobalSinkPoint>>,
    ): Long = groups.sumOf { group ->
        val sortedXs = (group.map { it.x } + driverX).sorted()
        val x = sortedXs[sortedXs.size / 2]
        val horizontal = group.sumOf { sink -> abs(sink.x - x).toLong() } + abs(driverX - x)
        val firstRow = minOf(driverRow, group.minOf { it.row })
        val lastRow = maxOf(driverRow, group.maxOf { it.row })
        horizontal + (lastRow - firstRow).toLong() * STEINER_ROW_COST + STEINER_TRACK_COST
    }

    private fun assignGlobalTracks(
        requests: List<GlobalTrackRequest>,
        cellWidth: Int,
        blockedViaColumns: Set<Int>,
        planeY: Int,
        tierCount: Int,
    ): List<GlobalTrack> {
        val folded = tierCount > 1
        val tracks = mutableListOf<GlobalTrack>()
        requests.sortedWith(
            compareByDescending<GlobalTrackRequest> { (it.rowSpan.last - it.rowSpan.first + 1) * it.sinkRows.size }
                .thenByDescending { it.rowSpan.last - it.rowSpan.first }
                .thenBy { it.signal.index }
                .thenBy { it.ordinal },
        ).forEach { request ->
            var radius = 0
            var chosen: GlobalTrack? = null
            while (chosen == null) {
                val viaCandidates = linkedSetOf(request.preferredX - radius, request.preferredX + radius)
                chosen = viaCandidates.asSequence()
                    .filter { it >= 0 }
                    .filter { viaX ->
                        (0 until tierCount).all { tier ->
                            val tierViaX = viaX + tier * GLOBAL_TIER_VIA_PITCH
                            blockedViaColumns.none { abs(it - tierViaX) <= technology.isolation }
                        }
                    }
                    .flatMap { viaX ->
                        val trunkCandidates = if (folded) {
                            sequenceOf(viaX - GLOBAL_TAP_OFFSET, viaX + GLOBAL_TAP_OFFSET)
                        } else if (request.sinkRows.size == 1) {
                            sequenceOf(viaX, viaX - GLOBAL_TAP_OFFSET, viaX + GLOBAL_TAP_OFFSET)
                        } else {
                            sequenceOf(viaX - GLOBAL_TAP_OFFSET, viaX + GLOBAL_TAP_OFFSET)
                        }
                        trunkCandidates.filter { it >= 0 }
                            .map { trunkX ->
                                GlobalTrack(
                                    request.signal,
                                    request.ordinal,
                                    request.driverRow,
                                    request.sinkKeys,
                                    request.sinkRows,
                                    trunkX,
                                    viaX,
                                    planeY,
                                    tierCount,
                                )
                            }
                    }
                    .filter { candidate ->
                        tracks.none { placed ->
                            (folded || rowSpansOverlap(candidate.rowSpan, placed.rowSpan)) &&
                                candidate.footprint.conflicts(placed.footprint, technology.isolation)
                        }
                    }
                    .minWithOrNull(
                        compareBy<GlobalTrack>(
                            { abs(it.viaX - request.preferredX) },
                            { abs(it.trunkX - it.viaX) },
                            { maxOf(it.trunkX, it.viaX) >= cellWidth },
                            { maxOf(it.trunkX, it.viaX) },
                            { it.trunkX },
                        ),
                    )
                radius++
            }
            tracks += chosen
        }
        return tracks
    }

    private fun rowSpansOverlap(left: IntRange, right: IntRange): Boolean =
        left.first <= right.last + GLOBAL_ROW_GUARD && right.first <= left.last + GLOBAL_ROW_GUARD

    private fun assignLanes(routes: List<LocalRouteDraft>): List<LocalRouteDraft> {
        val laneSpans = mutableListOf<MutableList<IntRange>>()
        val laneRoutes = mutableListOf<MutableList<LocalRouteDraft>>()
        val order = compareBy<LocalRouteDraft> { it.hasSouthRisingEndpoint }
            .thenByDescending { it.sinks.size + 1 }
            .thenBy { it.minimumX }
            .thenBy { it.signal.index }
        return routes.sortedWith(order).map { route ->
            val span = route.minimumX..route.maximumX
            var lane = 0
            while (true) {
                val sameLaneIsFree = laneSpans.getOrNull(lane)
                    ?.none { it.conflicts(span, technology.isolation) }
                    ?: true
                val adjacentViasAreClear = (lane - 1..lane + 1)
                    .filter { it >= 0 && it != lane }
                    .flatMap { laneRoutes.getOrNull(it).orEmpty() }
                    .none { route.viaClashesAcrossAdjacentLane(it) }
                if (sameLaneIsFree && adjacentViasAreClear) break
                lane++
            }
            while (laneSpans.size <= lane) {
                laneSpans.add(mutableListOf())
                laneRoutes.add(mutableListOf())
            }
            laneSpans[lane].add(span)
            laneRoutes[lane].add(route)
            route.copy(lane = lane)
        }
    }

    private fun connections(cells: List<PlacedCell>): Map<Signal, List<ConnectedPin>> {
        val connections = mutableMapOf<Signal, MutableList<ConnectedPin>>()
        cells.forEach { cell ->
            cell.cell.pins.forEach { pin ->
                val signal = checkNotNull(cell.nets[pin.name])
                connections.getOrPut(signal) { mutableListOf() } += ConnectedPin(cell, pin, cell.pin(pin.name))
            }
        }
        return connections
    }


    private fun ConnectedPin.globalSinkKey(): GlobalSinkKey = GlobalSinkKey(cell.name, pin.name)

    private fun ConnectedPin.cellEndpoint(): Endpoint.Cell = Endpoint.Cell(
        position,
        pin.allowsHorizontalAbutment,
        if (pin.accessesFromSouth) ViaSense.UP else ViaSense.DOWN,
        pin.branchOffsetX,
        pin.driveStrength,
        pin.requiredStrength,
    )

    private fun verifyPinColumns(cells: List<PlacedCell>) {
        cells.groupBy { it.row }.forEach { (row, rowCells) ->
            val pinsByPlane = rowCells.flatMap { cell ->
                cell.cell.pins.map { pin ->
                    PinColumn(
                        cell.origin.y + pin.position.y,
                        cell.origin.x + pin.position.x,
                        cell.nets.getValue(pin.name),
                    )
                }
            }.groupBy { it.y }
            pinsByPlane.forEach { (planeY, pins) ->
                val sorted = pins.sortedBy { it.x }
                sorted.zipWithNext().forEach { (left, right) ->
                    require(left.x != right.x) { "row $row plane Y=$planeY has shared pin column ${left.x}" }
                    require(right.x - left.x > technology.isolation || right.signal == left.signal) {
                        "row $row plane Y=$planeY pin columns ${left.x} and ${right.x} are not isolated"
                    }
                }
            }
        }
    }

    private fun routeWork(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): Int =
        rows.sumOf { it.routes.size + it.abutments.size } + globalTracks.size

    private fun route(
        rows: List<PlacedRow>,
        globalTracks: List<GlobalTrack>,
        sink: RouteSink,
        progress: ((completed: Int, total: Int, signal: Signal) -> Unit)? = null,
    ): DelayLog {
        val log = DelayLog()
        val allRoutes = rows.flatMap { it.routes }
        val runs = globalRuns(rows, globalTracks)
        val total = routeWork(rows, globalTracks)
        var completed = 0
        fun complete(signal: Signal) {
            completed++
            progress?.invoke(completed, total, signal)
        }
        allRoutes.filter { it.source is Endpoint.Cell }.forEach { route ->
            route.route(sink, 0, 0, log)
            complete(route.signal)
        }
        rows.flatMap { it.abutments }.forEach { abutment ->
            abutment.columns.forEach { x ->
                sink.place(
                    BlockPos(x, abutment.y, abutment.z),
                    technology.wire,
                    technology.routeSupport,
                    abutment.signal,
                )
            }
            log.pinTicks[BlockPos(abutment.sinkX, abutment.y, abutment.z)] = 0
            complete(abutment.signal)
        }
        runs.forEach { run ->
            placeGlobalRun(sink, run, log)
            complete(run.signal)
        }
        allRoutes.filter { it.source is Endpoint.Global }.forEach { route ->
            val source = route.source as Endpoint.Global
            val key = TapKey(source.track, route.laneY, route.laneZ)
            route.route(
                sink,
                checkNotNull(log.tapDecay[key]) { "missing global decay for signal ${route.signal.index}" },
                log.tapTicks[key] ?: 0,
                log,
            )
            complete(route.signal)
        }
        return log
    }

    private fun measureDelays(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): Map<BlockPos, Int> =
        route(rows, globalTracks, RouteSink { _, _, _, _ -> }).pinTicks

    private fun globalRuns(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): List<GlobalRun> =
        globalTracks.map { track ->
            val segments = rows.flatMap { row -> row.routes.filter { it.signal == track.signal } }
            val source = segments.single { route ->
                route.sinks.filterIsInstance<Endpoint.Global>().any { it.track == track }
            }
            val starts = source.sinks.filterIsInstance<Endpoint.Global>()
                .filter { it.track == track }
                .map { endpoint -> GlobalStart(endpoint.sense, globalTapZ(source, endpoint), endpoint.viaX) }
            val taps = segments.mapNotNull { route ->
                (route.source as? Endpoint.Global)?.takeIf { it.track == track }?.let { endpoint ->
                    GlobalTap(globalTapZ(route, endpoint), route.laneY, route.laneZ, endpoint.viaX)
                }
            }
            require(starts.isNotEmpty()) { "global track for signal ${track.signal.index} has no handoff" }
            require(taps.isNotEmpty()) { "global track for signal ${track.signal.index} has no tap" }
            require(segments.all { it.viaPolicy == source.viaPolicy }) { "global track mixes via policies" }
            GlobalRun(
                track.signal,
                track,
                starts.sortedWith(compareBy<GlobalStart>({ it.z }, { it.sense.ordinal })),
                taps.sortedBy { it.z },
                source.viaPolicy,
            )
        }

    private fun globalTapZ(route: LocalRoute, endpoint: Endpoint.Global): Int =
        route.laneZ + viaOffsets(
            endpoint.sense,
            route.laneY,
            endpoint.track.planeY,
            if (route.source == endpoint) ViaFlow.DOWNWARD else ViaFlow.UPWARD,
            true,
            route.viaPolicy,
        ).last().z

    private fun routingCost(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): RoutingCost =
        CountingSink().also { route(rows, globalTracks, it) }.let { RoutingCost(it.repeaters, it.blocks) }

    private fun placeGlobalRun(sink: RouteSink, run: GlobalRun, log: DelayLog) {
        val assigned = run.taps.groupBy { tap ->
            run.starts.minWith(compareBy<GlobalStart>({ abs(it.z - tap.z) }, { it.sense.ordinal }))
        }
        run.starts.forEach { start ->
            val key = HandoffKey(run.track, start.sense)
            val handoffDecay = checkNotNull(log.handoffDecay[key]) {
                "missing global handoff for signal ${run.signal.index}"
            }
            val handoffTicks = log.handoffTicks[key] ?: 0
            val atTrunk = placeGlobalConnector(
                sink,
                run,
                start.z,
                start.viaX,
                run.track.trunkX,
                handoffDecay,
                0,
            )
            val taps = assigned[start].orEmpty()
            taps.filter { it.z == start.z }.forEach { tap ->
                recordGlobalTap(sink, run, tap, atTrunk.decay, atTrunk.repeaters, handoffTicks, log)
            }
            listOf(Direction.NORTH, Direction.SOUTH).forEach { travel ->
                val south = travel == Direction.SOUTH
                val arm = taps.filter { if (south) it.z > start.z else it.z < start.z }
                placeGlobalArm(
                    sink,
                    run,
                    start.z,
                    if (south) arm.sortedBy { it.z } else arm.sortedByDescending { it.z },
                    travel,
                    atTrunk.decay,
                    handoffTicks + atTrunk.repeaters,
                    log,
                )
            }
        }
    }

    private fun placeGlobalConnector(
        sink: RouteSink,
        run: GlobalRun,
        z: Int,
        fromX: Int,
        toX: Int,
        initialDecay: Int,
        viaReserve: Int,
    ): Carried {
        if (fromX == toX) return Carried(initialDecay, 0)
        val travel = if (toX > fromX) Direction.EAST else Direction.WEST
        var decayAtEnd = initialDecay
        var repeaters = 0
        placeXRun(
            sink,
            minOf(fromX, toX),
            maxOf(fromX, toX),
            run.track.planeY,
            z,
            travel,
            emptySet(),
            run.signal,
            initialDecay = initialDecay,
            viaColumns = setOf(fromX, toX),
            viaReserve = viaReserve,
        ) { x, decay, repeater ->
            if (repeater) repeaters++
            if (x == toX) decayAtEnd = if (repeater) 0 else decay
        }
        return Carried(decayAtEnd, repeaters)
    }

    private fun recordGlobalTap(
        sink: RouteSink,
        run: GlobalRun,
        tap: GlobalTap,
        trunkDecay: Int,
        trunkRepeaters: Int,
        inboundTicks: Int,
        log: DelayLog,
    ) {
        val connector = placeGlobalConnector(
            sink,
            run,
            tap.z,
            run.track.trunkX,
            tap.viaX,
            trunkDecay,
            globalViaDescent(run.track, tap.laneY, ViaFlow.DOWNWARD, run.viaPolicy),
        )
        val key = TapKey(run.track, tap.laneY, tap.laneZ)
        log.tapDecay[key] = connector.decay
        log.tapTicks[key] = inboundTicks + trunkRepeaters + connector.repeaters
    }

    private fun placeGlobalArm(
        sink: RouteSink,
        run: GlobalRun,
        startZ: Int,
        taps: List<GlobalTap>,
        travel: Direction,
        initialDecay: Int,
        inboundTicks: Int,
        log: DelayLog,
    ) {
        if (taps.isEmpty()) return
        val endZ = taps.last().z
        val protected = buildSet {
            add(startZ)
            taps.forEach { add(it.z) }
        }
        val tapsByZ = taps.groupBy { it.z }
        var repeaters = 0
        placeZRun(
            sink,
            run.track.trunkX,
            minOf(startZ, endZ),
            maxOf(startZ, endZ),
            run.track.planeY,
            travel,
            emptySet(),
            run.signal,
            initialDecay = initialDecay,
            protectedPositions = protected,
            viaReserve = taps.maxOf {
                abs(it.viaX - run.track.trunkX) + globalViaDescent(run.track, it.laneY, ViaFlow.DOWNWARD, run.viaPolicy)
            },
        ) { z, decay, repeater ->
            if (repeater) repeaters++
            tapsByZ[z].orEmpty().forEach { tap ->
                recordGlobalTap(
                    sink,
                    run,
                    tap,
                    if (repeater) 0 else decay,
                    0,
                    inboundTicks + repeaters,
                    log,
                )
            }
        }
    }

    private fun LocalRoute.route(sink: RouteSink, inboundDecay: Int, inboundTicks: Int, log: DelayLog) {
        val sourceX = source.x
        val sinkXs = sinks.map { it.x }

        val fromSource = placeSourceEndpoint(sink, inboundDecay)
        val atVia = HashMap<Int, Carried>()
        atVia[sourceX] = Carried(fromSource.decay, 0)

        listOf(Direction.WEST, Direction.EAST).forEach { travel ->
            val arm = sinkXs.filter { if (travel == Direction.EAST) it > sourceX else it < sourceX }
            if (arm.isEmpty()) return@forEach
            val viaColumns = arm.toSet() + sourceX
            val viaReserve = (listOf(source) + sinks.filter { it.x in viaColumns })
                .maxOf { endpoint ->
                    endpointLaneReserve(
                        endpoint,
                        laneY,
                        if (endpoint == source) ViaFlow.DOWNWARD else ViaFlow.UPWARD,
                        viaPolicy,
                    )
                }
            var armRepeaters = 0
            placeXRun(
                sink,
                minOf(sourceX, arm.min()),
                maxOf(sourceX, arm.max()),
                laneY,
                laneZ,
                travel,
                emptySet(),
                signal,
                initialDecay = fromSource.decay,
                viaColumns = viaColumns,
                viaReserve = viaReserve,
            ) { x, decay, repeater ->
                if (x in viaColumns && x != sourceX) atVia[x] = Carried(decay, armRepeaters)
                if (repeater) armRepeaters++
            }
        }

        sinks.forEach { endpoint ->
            val onLane = checkNotNull(atVia[endpoint.x])
            val toSink = placeSinkEndpoint(sink, endpoint, onLane.decay)
            val ticks = inboundTicks + fromSource.repeaters + onLane.repeaters + toSink.repeaters
            when (endpoint) {
                is Endpoint.Cell -> log.pinTicks[endpoint.position] = ticks
                is Endpoint.Global -> {
                    val key = HandoffKey(endpoint.track, endpoint.sense)
                    log.handoffDecay[key] = toSink.decay
                    log.handoffTicks[key] = ticks
                }
            }
        }
    }

    private fun LocalRoute.placeSourceEndpoint(sink: RouteSink, inboundDecay: Int): Carried = when (source) {
        is Endpoint.Cell -> {
            val descent = endpointViaDescent(source, laneY, ViaFlow.DOWNWARD, viaPolicy)
            placeVia(sink, source.x, laneY, laneZ, source.sense, signal, source.position.y, ViaFlow.DOWNWARD, false, viaPolicy)
            val accessZ = laneZ + viaOffsets(
                source.sense,
                laneY,
                source.position.y,
                ViaFlow.DOWNWARD,
                false,
                viaPolicy,
            ).last().z
            val branch = routeUpperBranch(
                sink,
                source.position,
                accessZ,
                source.sense,
                source.branchOffsetX,
                Direction.SOUTH,
                signal,
                technology.signalStrength - source.driveStrength + 1,
                descent,
                1,
            )
            Carried(branch.decay + descent, branch.repeaters)
        }

        is Endpoint.Global -> {
            placeVia(sink, source.x, laneY, laneZ, source.sense, signal, source.track.planeY, ViaFlow.DOWNWARD, true, viaPolicy)
            Carried(inboundDecay + globalViaDescent(source.track, laneY, ViaFlow.DOWNWARD, viaPolicy), 0)
        }
    }

    private fun LocalRoute.placeSinkEndpoint(sink: RouteSink, endpoint: Endpoint, laneDecay: Int): Carried =
        when (endpoint) {
            is Endpoint.Cell -> {
                val descent = endpointViaDescent(endpoint, laneY, ViaFlow.UPWARD, viaPolicy)
                placeVia(
                    sink,
                    endpoint.x,
                    laneY,
                    laneZ,
                    endpoint.sense,
                    signal,
                    endpoint.position.y,
                    ViaFlow.UPWARD,
                    false,
                    viaPolicy,
                )
                val accessZ = laneZ + viaOffsets(
                    endpoint.sense,
                    laneY,
                    endpoint.position.y,
                    ViaFlow.UPWARD,
                    false,
                    viaPolicy,
                ).last().z
                routeUpperBranch(
                    sink,
                    endpoint.position,
                    accessZ,
                    endpoint.sense,
                    if (usesGlassTower(laneY, endpoint.position.y, ViaFlow.UPWARD, viaPolicy)) 0 else endpoint.branchOffsetX,
                    Direction.NORTH,
                    signal,
                    laneDecay + descent,
                    descent,
                    endpoint.requiredStrength,
                )
            }

            is Endpoint.Global -> {
                placeVia(
                    sink,
                    endpoint.x,
                    laneY,
                    laneZ,
                    endpoint.sense,
                    signal,
                    endpoint.track.planeY,
                    ViaFlow.UPWARD,
                    true,
                    viaPolicy,
                )
                Carried(laneDecay + globalViaDescent(endpoint.track, laneY, ViaFlow.UPWARD, viaPolicy), 0)
            }
        }

    private data class Carried(val decay: Int, val repeaters: Int)

    private class DelayLog {
        val pinTicks: MutableMap<BlockPos, Int> = HashMap()
        val handoffDecay: MutableMap<HandoffKey, Int> = HashMap()
        val handoffTicks: MutableMap<HandoffKey, Int> = HashMap()
        val tapDecay: MutableMap<TapKey, Int> = HashMap()
        val tapTicks: MutableMap<TapKey, Int> = HashMap()
    }

    private fun routeUpperBranch(
        sink: RouteSink,
        pin: BlockPos,
        accessZ: Int,
        sense: ViaSense,
        branchOffsetX: Int,
        travel: Direction,
        signal: Signal,
        initialDecay: Int,
        viaDecay: Int,
        requiredStrength: Int,
    ): Carried {
        val outsideZ = pin.z + 1
        val descending = travel == Direction.SOUTH
        if (sense == ViaSense.UP && branchOffsetX != 0) {
            return routeUpperDetour(
                sink,
                pin,
                accessZ,
                branchOffsetX,
                travel,
                signal,
                initialDecay,
                requiredStrength,
            )
        }
        var decayAtVia = initialDecay
        var repeaters = 0
        placeZRun(
            sink,
            pin.x,
            minOf(outsideZ, accessZ),
            maxOf(outsideZ, accessZ),
            pin.y,
            travel,
            emptySet(),
            signal,
            initialDecay = initialDecay,

            limit = if (descending) {
                technology.signalStrength - viaDecay
            } else {
                technology.signalStrength - requiredStrength
            },
            protectedPositions = setOf(accessZ),
        ) { z, decay, repeater ->
            if (z == accessZ) decayAtVia = decay
            if (repeater) repeaters++
        }
        return Carried(decayAtVia, repeaters)
    }

    private fun routeUpperDetour(
        sink: RouteSink,
        pin: BlockPos,
        accessZ: Int,
        branchOffsetX: Int,
        travel: Direction,
        signal: Signal,
        initialDecay: Int,
        requiredStrength: Int,
    ): Carried {
        require(travel == Direction.NORTH) { "a south-rising source endpoint is unsupported" }
        require(kotlin.math.abs(branchOffsetX) == 1) { "upper-plane detour must be one column" }
        val detourX = pin.x + branchOffsetX
        val outsideZ = pin.z + 1
        require(accessZ > outsideZ + 1) { "upper-plane access at Z=$accessZ cannot clear pin $pin" }
        val returnZ = accessZ - 2

        sink.place(BlockPos(detourX, pin.y, accessZ), technology.wire, technology.routeSupport, signal)
        var decayAtReturn = initialDecay + 1
        var repeaters = 0
        placeZRun(
            sink,
            detourX,
            returnZ,
            accessZ - 1,
            pin.y,
            Direction.NORTH,
            emptySet(),
            signal,
            initialDecay = decayAtReturn,
            limit = technology.signalStrength - requiredStrength,
            protectedPositions = setOf(returnZ),
        ) { z, decay, repeater ->
            if (z == returnZ) decayAtReturn = if (repeater) 0 else decay + 1
            if (repeater) repeaters++
        }
        sink.place(BlockPos(pin.x, pin.y, returnZ), technology.wire, technology.routeSupport, signal)
        decayAtReturn++
        if (returnZ > outsideZ) {
            placeZRun(
                sink,
                pin.x,
                outsideZ,
                returnZ - 1,
                pin.y,
                Direction.NORTH,
                emptySet(),
                signal,
                initialDecay = decayAtReturn,
                limit = technology.signalStrength - requiredStrength,
            ) { z, decay, repeater ->
                if (z == outsideZ) decayAtReturn = if (repeater) 0 else decay
                if (repeater) repeaters++
            }
        }
        return Carried(decayAtReturn, repeaters)
    }

    private val baseViaDescent: Int get() = technology.viaSignalOffsets.size - 1

    private fun globalViaDescent(track: GlobalTrack, laneY: Int, flow: ViaFlow, viaPolicy: ViaPolicy): Int =
        viaOffsets(ViaSense.DOWN, laneY, track.planeY, flow, true, viaPolicy).size - 1

    private fun endpointViaDescent(
        endpoint: Endpoint,
        laneY: Int,
        flow: ViaFlow,
        viaPolicy: ViaPolicy,
    ): Int = when (endpoint) {
        is Endpoint.Cell -> viaOffsets(endpoint.sense, laneY, endpoint.position.y, flow, false, viaPolicy).size - 1
        is Endpoint.Global -> globalViaDescent(endpoint.track, laneY, flow, viaPolicy)
    }

    private fun endpointLaneReserve(
        endpoint: Endpoint,
        laneY: Int,
        flow: ViaFlow,
        viaPolicy: ViaPolicy,
    ): Int = endpointViaDescent(endpoint, laneY, flow, viaPolicy) +
        if (endpoint is Endpoint.Cell) kotlin.math.abs(endpoint.branchOffsetX) else 0

    private fun placeVia(
        sink: RouteSink,
        x: Int,
        laneY: Int,
        laneZ: Int,
        sense: ViaSense,
        signal: Signal,
        targetY: Int,
        flow: ViaFlow,
        global: Boolean,
        viaPolicy: ViaPolicy,
    ) {
        val origin = BlockPos(x, laneY, laneZ)
        val support = if (!global && usesGlassTower(laneY, targetY, flow, viaPolicy)) {
            technology.routeSupport
        } else {
            technology.viaSupport
        }
        viaOffsets(sense, laneY, targetY, flow, global, viaPolicy).forEach { offset ->
            sink.place(origin + offset, technology.wire, support, signal)
        }
    }

    private fun viaOffsets(
        sense: ViaSense,
        laneY: Int,
        targetY: Int,
        flow: ViaFlow,
        global: Boolean,
        viaPolicy: ViaPolicy,
    ): List<BlockPos> {
        val baseRise = technology.viaSignalOffsets.last().y
        require(targetY - laneY >= baseRise) { "cannot route from lane Y=$laneY to target Y=$targetY" }
        if (!global && usesGlassTower(laneY, targetY, flow, viaPolicy)) {
            val direction = if (sense == ViaSense.DOWN) -1 else 1
            return (0..targetY - laneY).map { step ->
                BlockPos(0, step, if (step % 2 == 0) 0 else direction)
            }
        }
        val base = technology.viaSignalOffsets
        val last = base.last()
        val extra = targetY - laneY - baseRise
        val down = base + (1..extra).map { step ->
            BlockPos(last.x, last.y + step, last.z - step)
        }
        return when (sense) {
            ViaSense.DOWN -> down
            ViaSense.UP -> down.map { BlockPos(it.x, it.y, -it.z) }
        }
    }

    private fun usesGlassTower(
        laneY: Int,
        targetY: Int,
        flow: ViaFlow,
        viaPolicy: ViaPolicy,
    ): Boolean = viaPolicy == ViaPolicy.UPWARD_GLASS &&
        flow == ViaFlow.UPWARD &&
        targetY - laneY > technology.viaSignalOffsets.last().y

    private fun Endpoint.targetY(): Int = when (this) {
        is Endpoint.Cell -> position.y
        is Endpoint.Global -> track.planeY
    }

    private fun Endpoint.viaSense(): ViaSense = when (this) {
        is Endpoint.Cell -> sense
        is Endpoint.Global -> sense
    }

    private fun viaReach(
        endpoint: Endpoint,
        laneY: Int,
        flow: ViaFlow,
        viaPolicy: ViaPolicy,
    ): Int = viaOffsets(
        endpoint.viaSense(),
        laneY,
        endpoint.targetY(),
        flow,
        endpoint is Endpoint.Global,
        viaPolicy,
    ).maxOf { abs(it.z) }

    private inline fun placeXRun(
        sink: RouteSink,
        start: Int,
        end: Int,
        y: Int,
        z: Int,
        travel: Direction,
        forcedRepeaters: Set<Int>,
        signal: Signal,
        initialDecay: Int = 0,
        limit: Int = technology.signalStrength,
        viaColumns: Set<Int> = emptySet(),
        viaReserve: Int = baseViaDescent,
        observe: (coordinate: Int, decay: Int, repeater: Boolean) -> Unit = { _, _, _ -> },
    ) {
        require(travel == Direction.EAST || travel == Direction.WEST)
        forEachRunStep(
            start,
            end,
            travel == Direction.EAST,
            forcedRepeaters,
            initialDecay,
            limit,
            viaColumns,
            viaReserve,
        ) { x, repeater, decay ->
            sink.place(
                BlockPos(x, y, z),
                if (repeater) technology.repeater(travel) else technology.wire,
                technology.routeSupport,
                signal,
            )
            observe(x, decay, repeater)
        }
    }

    private inline fun placeZRun(
        sink: RouteSink,
        x: Int,
        start: Int,
        end: Int,
        y: Int,
        travel: Direction,
        forcedRepeaters: Set<Int>,
        signal: Signal,
        initialDecay: Int = 0,
        limit: Int = technology.signalStrength,
        protectedPositions: Set<Int> = emptySet(),
        viaReserve: Int = baseViaDescent,
        observe: (coordinate: Int, decay: Int, repeater: Boolean) -> Unit = { _, _, _ -> },
    ) {
        require(travel == Direction.NORTH || travel == Direction.SOUTH)
        forEachRunStep(
            start,
            end,
            travel == Direction.SOUTH,
            forcedRepeaters,
            initialDecay,
            limit,
            protectedPositions,
            viaReserve,
        ) { z, repeater, decay ->
            sink.place(
                BlockPos(x, y, z),
                if (repeater) technology.repeater(travel) else technology.wire,
                technology.routeSupport,
                signal,
            )
            observe(z, decay, repeater)
        }
    }

    private inline fun forEachRunStep(
        start: Int,
        end: Int,
        forward: Boolean,
        forcedRepeaters: Set<Int>,
        initialDecay: Int,
        limit: Int,
        viaColumns: Set<Int>,
        viaReserve: Int,
        step: (coordinate: Int, repeater: Boolean, decay: Int) -> Unit,
    ) {
        val stride = if (forward) 1 else -1
        val positions = if (forward) start..end else end downTo start
        var decay = initialDecay
        positions.forEach { coordinate ->

            val startsVia = coordinate in viaColumns
            val nextStartsVia = (coordinate + stride) in viaColumns
            val repeater = !startsVia && (
                coordinate in forcedRepeaters ||
                    decay + 1 >= limit ||
                    (nextStartsVia && decay + 2 + viaReserve >= technology.signalStrength)
                )
            step(coordinate, repeater, decay)
            decay = if (repeater) 0 else decay + 1
        }
    }

    private fun interface RouteSink {
        fun place(pos: BlockPos, state: BlockState, support: BlockState, signal: Signal)
    }

    private class CountingSink : RouteSink {
        private val owners = HashMap<BlockPos, Signal>()
        private val supports = HashMap<BlockPos, BlockState>()
        var repeaters: Long = 0
            private set
        var blocks: Long = 0
            private set

        override fun place(pos: BlockPos, state: BlockState, support: BlockState, signal: Signal) {
            if (pos in supports) {
                throw CandidateGeometryException("signal ${signal.index} crosses route support at $pos")
            }
            val supportPos = pos.offset(Direction.DOWN)
            if (supportPos in owners) {
                throw CandidateGeometryException("signal ${signal.index} needs support through routed wire at $supportPos")
            }
            supports.putIfAbsent(supportPos, support)
            val previous = owners.putIfAbsent(pos, signal)
            if (previous != null) {
                if (previous != signal) {
                    throw CandidateGeometryException("signals ${previous.index} and ${signal.index} overlap at $pos")
                }
                return
            }
            Direction.HORIZONTALS.forEach { direction ->
                val neighbour = owners[pos.offset(direction)]
                if (neighbour != null && neighbour != signal) {
                    throw CandidateGeometryException(
                        "signals ${neighbour.index} and ${signal.index} are not isolated at $pos",
                    )
                }
            }
            blocks++
            if (state.type.component == ComponentKind.REPEATER) repeaters++
        }
    }

    private inner class MatrixSink(private val matrix: BlockMatrix) : RouteSink {
        val owners: MutableMap<BlockPos, Signal> = mutableMapOf()

        override fun place(pos: BlockPos, state: BlockState, support: BlockState, signal: Signal) {
            val previousOwner = owners.putIfAbsent(pos, signal)
            require(previousOwner == null || previousOwner == signal) {
                "signals ${previousOwner?.index} and ${signal.index} overlap at $pos"
            }
            val supportPos = pos.offset(Direction.DOWN)
            val previousSupport = matrix.blockAt(supportPos)
            when {
                previousSupport.isAir -> matrix.placeChecked(supportPos, support)
                previousSupport == technology.routeSupport && support == technology.viaSupport -> {
                    matrix.setBlockAt(supportPos, support)
                }
                else -> require(previousSupport.type.isSolid) {
                    "$supportPos contains $previousSupport owned by signal ${owners[supportPos]?.index} " +
                        "and cannot support signal ${signal.index} at $pos"
                }
            }

            val previous = matrix.blockAt(pos)
            when {
                previous.isAir || previous == state -> matrix.placeChecked(pos, state)
                previous.type == technology.wire.type && state.type == technology.wire.type -> Unit
                else -> error(
                    "incompatible route components $previous and $state for signal ${signal.index} at $pos",
                )
            }
        }
    }

    private fun verifyRouteIsolation(owners: Map<BlockPos, Signal>) {
        owners.forEach { (pos, signal) ->
            Direction.HORIZONTALS.forEach { direction ->
                val neighbour = owners[pos.offset(direction)]
                require(neighbour == null || neighbour == signal) {
                    "signals ${signal.index} and ${neighbour?.index} can short at $pos"
                }
            }
        }
    }

    private fun IntRange.conflicts(other: IntRange, isolation: Int): Boolean =
        !(last + isolation < other.first || other.last + isolation < first)

    private data class CellSpec(
        val name: String,
        val cell: StandardCell,
        val nets: Map<String, Signal>,
        val index: Int,
        val forcedTier: Int? = null,
        val nearSignals: Set<Signal> = emptySet(),
        val forcedEdge: org.kvxd.dust.netlist.InterfaceEdge? = null,
        val panel: Boolean = false,
    )

    private data class AbutmentSeam(
        val y: Int,
        val z: Int,
        val signal: Signal,
    )

    private data class ConnectedPin(val cell: PlacedCell, val pin: CellPin, val position: BlockPos)

    private data class PinColumn(val y: Int, val x: Int, val signal: Signal)


    private sealed interface Endpoint {
        val x: Int

        data class Cell(
            val position: BlockPos,
            val allowsHorizontalAbutment: Boolean,
            val sense: ViaSense,
            val branchOffsetX: Int,
            val driveStrength: Int,
            val requiredStrength: Int,
        ) : Endpoint {
            override val x: Int get() = position.x
        }

        data class Global(val track: GlobalTrack, val sense: ViaSense, val viaX: Int) : Endpoint {
            override val x: Int get() = viaX
        }
    }

    private enum class ViaSense { UP, DOWN }
    private enum class ViaFlow { UPWARD, DOWNWARD }
    private enum class ViaPolicy { STAIRS, UPWARD_GLASS }

    private data class LocalRouteDraft(
        val signal: Signal,
        val row: Int,
        val source: Endpoint,
        val sinks: List<Endpoint>,
        val lane: Int = -1,
    ) {
        val minimumX: Int = minOf(source.x, sinks.minOf { it.x })
        val maximumX: Int = maxOf(source.x, sinks.maxOf { it.x })
        val hasSouthRisingEndpoint: Boolean = (listOf(source) + sinks)
            .filterIsInstance<Endpoint.Cell>()
            .any { it.sense == ViaSense.UP }

        fun viaClashesAcrossAdjacentLane(other: LocalRouteDraft): Boolean {
            val ours = (listOf(source) + sinks).filterIsInstance<Endpoint.Cell>()
            val theirs = (listOf(other.source) + other.sinks).filterIsInstance<Endpoint.Cell>()
            return ours.any { left -> theirs.any { right -> left.x == right.x && left.sense != right.sense } }
        }

        fun abutment(cellGap: Int): Abutment? {
            val sink = sinks.singleOrNull() ?: return null
            if (source !is Endpoint.Cell || sink !is Endpoint.Cell) return null
            if (!source.allowsHorizontalAbutment || !sink.allowsHorizontalAbutment) return null
            if (source.position.z != sink.position.z) return null
            if (maximumX - minimumX > cellGap + 1) return null
            return Abutment(signal, minimumX + 1..maximumX - 1, source.position.y, source.position.z, sink.x)
        }

        fun placeAt(rowZ: Int, laneY: Int, laneZ: Int, viaPolicy: ViaPolicy): LocalRoute = LocalRoute(
            signal,
            row,
            source.translate(rowZ),
            sinks.map { it.translate(rowZ) },
            lane,
            laneY,
            laneZ,
            viaPolicy,
        )

        private fun Endpoint.translate(z: Int): Endpoint = when (this) {
            is Endpoint.Cell -> copy(position = position + BlockPos(0, 0, z))
            is Endpoint.Global -> this
        }
    }

    private data class LocalRoute(
        val signal: Signal,
        val row: Int,
        val source: Endpoint,
        val sinks: List<Endpoint>,
        val lane: Int,
        val laneY: Int,
        val laneZ: Int,
        val viaPolicy: ViaPolicy,
    ) {
        fun southernExtent(viaReach: Int): Int {
            val endpoints = listOf(source) + sinks
            return laneZ + if (endpoints.any { it is Endpoint.Global && it.sense == ViaSense.UP }) viaReach else 0
        }
    }

    private data class Abutment(
        val signal: Signal,
        val columns: IntRange,
        val y: Int,
        val z: Int,
        val sinkX: Int,
    )

    private data class RowDraft(
        val index: Int,
        val cells: List<PlacedCell>,
        val routes: List<LocalRouteDraft>,
        val abutments: List<Abutment>,
        val cellDepth: Int,
    )

    private data class PreparedRow(
        val draft: RowDraft,
        val laneY: Int,
        val laneBase: Int,
        val depth: Int,
    )

    private data class PlacedRow(
        val index: Int,
        val cells: List<PlacedCell>,
        val routes: List<LocalRoute>,
        val abutments: List<Abutment>,
    )

    private data class GlobalSinkKey(
        val cell: String,
        val pin: String,
    )

    private data class GlobalSinkPoint(
        val key: GlobalSinkKey,
        val row: Int,
        val x: Int,
    )

    private data class GlobalTrackRequest(
        val signal: Signal,
        val ordinal: Int,
        val driverRow: Int,
        val sinkKeys: Set<GlobalSinkKey>,
        val sinkRows: Set<Int>,
        val preferredX: Int,
    ) {
        val rowSpan: IntRange = minOf(driverRow, sinkRows.min())..maxOf(driverRow, sinkRows.max())
    }

    private data class GlobalTrack(
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

    private data class HandoffKey(val track: GlobalTrack, val sense: ViaSense)

    private data class TapKey(val track: GlobalTrack, val laneY: Int, val laneZ: Int)

    private data class GlobalStart(val sense: ViaSense, val z: Int, val viaX: Int)

    private data class GlobalTap(val z: Int, val laneY: Int, val laneZ: Int, val viaX: Int)

    private data class GlobalRun(
        val signal: Signal,
        val track: GlobalTrack,
        val starts: List<GlobalStart>,
        val taps: List<GlobalTap>,
        val viaPolicy: ViaPolicy,
    )

    private data class RoutingCost(val repeaters: Long, val blocks: Long)

    private data class FloorplanCandidate(
        val plan: Floorplan,
        val partitions: List<List<CellSpec>>,
        val assignment: IntArray,
        val tierCount: Int,
        val candidate: Int,
    )

    private data class FloorplanSelection(
        val plan: Floorplan,
        val candidate: Int,
        val candidateTotal: Int,
    )

    private data class Floorplan(
        val rows: List<PlacedRow>,
        val cells: List<PlacedCell>,
        val globalTracks: List<GlobalTrack>,
        val width: Int,
        val height: Int,
        val length: Int,
        val timingCutCost: Long,
        val routingRepeaters: Long,
        val routingBlocks: Long,
        val tierCount: Int,
    ) {
        val area: Long = width.toLong() * length
        val maximumDimension: Int = maxOf(width, length)
        val selectionCost: Long = routingBlocks * ROUTING_SELECTION_WEIGHT +
            maximumDimension.toLong() * MAX_DIMENSION_SELECTION_WEIGHT + area * AREA_SELECTION_WEIGHT
    }

    private class CandidateGeometryException(message: String) : IllegalArgumentException(message)

    private companion object {

        const val SHAPE_ROW_PITCH = 12.0
        const val SMALL_EXHAUSTIVE_ROWS = 8
        const val NEAR_AFFINITY = 12
        const val EXACT_TIER_ROWS = 8
        const val TIER_ROW_IMBALANCE = 1
        const val TIER_CLEARANCE = 2
        const val TIER_BALANCE_COST = 12L
        const val TIER_BAND_SPAN_COST = 24
        const val TIER_VERTICAL_SPAN_COST = 12
        const val TIER_NEAR_WEIGHT = 8
        const val EDGE_CELL_MARGIN = 4
        const val UPWARD_GLASS_CANDIDATES = 3
        const val UPWARD_GLASS_PLACEMENT_GATE_LIMIT = 128
        const val ROUTING_SELECTION_WEIGHT = 100L
        const val MAX_DIMENSION_SELECTION_WEIGHT = 600L
        const val AREA_SELECTION_WEIGHT = 5L

        const val IO_SLOT_PITCH = 3
        const val OUTPUT_PLANE_OFFSET = 3
        const val GLOBAL_TAP_OFFSET = 1
        const val GLOBAL_TIER_VIA_PITCH = 2
        const val GLOBAL_ROW_GUARD = 1
        const val GLOBAL_PLANE_CLEARANCE = 1
        const val STEINER_MAX_TRACKS = 3
        const val STEINER_ROW_COST = 18L
        const val STEINER_TRACK_COST = 12L

    }

}
