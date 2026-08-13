package org.kvxd.dust.physical.compilation.model

import org.kvxd.dust.netlist.Signal

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
