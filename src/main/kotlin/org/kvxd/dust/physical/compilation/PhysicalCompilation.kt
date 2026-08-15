package org.kvxd.dust.physical.compilation

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.physical.design.PhysicalDesign
import org.kvxd.dust.physical.compilation.model.*
import org.kvxd.dust.physical.design.RoutedNet
import org.kvxd.dust.physical.io.PhysicalIo
import org.kvxd.dust.physical.io.PhysicalIoLayout
import org.kvxd.dust.physical.progress.PhysicalProgressEvent
import org.kvxd.dust.physical.progress.PhysicalProgressListener
import org.kvxd.dust.physical.progress.PhysicalProgressStage
import org.kvxd.dust.technology.MinecraftRedstone
import org.kvxd.dust.technology.PinDirection
import org.kvxd.dust.technology.RedstoneTechnology

internal class PhysicalCompilation(
    private val technology: RedstoneTechnology = MinecraftRedstone.technology,
) {
    private val router = PhysicalRouter(technology)
    private val rowPlacer = CellRowPlacer(technology)
    private val ioCompiler = PhysicalIoCompiler(technology, rowPlacer)
    private val floorplanner = PhysicalFloorplanner(technology, rowPlacer, router)
    private val placementPlanner = PhysicalPlacementPlanner(technology, ioCompiler, floorplanner)

    fun compile(
        netlist: BooleanNetlist,
        io: PhysicalIo = PhysicalIo.DEBUG_PADS,
        layout: PhysicalIoLayout? = null,
        progress: PhysicalProgressListener = PhysicalProgressListener.NONE,
    ): PhysicalDesign {
        val specs = placementPlanner.cellInstances(netlist)
        require(specs.isNotEmpty()) { "a physical design needs at least one gate" }
        ioCompiler.validateLayout(netlist, layout)
        val selection = placementPlanner.searchFloorplan(netlist, specs, io, layout, progress)
        val plan = selection.plan

        val matrix = BlockMatrix(plan.width, plan.height, plan.length)
        plan.cells.forEach { technology.placeCell(matrix, it.cell, it.origin) }
        val sink = router.MatrixSink(matrix)
        val clockPadding = mutableMapOf<BlockPos, Int>()
        var balancingPass = 0
        while (balancingPass++ < CLOCK_BALANCING_PASSES) {
            val measured = router.measureDelays(
                plan.rows,
                plan.globalTracks,
                netlist.clockSignals,
                clockPadding,
            )
            val correction = clockPadding(plan.cells, measured)
            if (correction.values.maxOrNull() == 0) break
            correction.forEach { (position, ticks) ->
                clockPadding[position] = clockPadding.getOrDefault(position, 0) + ticks
            }
        }
        val routingWork = router.routeWork(plan.rows, plan.globalTracks)
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
        router.route(
            plan.rows,
            plan.globalTracks,
            sink,
            netlist.clockSignals,
            clockPadding,
        ) { completed, total, signal ->
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
        progress.onProgress(
            PhysicalProgressEvent(
                PhysicalProgressStage.ELECTRICAL_FINALIZATION,
                completed = 0,
                total = 1
            )
        )
        val owned = sink.owners
        router.verifyRouteIsolation(owned)
        ioCompiler.placeSigns(matrix, plan.cells, layout)

        val connections = floorplanner.connections(plan.cells)

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
        val delays = router.measureDelays(plan.rows, plan.globalTracks, netlist.clockSignals, clockPadding)
        progress.onProgress(
            PhysicalProgressEvent(
                PhysicalProgressStage.ELECTRICAL_FINALIZATION,
                completed = 1,
                total = 1
            )
        )
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
            plan.cells.flatMap { cell ->
                cell.observations.map { (name, position) -> "${cell.name}.$name" to position }
            }.toMap(),
        )
    }

    private companion object {
        const val CLOCK_BALANCING_PASSES: Int = 8
    }

}
