package org.kvxd.dust.netlist

import org.kvxd.dust.cell.CellBehavior
import org.kvxd.dust.cell.PortDirection

class SequentialSimulator(private val netlist: BooleanNetlist) {
    private val state: BooleanArray = BooleanArray(netlist.signals)
    private val cellState: MutableMap<CellInstance, BooleanArray> = netlist.instances
        .filter { it.type.behavior.stateBits > 0 }
        .associateWith { BooleanArray(it.type.behavior.stateBits) }
        .toMutableMap()
    private val combinational = netlist.combinationalOrder()

    fun step(values: Map<String, Boolean>): Map<String, Boolean> {
        require(values.keys == netlist.inputs.keys) { "expected inputs ${netlist.inputs.keys}, got ${values.keys}" }
        netlist.inputs.forEach { (name, signal) -> state[signal.index] = checkNotNull(values[name]) }

        evaluateCombinational()
        cellState.filterKeys { instance ->
            (instance.type.behavior as CellBehavior.Stateful).mode == CellBehavior.StateMode.EDGE_TRIGGERED
        }.forEach { (instance, previous) -> evaluateState(instance, previous) }

        val limit = maxOf(8, netlist.instances.size * 4)
        repeat(limit) {
            val before = state.copyOf()
            evaluateCombinational()
            cellState.filterKeys { instance ->
                (instance.type.behavior as CellBehavior.Stateful).mode == CellBehavior.StateMode.TRANSPARENT
            }.forEach { (instance, previous) -> evaluateState(instance, previous) }
            evaluateCombinational()
            if (before.contentEquals(state)) return outputs()
        }
        error("${netlist.name} did not settle after $limit logical iterations")
    }

    private fun evaluateState(instance: CellInstance, previous: BooleanArray) {
        val inputs = instance.type.ports
            .filter { port -> port.direction == PortDirection.INPUT }
            .associate { port ->
                port.name to instance.connections.getValue(port.name).map { state[it.index] }.toBooleanArray()
            }
        val result = instance.type.behavior.evaluate(inputs, previous)
        cellState[instance] = result.nextState.copyOf()
        netlist.writeOutputs(instance, result.outputs, state)
    }

    private fun evaluateCombinational() {
        combinational.forEach { instance ->
            val inputs = instance.type.ports
                .filter { it.direction == PortDirection.INPUT }
                .associate { port ->
                    port.name to instance.connections.getValue(port.name).map { state[it.index] }.toBooleanArray()
                }
            val result = instance.type.behavior.evaluate(inputs, BooleanArray(0))
            netlist.writeOutputs(instance, result.outputs, state)
        }
    }

    private fun outputs(): Map<String, Boolean> = netlist.outputs.mapValues { (_, signal) -> state[signal.index] }
}
