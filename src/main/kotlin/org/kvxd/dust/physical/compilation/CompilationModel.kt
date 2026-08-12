package org.kvxd.dust.physical.compilation

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.design.PlacedCell
import org.kvxd.dust.technology.CellPin
import org.kvxd.dust.technology.StandardCell

internal data class CellSpec(
    val name: String,
    val cell: StandardCell,
    val nets: Map<String, Signal>,
    val index: Int,
    val forcedTier: Int? = null,
    val nearSignals: Set<Signal> = emptySet(),
    val forcedEdge: org.kvxd.dust.netlist.InterfaceEdge? = null,
    val panel: Boolean = false,
)

internal data class AbutmentSeam(
    val y: Int,
    val z: Int,
    val signal: Signal,
)

internal data class ConnectedPin(val cell: PlacedCell, val pin: CellPin, val position: BlockPos)

internal data class PinColumn(val y: Int, val x: Int, val signal: Signal)


internal sealed interface Endpoint {
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

internal enum class ViaSense { UP, DOWN }
internal enum class ViaFlow { UPWARD, DOWNWARD }
internal enum class ViaPolicy { STAIRS, UPWARD_GLASS }

internal data class LocalRouteDraft(
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

internal data class LocalRoute(
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

internal data class Abutment(
    val signal: Signal,
    val columns: IntRange,
    val y: Int,
    val z: Int,
    val sinkX: Int,
)

internal data class RowDraft(
    val index: Int,
    val cells: List<PlacedCell>,
    val routes: List<LocalRouteDraft>,
    val abutments: List<Abutment>,
    val cellDepth: Int,
)

internal data class PreparedRow(
    val draft: RowDraft,
    val laneY: Int,
    val laneBase: Int,
    val depth: Int,
)

internal data class PlacedRow(
    val index: Int,
    val cells: List<PlacedCell>,
    val routes: List<LocalRoute>,
    val abutments: List<Abutment>,
)

internal data class GlobalSinkKey(
    val cell: String,
    val pin: String,
)

internal data class GlobalSinkPoint(
    val key: GlobalSinkKey,
    val row: Int,
    val x: Int,
)

internal data class GlobalTrackRequest(
    val signal: Signal,
    val ordinal: Int,
    val driverRow: Int,
    val sinkKeys: Set<GlobalSinkKey>,
    val sinkRows: Set<Int>,
    val preferredX: Int,
) {
    val rowSpan: IntRange = minOf(driverRow, sinkRows.min())..maxOf(driverRow, sinkRows.max())
}

internal data class GlobalTrack(
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

internal data class HandoffKey(val track: GlobalTrack, val sense: ViaSense)

internal data class TapKey(val track: GlobalTrack, val laneY: Int, val laneZ: Int)

internal data class GlobalStart(val sense: ViaSense, val z: Int, val viaX: Int)

internal data class GlobalTap(val z: Int, val laneY: Int, val laneZ: Int, val viaX: Int)

internal data class GlobalRun(
    val signal: Signal,
    val track: GlobalTrack,
    val starts: List<GlobalStart>,
    val taps: List<GlobalTap>,
    val viaPolicy: ViaPolicy,
)

internal data class RoutingCost(val repeaters: Long, val blocks: Long)

internal data class FloorplanCandidate(
    val plan: Floorplan,
    val partitions: List<List<CellSpec>>,
    val assignment: IntArray,
    val tierCount: Int,
    val candidate: Int,
)

internal data class FloorplanSelection(
    val plan: Floorplan,
    val candidate: Int,
    val candidateTotal: Int,
)

internal data class Floorplan(
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

internal class CandidateGeometryException(message: String) : IllegalArgumentException(message)

internal fun Endpoint.targetY(): Int = when (this) {
    is Endpoint.Cell -> position.y
    is Endpoint.Global -> track.planeY
}

internal fun Endpoint.viaSense(): ViaSense = when (this) {
    is Endpoint.Cell -> sense
    is Endpoint.Global -> sense
}

internal fun IntRange.conflicts(other: IntRange, isolation: Int): Boolean =
    !(last + isolation < other.first || other.last + isolation < first)

internal const val SHAPE_ROW_PITCH = 12.0
internal const val SMALL_EXHAUSTIVE_ROWS = 8
internal const val NEAR_AFFINITY = 12
internal const val EXACT_TIER_ROWS = 8
internal const val TIER_ROW_IMBALANCE = 1
internal const val TIER_CLEARANCE = 2
internal const val TIER_BALANCE_COST = 12L
internal const val TIER_BAND_SPAN_COST = 24
internal const val TIER_VERTICAL_SPAN_COST = 12
internal const val TIER_NEAR_WEIGHT = 8
internal const val EDGE_CELL_MARGIN = 4
internal const val UPWARD_GLASS_CANDIDATES = 3
internal const val UPWARD_GLASS_PLACEMENT_GATE_LIMIT = 128
internal const val ROUTING_SELECTION_WEIGHT = 100L
internal const val MAX_DIMENSION_SELECTION_WEIGHT = 600L
internal const val AREA_SELECTION_WEIGHT = 5L

internal const val IO_SLOT_PITCH = 3
internal const val OUTPUT_PLANE_OFFSET = 3
internal const val GLOBAL_TAP_OFFSET = 1
internal const val GLOBAL_TIER_VIA_PITCH = 2
internal const val GLOBAL_ROW_GUARD = 1
internal const val GLOBAL_PLANE_CLEARANCE = 1
internal const val STEINER_MAX_TRACKS = 3
internal const val STEINER_ROW_COST = 18L
internal const val STEINER_TRACK_COST = 12L
