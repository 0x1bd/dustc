package org.kvxd.dust.physical

import kotlin.math.abs
import kotlin.math.ceil
import org.kvxd.dust.device.BlockMatrix
import org.kvxd.dust.device.BlockPos
import org.kvxd.dust.device.BlockState
import org.kvxd.dust.device.ComponentKind
import org.kvxd.dust.device.Direction
import org.kvxd.dust.device.SignBlockEntity
import org.kvxd.dust.netlist.BooleanNetlist
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
    private val globalPlaneY: Int get() = technology.routeHeight + GLOBAL_PLANE_CLEARANCE

    fun compile(
        netlist: BooleanNetlist,
        io: PhysicalIo = PhysicalIo.DEBUG_PADS,
        layout: PhysicalIoLayout? = null,
    ): PhysicalDesign {
        val specs = cellInstances(netlist)
        require(specs.isNotEmpty()) { "a physical design needs at least one gate" }
        validateIoLayout(netlist, layout)
        val plan = searchFloorplan(netlist, specs, io, layout)

        val matrix = BlockMatrix(plan.width, plan.height, plan.length)
        plan.cells.forEach { technology.placeCell(matrix, it.cell, it.origin) }
        placeIoSigns(matrix, plan.cells, layout)
        val sink = MatrixSink(matrix)
        route(plan.rows, plan.globalTracks, sink)
        val owned = sink.owners
        verifyRouteIsolation(owned)

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
            measureDelays(plan.rows, plan.globalTracks),
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
        CellSpec(instance.name, cell, nets, index)
    }

    private fun searchFloorplan(
        netlist: BooleanNetlist,
        specs: List<CellSpec>,
        io: PhysicalIo,
        layout: PhysicalIoLayout?,
    ): Floorplan {
        val placer = connectivityPlacer(netlist, specs)
        val plans = rowCandidates(specs.size).flatMap { rows ->
            placer.place(rows).map { placement ->
                floorplan(
                    netlist,
                    placement.map { row -> row.map { index -> specs[index] } },
                    io,
                    layout,
                )
            }
        }
        require(plans.isNotEmpty()) { "no feasible floorplan for ${specs.size} cells" }
        return if (layout == null) {
            plans.minWith(compareBy({ it.routingBlocks }, { it.routingRepeaters }, { it.timingCutCost }, { it.area }))
        } else {
            plans.minWith(
                compareBy(
                    { it.routingBlocks },
                    { it.maximumDimension },
                    { it.area },
                    { it.routingRepeaters },
                    { it.timingCutCost },
                ),
            )
        }
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

    private fun rowCandidates(cellCount: Int): List<Int> = generateSequence(1.0) { it * ROW_LADDER_RATIO }
        .map { ceil(it).toInt() }
        .takeWhile { it <= cellCount }
        .distinct()
        .toList()

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
        gatePartitions: List<List<CellSpec>>,
        io: PhysicalIo,
        layout: PhysicalIoLayout?,
    ): Floorplan {
        val partitions = attachPads(netlist, gatePartitions, io, layout)

        val localCells = partitions.flatMapIndexed(::placeRowCells)
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
        val globalTracks = assignGlobalTracks(
            planGlobalTracks(globalSignals, localConnections),
            cellWidth,
            blockedGlobalViaColumns,
        )
        val tracksBySignal = globalTracks.groupBy { it.signal }

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
                        if (track.sinkRows.any { it > row }) sinks += Endpoint.Global(track, ViaSense.UP)
                        if (track.sinkRows.any { it < row }) sinks += Endpoint.Global(track, ViaSense.DOWN)
                    }
                    require(sinks.isNotEmpty())
                    listOf(LocalRouteDraft(signal, row, source, sinks))
                } else {
                    val sense = if (row < driver.cell.row) ViaSense.UP else ViaSense.DOWN
                    checkNotNull(tracksBySignal[signal])
                        .filter { row in it.sinkRows }
                        .map { track ->
                            val sinks = rowSinks.filter { it.globalSinkKey() in track.sinkKeys }
                                .map { it.cellEndpoint() }
                            require(sinks.isNotEmpty())
                            LocalRouteDraft(signal, row, Endpoint.Global(track, sense), sinks)
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

        val baseViaReach = technology.viaSignalOffsets.maxOf { abs(it.z) }
        val globalViaReach = abs(viaOffsets(ViaSense.DOWN, globalPlaneY).last().z)
        var nextRowZ = 0
        val rows = rowDrafts.map { draft ->
            val endpointViaReach = draft.routes
                .flatMap { route -> listOf(route.source) + route.sinks }
                .maxOfOrNull { endpoint ->
                    when (endpoint) {
                        is Endpoint.Cell -> if (endpoint.sense == ViaSense.DOWN) {
                            abs(viaOffsets(endpoint.sense, endpoint.position.y).last().z)
                        } else {
                            baseViaReach
                        }
                        is Endpoint.Global -> globalViaReach
                    }
                } ?: baseViaReach

            val laneBase = nextRowZ + draft.cellDepth + technology.isolation + endpointViaReach
            val routed = draft.routes.map { route -> route.placeAt(nextRowZ, laneBase + route.lane * technology.lanePitch) }
            val translatedCells = draft.cells.map { cell -> cell.copy(origin = cell.origin + BlockPos(0, 0, nextRowZ)) }
            val translatedAbutments = draft.abutments.map { it.copy(z = it.z + nextRowZ) }

            val southExtent = routed.maxOfOrNull {
                it.southernExtent(globalViaReach)
            } ?: (nextRowZ + draft.cellDepth - 1)
            val outputPlaneExtent = routed.maxOfOrNull { route ->
                (listOf(route.source) + route.sinks)
                    .filterIsInstance<Endpoint.Cell>()
                    .filter { it.sense == ViaSense.UP }
                    .maxOfOrNull { endpoint ->
                        route.laneZ + viaOffsets(endpoint.sense, endpoint.position.y).last().z
                    } ?: Int.MIN_VALUE
            } ?: Int.MIN_VALUE
            nextRowZ = maxOf(southExtent, outputPlaneExtent) + technology.isolation + 1
            PlacedRow(draft.index, translatedCells, routed, translatedAbutments)
        }
        val cells = rows.flatMap { it.cells }
        val width = maxOf(cellWidth, globalTracks.maxOfOrNull { maxOf(it.trunkX, it.viaX) + 1 } ?: 0)
        val height = if (globalTracks.isEmpty()) technology.routeHeight else globalPlaneY + 1

        val criticality = signalCriticality(netlist)
        val timingCost = globalSignals.sumOf { criticality[it.index].toLong() }
        val routing = routingCost(rows, globalTracks)
        return Floorplan(
            rows,
            cells,
            globalTracks,
            width,
            height,
            nextRowZ,
            timingCost,
            routing.repeaters,
            routing.blocks,
        )
    }

    private fun placeRowCells(row: Int, specs: List<CellSpec>): List<PlacedCell> {
        val rowDepth = specs.maxOf { it.cell.size.z }
        val fixed = specs.filter { it.ioX != null }
        val movable = specs.filter { it.ioX == null }
        val placed = ArrayList<PlacedCell>(specs.size)
        fixed.forEach { spec ->
            placed += PlacedCell(
                spec.name,
                spec.cell,
                BlockPos(checkNotNull(spec.ioX), technology.cellOriginY, rowDepth - spec.cell.size.z),
                row,
                spec.nets,
            )
        }
        var nextX = fixed.maxOfOrNull { checkNotNull(it.ioX) + it.cell.size.x + technology.cellGap } ?: 0
        movable.forEachIndexed { index, spec ->
            placed += PlacedCell(
                spec.name,
                spec.cell,
                BlockPos(nextX, technology.cellOriginY, rowDepth - spec.cell.size.z),
                row,
                spec.nets,
            )
            val next = movable.getOrNull(index + 1)
            nextX += spec.cell.size.x + if (next != null && canAbut(spec, next, rowDepth)) 0 else technology.cellGap
        }
        return placed
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
        if (layout != null) {
            val gateRows = IntArray(netlist.gates.size)
            gatePartitions.forEachIndexed { row, specs -> specs.forEach { gateRows[it.index] = row } }
            val inputSignals = layout.groups.filter { it.direction == PhysicalIoDirection.INPUT }.flatMap { it.signals }
            val outputSignals = layout.groups.filter { it.direction == PhysicalIoDirection.OUTPUT }.flatMap { it.signals }
            val connectedRows = buildList {
                inputSignals.forEach { name ->
                    val signal = netlist.inputs.getValue(name)
                    netlist.gates.indices.filter { signal in netlist.gates[it].inputs }.forEach { add(gateRows[it]) }
                }
                outputSignals.forEach { name ->
                    val signal = netlist.outputs.getValue(name)
                    netlist.gates.indexOfFirst { it.output == signal }.takeIf { it >= 0 }?.let { add(gateRows[it]) }
                }
            }.sorted()
            val panelRow = connectedRows.getOrNull(connectedRows.size / 2) ?: 0
            val panel = buildList {
                inputSignals.forEachIndexed { slot, name ->
                    add(
                        CellSpec(
                            "input-$name",
                            inputCell,
                            mapOf("y" to netlist.inputs.getValue(name)),
                            -1,
                            ioX = slot * IO_SLOT_PITCH,
                        ),
                    )
                }
                outputSignals.forEachIndexed { slot, name ->
                    add(
                        CellSpec(
                            "output-$name",
                            outputCell,
                            mapOf("a" to netlist.outputs.getValue(name)),
                            -1,
                            ioX = slot * IO_SLOT_PITCH,
                        ),
                    )
                }
            }
            return gatePartitions.mapIndexed { row, gates ->
                if (row == panelRow) panel + gates else gates
            }
        }
        if (io == PhysicalIo.TERMINALS) {
            return buildList {
                add(netlist.inputs.map { (name, signal) ->
                    CellSpec("input-$name", inputCell, mapOf("y" to signal), -1)
                })
                addAll(gatePartitions)
                add(netlist.outputs.map { (name, signal) ->
                    CellSpec("output-$name", outputCell, mapOf("a" to signal), -1)
                })
            }
        }
        val gateRows = IntArray(netlist.gates.size)
        gatePartitions.forEachIndexed { row, specs -> specs.forEach { gateRows[it.index] = row } }

        val inputRows = netlist.inputs.mapValues { (_, signal) ->
            netlist.gates.indices
                .filter { signal in netlist.gates[it].inputs }
                .minOfOrNull { gateRows[it] }
                ?: 0
        }
        val outputRows = netlist.outputs.mapValues { (_, signal) ->
            netlist.gates.indexOfFirst { it.output == signal }
                .takeIf { it >= 0 }
                ?.let { gateRows[it] }
                ?: inputRows.entries.singleOrNull { netlist.inputs[it.key] == signal }?.value
                ?: 0
        }

        return gatePartitions.mapIndexed { row, gates ->
            buildList {
                netlist.inputs.forEach { (name, signal) ->
                    if (inputRows.getValue(name) == row) {
                        add(CellSpec("input-$name", inputCell, mapOf("y" to signal), -1))
                    }
                }
                addAll(gates)
                netlist.outputs.forEach { (name, signal) ->
                    if (outputRows.getValue(name) == row) {
                        add(CellSpec("output-$name", outputCell, mapOf("a" to signal), -1))
                    }
                }
            }
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
    }

    private fun placeIoSigns(
        matrix: BlockMatrix,
        cells: List<PlacedCell>,
        layout: PhysicalIoLayout?,
    ) {
        if (layout == null) return
        layout.groups.forEach { group ->
            group.signals.forEach { signal ->
                val cellPrefix = if (group.direction == PhysicalIoDirection.INPUT) "input-" else "output-"
                val cell = cells.single { it.name == cellPrefix + signal }
                val position = if (group.direction == PhysicalIoDirection.INPUT) {
                    cell.origin + BlockPos(0, 0, -1)
                } else if (cell.cell.name == "output-pad") {
                    cell.origin + BlockPos(0, OUTPUT_PLANE_OFFSET, -1)
                } else {
                    cell.origin + BlockPos(0, OUTPUT_PLANE_OFFSET - 1, -1)
                }
                matrix.placeChecked(position, technology.ioSign)
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
    ): List<GlobalTrack> {
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
                    .filter { viaX -> blockedViaColumns.none { abs(it - viaX) <= technology.isolation } }
                    .flatMap { viaX ->
                        val trunkCandidates = if (request.sinkRows.size == 1) {
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
                                )
                            }
                    }
                    .filter { candidate ->
                        tracks.none { placed ->
                            rowSpansOverlap(candidate.rowSpan, placed.rowSpan) &&
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

    private fun route(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>, sink: RouteSink): DelayLog {
        val log = DelayLog()
        val allRoutes = rows.flatMap { it.routes }
        allRoutes.filter { it.source is Endpoint.Cell }.forEach { route -> route.route(sink, 0, 0, log) }
        rows.flatMap { it.abutments }.forEach { abutment ->
            abutment.columns.forEach { x ->
                sink.place(
                    BlockPos(x, technology.upperPlaneY, abutment.z),
                    technology.wire,
                    technology.routeSupport,
                    abutment.signal,
                )
            }
            log.pinTicks[BlockPos(abutment.sinkX, technology.upperPlaneY, abutment.z)] = 0
        }
        globalRuns(rows, globalTracks).forEach { run -> placeGlobalRun(sink, run, log) }
        allRoutes.filter { it.source is Endpoint.Global }.forEach { route ->
            val source = route.source as Endpoint.Global
            val key = TapKey(source.track, route.laneZ)
            route.route(
                sink,
                checkNotNull(log.tapDecay[key]) { "missing global decay for signal ${route.signal.index}" },
                log.tapTicks[key] ?: 0,
                log,
            )
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
            val handoffs = source.sinks.filterIsInstance<Endpoint.Global>()
                .filter { it.track == track }
                .associate { endpoint -> endpoint.sense to globalTapZ(source, endpoint) }
            val taps = segments.mapNotNull { route ->
                (route.source as? Endpoint.Global)?.takeIf { it.track == track }?.let { endpoint ->
                    GlobalTap(globalTapZ(route, endpoint), route.laneZ)
                }
            }
            require(taps.isNotEmpty()) { "global track for signal ${track.signal.index} has no tap" }
            require(taps.none { it.z in handoffs.values }) {
                "global track for signal ${track.signal.index} taps its own handoff"
            }
            GlobalRun(
                track.signal,
                track,
                handoffs[ViaSense.UP],
                handoffs[ViaSense.DOWN],
                taps.sortedBy { it.z },
            )
        }

    private fun globalTapZ(route: LocalRoute, endpoint: Endpoint.Global): Int =
        route.laneZ + viaOffsets(endpoint.sense, globalPlaneY).last().z

    private fun routingCost(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): RoutingCost =
        CountingSink().also { route(rows, globalTracks, it) }.let { RoutingCost(it.repeaters, it.blocks) }

    private fun placeGlobalRun(sink: RouteSink, run: GlobalRun, log: DelayLog) {
        listOf(Direction.NORTH, Direction.SOUTH).forEach { travel ->
            val south = travel == Direction.SOUTH
            val sense = if (south) ViaSense.UP else ViaSense.DOWN
            val start = (if (south) run.southStartZ else run.northStartZ) ?: return@forEach
            val taps = run.taps.filter { if (south) it.z > start else it.z < start }
            val key = HandoffKey(run.track, sense)
            placeGlobalArm(
                sink,
                run,
                start,
                if (south) taps else taps.reversed(),
                travel,
                checkNotNull(log.handoffDecay[key]) { "missing global handoff for signal ${run.signal.index}" },
                log.handoffTicks[key] ?: 0,
                log,
            )
        }
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
        val tapsByZ = taps.associateBy { it.z }
        var repeaters = 0
        placeZRun(
            sink,
            run.track.trunkX,
            minOf(startZ, endZ),
            maxOf(startZ, endZ),
            globalPlaneY,
            travel,
            emptySet(),
            run.signal,
            initialDecay = initialDecay,
            protectedPositions = protected,
            viaReserve = globalViaDescent,
        ) { z, decay, repeater ->
            if (repeater) repeaters++
            val tap = tapsByZ[z]
            if (tap != null) {
                val key = TapKey(run.track, tap.laneZ)
                log.tapDecay[key] = if (repeater) 0 else decay
                log.tapTicks[key] = inboundTicks + repeaters
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
                .maxOf { endpoint -> endpointLaneReserve(endpoint) }
            var armRepeaters = 0
            placeXRun(
                sink,
                minOf(sourceX, arm.min()),
                maxOf(sourceX, arm.max()),
                technology.lowerPlaneY,
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
            val descent = endpointViaDescent(source)
            placeVia(sink, source.x, laneZ, source.sense, signal, source.position.y)
            val accessZ = laneZ + viaOffsets(source.sense, source.position.y).last().z
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
            placeVia(sink, source.x, laneZ, source.sense, signal, globalPlaneY)
            Carried(inboundDecay + globalViaDescent, 0)
        }
    }

    private fun LocalRoute.placeSinkEndpoint(sink: RouteSink, endpoint: Endpoint, laneDecay: Int): Carried =
        when (endpoint) {
            is Endpoint.Cell -> {
                val descent = endpointViaDescent(endpoint)
                placeVia(sink, endpoint.x, laneZ, endpoint.sense, signal, endpoint.position.y)
                val accessZ = laneZ + viaOffsets(endpoint.sense, endpoint.position.y).last().z
                routeUpperBranch(
                    sink,
                    endpoint.position,
                    accessZ,
                    endpoint.sense,
                    endpoint.branchOffsetX,
                    Direction.NORTH,
                    signal,
                    laneDecay + descent,
                    descent,
                    endpoint.requiredStrength,
                )
            }

            is Endpoint.Global -> {
                placeVia(sink, endpoint.x, laneZ, endpoint.sense, signal, globalPlaneY)
                Carried(laneDecay + globalViaDescent, 0)
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
        val returnZ = outsideZ + 1
        require(accessZ > returnZ) { "upper-plane access at Z=$accessZ cannot clear pin $pin" }

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
        sink.place(
            BlockPos(pin.x, pin.y, outsideZ),
            technology.wire,
            technology.routeSupport,
            signal,
        )
        return Carried(decayAtReturn + 2, repeaters)
    }

    private val baseViaDescent: Int get() = technology.viaSignalOffsets.size - 1

    private val globalViaDescent: Int get() = viaOffsets(ViaSense.DOWN, globalPlaneY).size - 1

    private fun endpointViaDescent(endpoint: Endpoint): Int = when (endpoint) {
        is Endpoint.Cell -> viaOffsets(endpoint.sense, endpoint.position.y).size - 1
        is Endpoint.Global -> globalViaDescent
    }

    private fun endpointLaneReserve(endpoint: Endpoint): Int = endpointViaDescent(endpoint) +
        if (endpoint is Endpoint.Cell) kotlin.math.abs(endpoint.branchOffsetX) else 0

    private fun placeVia(
        sink: RouteSink,
        x: Int,
        laneZ: Int,
        sense: ViaSense,
        signal: Signal,
        targetY: Int = technology.upperPlaneY,
    ) {
        val origin = BlockPos(x, technology.lowerPlaneY, laneZ)
        viaOffsets(sense, targetY).forEach { offset ->
            sink.place(origin + offset, technology.wire, technology.viaSupport, signal)
        }
    }

    private fun viaOffsets(sense: ViaSense, targetY: Int = technology.upperPlaneY): List<BlockPos> {
        require(targetY >= technology.upperPlaneY) { "cannot route down from pin plane Y=$targetY" }
        val base = technology.viaSignalOffsets
        val last = base.last()
        val extra = targetY - technology.upperPlaneY
        val down = base + (1..extra).map { step ->
            BlockPos(last.x, last.y + step, last.z - step)
        }
        return when (sense) {
            ViaSense.DOWN -> down
            ViaSense.UP -> down.map { BlockPos(it.x, it.y, -it.z) }
        }
    }

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
        private val seen = HashSet<BlockPos>()
        var repeaters: Long = 0
            private set
        var blocks: Long = 0
            private set

        override fun place(pos: BlockPos, state: BlockState, support: BlockState, signal: Signal) {
            if (!seen.add(pos)) return
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
        val ioX: Int? = null,
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

        data class Global(val track: GlobalTrack, val sense: ViaSense) : Endpoint {
            override val x: Int get() = track.viaX
        }
    }

    private enum class ViaSense { UP, DOWN }

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
            return Abutment(signal, minimumX + 1..maximumX - 1, source.position.z, sink.x)
        }

        fun placeAt(rowZ: Int, laneZ: Int): LocalRoute = LocalRoute(
            signal,
            row,
            source.translate(rowZ),
            sinks.map { it.translate(rowZ) },
            lane,
            laneZ,
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
        val laneZ: Int,
    ) {

        fun southernExtent(viaReach: Int): Int {
            val endpoints = listOf(source) + sinks
            return laneZ + if (endpoints.any { it is Endpoint.Global && it.sense == ViaSense.UP }) viaReach else 0
        }
    }

    private data class Abutment(
        val signal: Signal,
        val columns: IntRange,
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
    ) {
        val footprint: IntRange = minOf(trunkX, viaX)..maxOf(trunkX, viaX)
        val rowSpan: IntRange = minOf(driverRow, sinkRows.min())..maxOf(driverRow, sinkRows.max())
    }

    private data class HandoffKey(val track: GlobalTrack, val sense: ViaSense)

    private data class TapKey(val track: GlobalTrack, val laneZ: Int)

    private data class GlobalTap(val z: Int, val laneZ: Int)

    private data class GlobalRun(
        val signal: Signal,
        val track: GlobalTrack,
        val southStartZ: Int?,
        val northStartZ: Int?,
        val taps: List<GlobalTap>,
    )

    private data class RoutingCost(val repeaters: Long, val blocks: Long)

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
    ) {
        val area: Long = width.toLong() * length
        val maximumDimension: Int = maxOf(width, length)
    }

    private companion object {

        const val ROW_LADDER_RATIO = 1.4

        const val IO_SLOT_PITCH = 3
        const val OUTPUT_PLANE_OFFSET = 3
        const val GLOBAL_TAP_OFFSET = 1
        const val GLOBAL_ROW_GUARD = 1
        const val GLOBAL_PLANE_CLEARANCE = 1
        const val STEINER_MAX_TRACKS = 3
        const val STEINER_ROW_COST = 18L
        const val STEINER_TRACK_COST = 12L

    }

}
