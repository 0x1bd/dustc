package org.kvxd.dust.netlist

import org.kvxd.dust.cell.behavior.CellBehavior
import org.kvxd.dust.cell.behavior.CellEvaluation
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.timing.Edge

class SequentialSimulator(private val netlist: BooleanNetlist) {
    private val state: BooleanArray = BooleanArray(netlist.signals)
    private val cellState: MutableMap<CellInstance, BooleanArray> = netlist.instances
        .filter { it.type.behavior.stateBits > 0 }
        .associateWith { BooleanArray(it.type.behavior.stateBits) }
        .toMutableMap()
    private val combinational = netlist.combinationalOrder()
    private val edgeTriggered = cellState.keys.filter { instance ->
        (instance.type.behavior as CellBehavior.Stateful).trigger is CellBehavior.Trigger.EdgeTriggered
    }
    private val generatedClocks = cellState.keys.filter { instance ->
        (instance.type.behavior as CellBehavior.Stateful).trigger is CellBehavior.Trigger.GeneratedClock
    }
    private val transparent = cellState.keys.filter { instance ->
        (instance.type.behavior as CellBehavior.Stateful).trigger == CellBehavior.Trigger.Transparent
    }
    private val previousClocks = edgeTriggered.associateWith { false }.toMutableMap()

    fun step(values: Map<String, Boolean>): Map<String, Boolean> {
        require(values.keys == netlist.inputs.keys) { "expected inputs ${netlist.inputs.keys}, got ${values.keys}" }
        netlist.inputs.forEach { (name, signal) -> state[signal.index] = checkNotNull(values[name]) }

        evaluateCombinational()
        generatedClocks.forEach { instance -> evaluateState(instance, cellState.getValue(instance)) }
        evaluateCombinational()
        val clockLevels = edgeTriggered.associateWith(::clockLevel)
        val sampled = edgeTriggered.mapNotNull { instance ->
            val trigger = (instance.type.behavior as CellBehavior.Stateful).trigger
                as CellBehavior.Trigger.EdgeTriggered
            val previous = previousClocks.getValue(instance)
            val current = clockLevels.getValue(instance)
            if (trigger.edge.matches(previous, current)) instance to evaluate(instance, cellState.getValue(instance)) else null
        }
        sampled.forEach { (instance, result) -> commit(instance, result) }

        val limit = maxOf(8, netlist.instances.size * 4)
        repeat(limit) {
            val before = state.copyOf()
            evaluateCombinational()
            transparent.forEach { instance -> evaluateState(instance, cellState.getValue(instance)) }
            evaluateCombinational()
            if (before.contentEquals(state)) {
                previousClocks.putAll(clockLevels)
                return outputs()
            }
        }
        error("${netlist.name} did not settle after $limit logical iterations")
    }

    private fun evaluateState(instance: CellInstance, previous: BooleanArray) {
        commit(instance, evaluate(instance, previous))
    }

    private fun evaluate(instance: CellInstance, previous: BooleanArray) =
        instance.type.behavior.evaluate(inputs(instance), previous)

    private fun commit(instance: CellInstance, result: CellEvaluation) {
        cellState[instance] = result.nextState.copyOf()
        netlist.writeOutputs(instance, result.outputs, state)
    }

    private fun inputs(instance: CellInstance): Map<String, BooleanArray> =
        instance.type.ports
            .filter { port -> port.direction == PortDirection.INPUT }
            .associate { port ->
                port.name to instance.connections.getValue(port.name).map { state[it.index] }.toBooleanArray()
            }

    private fun clockLevel(instance: CellInstance): Boolean {
        val trigger = (instance.type.behavior as CellBehavior.Stateful).trigger
            as CellBehavior.Trigger.EdgeTriggered
        return inputs(instance).getValue(trigger.clockPort).single()
    }

    private fun Edge.matches(previous: Boolean, current: Boolean): Boolean = when (this) {
        Edge.RISE -> !previous && current
        Edge.FALL -> previous && !current
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
