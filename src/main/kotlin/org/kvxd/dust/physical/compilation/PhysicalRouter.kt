package org.kvxd.dust.physical.compilation

import kotlin.math.abs
import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.physical.compilation.model.*
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.block.ComponentKind
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.technology.RedstoneTechnology
import org.kvxd.dust.technology.placeChecked

internal class PhysicalRouter(
    private val technology: RedstoneTechnology,
) {
    internal fun routeWork(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): Int =
        rows.sumOf { it.routes.size + it.abutments.size } + globalTracks.size

    internal fun route(
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

    internal fun measureDelays(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): Map<BlockPos, Int> =
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

    internal fun routingCost(rows: List<PlacedRow>, globalTracks: List<GlobalTrack>): RoutingCost =
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
            placeVia(
                sink,
                source.x,
                laneY,
                laneZ,
                source.sense,
                signal,
                source.position.y,
                ViaFlow.DOWNWARD,
                false,
                viaPolicy
            )
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
            placeVia(
                sink,
                source.x,
                laneY,
                laneZ,
                source.sense,
                signal,
                source.track.planeY,
                ViaFlow.DOWNWARD,
                true,
                viaPolicy
            )
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
                    if (usesGlassTower(
                            laneY,
                            endpoint.position.y,
                            ViaFlow.UPWARD,
                            viaPolicy
                        )
                    ) 0 else endpoint.branchOffsetX,
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

    internal class DelayLog {
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

    internal fun viaOffsets(
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


    internal fun viaReach(
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

    internal fun interface RouteSink {
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

    internal inner class MatrixSink(private val matrix: BlockMatrix) : RouteSink {
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

    internal fun verifyRouteIsolation(owners: Map<BlockPos, Signal>) {
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

}
