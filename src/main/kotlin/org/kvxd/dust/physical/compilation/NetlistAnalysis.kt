package org.kvxd.dust.physical.compilation

import org.kvxd.dust.netlist.BooleanNetlist

internal fun signalCriticality(netlist: BooleanNetlist): IntArray {
    val remaining = IntArray(netlist.signals)
    netlist.instances.asReversed().forEach { instance ->
        val outputs = instance.type.ports.filter { it.direction == org.kvxd.dust.cell.definition.PortDirection.OUTPUT }
            .flatMap { instance.connections.getValue(it.name) }
        val latency = instance.type.timing.arcs.maxOfOrNull {
            maxOf(it.rise.maxTicks, it.fall.maxTicks)
        } ?: 0
        instance.type.ports.filter { it.direction == org.kvxd.dust.cell.definition.PortDirection.INPUT }
            .forEach { port ->
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
