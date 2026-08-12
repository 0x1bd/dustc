package org.kvxd.dust.physical.compilation

import org.kvxd.dust.device.block.BlockMatrix
import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.device.block.BlockState
import org.kvxd.dust.device.geometry.Direction
import org.kvxd.dust.device.property.Properties
import org.kvxd.dust.device.block.SignBlockEntity
import org.kvxd.dust.netlist.BooleanNetlist
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.physical.io.PhysicalIo
import org.kvxd.dust.physical.io.PhysicalIoDirection
import org.kvxd.dust.physical.io.PhysicalIoEdge
import org.kvxd.dust.physical.io.PhysicalIoLayout
import org.kvxd.dust.technology.RedstoneTechnology
import org.kvxd.dust.technology.placeChecked

internal class PhysicalIoCompiler(
    private val technology: RedstoneTechnology,
    private val rowPlacer: CellRowPlacer,
) {
    internal fun attachPads(
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
                val anchor =
                    if (producer >= 0) gatePositions[producer] else gatePartitions[row].lastIndex.coerceAtLeast(0)
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
                if (slot in 1 until gates.size && rowPlacer.canAbut(gates[slot - 1], gates[slot], rowDepth)) {
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

    internal fun validateLayout(netlist: BooleanNetlist, layout: PhysicalIoLayout?) {
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

    internal fun placeSigns(
        matrix: BlockMatrix,
        cells: List<PlacedCell>,
        layout: PhysicalIoLayout?,
    ) {
        if (layout == null) return
        layout.groups.forEach { group ->
            group.signals.forEach signalLoop@{ signal ->
                val cellPrefix = if (group.direction == PhysicalIoDirection.INPUT) "input-" else "output-"
                val cell = cells.single { it.name == cellPrefix + signal }
                val direct = when (cell.cell.name) {
                    "input-pad" -> cell.origin + BlockPos(0, 0, -1)
                    "output-pad" -> cell.origin + BlockPos(0, OUTPUT_PLANE_OFFSET, -1)
                    else -> null
                }
                val position: BlockPos
                val state: BlockState
                if (direct != null) {
                    require(matrix.contains(direct) && matrix.blockAt(direct).isAir) {
                        "I/O sign position $direct for ${cell.name} is unavailable"
                    }
                    position = direct
                    state = technology.ioSign.with(Properties.FACING, Direction.NORTH)
                } else {
                    val y = if (group.direction == PhysicalIoDirection.INPUT) {
                        cell.origin.y
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
                    position = candidates.firstOrNull { matrix.contains(it) && matrix.blockAt(it).isAir }
                        ?: return@signalLoop
                    state =
                        group.edge?.let { technology.ioSign.with(Properties.FACING, it.outward) } ?: technology.ioSign
                }
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

}
