package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.device.geometry.BlockPos
import org.kvxd.dust.netlist.Signal

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
