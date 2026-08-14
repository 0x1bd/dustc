package org.kvxd.dust.physical.compilation

import kotlin.math.abs
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.physical.compilation.model.*
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneTechnology
import org.kvxd.dust.timing.StaticTiming

internal class PhysicalFloorplanner(
    private val technology: RedstoneTechnology,
    private val rowPlacer: CellRowPlacer,
    private val router: PhysicalRouter,
) {
    internal fun floorplan(
        netlist: BooleanNetlist,
        partitions: List<List<CellSpec>>,
        tierAssignment: IntArray,
        tierCount: Int,
        viaPolicy: ViaPolicy,
        reserveIoSigns: Boolean,
    ): Floorplan {
        require(partitions.size == tierAssignment.size)
        val activeCellHeight = partitions.flatten().maxOf { it.cell.size.y }
        val tierPitch = if (tierCount == 1) 0 else activeCellHeight + TIER_CLEARANCE
        val preliminaryRows = partitions.mapIndexed { row, specs ->
            rowPlacer.placeRowCells(row, specs, tierAssignment[row] * tierPitch)
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
                    if (pin.accessesFromSouth) add(cell.origin.x + pin.position.x + 1)
                }
            }
        }
        val activeRouteHeight = maxOf(
            technology.upperPlaneY + (tierCount - 1) * tierPitch + 1,
            localCells.maxOf { it.origin.y + it.cell.size.y },
        )
        val activeGlobalPlaneY = activeRouteHeight + GLOBAL_PLANE_CLEARANCE
        val globalTracks = assignGlobalTracks(
            planGlobalTracks(globalSignals, localConnections, netlist.clockSignals),
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
                    val sense =
                        if (foldRows) ViaSense.DOWN else if (row < driver.cell.row) ViaSense.UP else ViaSense.DOWN
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
                    router.viaOffsets(
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
                                    router.viaOffsets(
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
                val laneBase =
                    bandCellDepths[bands[draft.index]] + technology.isolation + laneReach + tierLaneOffsets[draft.index]
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
                            route.laneZ + router.viaOffsets(
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
                        router.viaReach(route.source, laneY, ViaFlow.DOWNWARD, viaPolicy),
                        route.sinks.maxOfOrNull { router.viaReach(it, laneY, ViaFlow.UPWARD, viaPolicy) } ?: 0,
                    )
                } ?: 0
                val laneBase =
                    bandCellDepths[bands[draft.index]] + technology.isolation + laneReach + tierLaneOffsets[draft.index]
                val routes = draft.routes.map { route ->
                    route.placeAt(0, laneY, laneBase + route.lane * technology.lanePitch, viaPolicy)
                }
                val southExtent = routes.maxOfOrNull { route ->
                    maxOf(
                        route.laneZ + router.viaOffsets(
                            route.source.viaSense(),
                            laneY,
                            route.source.targetY(),
                            ViaFlow.DOWNWARD,
                            route.source is Endpoint.Global,
                            viaPolicy,
                        ).maxOf { it.z },
                        route.sinks.maxOfOrNull { endpoint ->
                            route.laneZ + router.viaOffsets(
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
        val bandSignMargins = IntArray(bandCount)
        if (reserveIoSigns) {
            rowDrafts.forEach { draft ->
                if (draft.cells.any { it.cell.name == "input-pad" || it.cell.name == "output-pad" }) {
                    bandSignMargins[bands[draft.index]] = 1
                }
            }
        }
        val bandStarts = IntArray(bandCount)
        if (bandCount > 0) bandStarts[0] = bandSignMargins[0]
        for (band in 1 until bandCount) {
            bandStarts[band] = bandStarts[band - 1] + bandDepths[band - 1] + bandSignMargins[band]
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
        val length = bandDepths.sum() + bandSignMargins.sum()

        val criticality = signalCriticality(netlist)
        val timingCost = globalSignals.sumOf { criticality[it.index].toLong() }
        val (routing, routeDelays) = router.routingEvaluation(rows, globalTracks, netlist.clockSignals)
        val placedConnections = connections(cells)
        val clockSkew = netlist.clockSignals.maxOfOrNull { signal ->
            val arrivals = placedConnections.getValue(signal).mapNotNull { connected ->
                val trigger = (connected.cell.cell.logicalType.behavior as? CellBehavior.Stateful)?.trigger
                    as? CellBehavior.Trigger.EdgeTriggered
                if (trigger?.clockPort == connected.pin.port) routeDelays[connected.position] ?: 0 else null
            }
            (arrivals.maxOrNull() ?: 0) - (arrivals.minOrNull() ?: 0)
        } ?: 0
        val timing = if (netlist.instances.any { it.type.behavior is CellBehavior.Stateful }) {
            StaticTiming.analyse(
                netlist,
                cells,
                balancedClockDelays(cells, routeDelays),
                includePrimaryIoPaths = false,
            )
        } else {
            null
        }
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
            clockSkew,
            timing,
        )
    }


    private fun planGlobalTracks(
        globalSignals: List<Signal>,
        connections: Map<Signal, List<ConnectedPin>>,
        clockSignals: Set<Signal>,
    ): List<GlobalTrackRequest> = globalSignals.flatMap { signal ->
        val pins = checkNotNull(connections[signal])
        val driver = pins.single { it.pin.direction == PinDirection.OUTPUT }
        val sinkPoints = pins.filter { it.pin.direction == PinDirection.INPUT && it.cell.row != driver.cell.row }
            .map { GlobalSinkPoint(it.globalSinkKey(), it.cell.row, it.position.x) }
            .sortedWith(compareBy<GlobalSinkPoint> { it.x }.thenBy { it.row }.thenBy { it.key.cell })
        val partitions = if (signal in clockSignals) listOf(sinkPoints) else {
            globalTrackPartitions(driver.cell.row, driver.position.x, sinkPoints)
        }
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

    internal fun connections(cells: List<PlacedCell>): Map<Signal, List<ConnectedPin>> {
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
        ((cell.cell.logicalType.behavior as? CellBehavior.Stateful)?.trigger
            as? CellBehavior.Trigger.EdgeTriggered)?.clockPort == pin.port,
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

}
