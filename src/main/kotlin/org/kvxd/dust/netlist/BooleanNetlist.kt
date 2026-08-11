package org.kvxd.dust.netlist

import org.kvxd.dust.cell.definition.PortDirection

class BooleanNetlist internal constructor(
    val name: String,
    val signals: Int,
    val inputs: Map<String, Signal>,
    val outputs: Map<String, Signal>,
    val gates: List<Gate>,
    val instances: List<CellInstance>,
    internal val placements: Map<Signal, SignalPlacement> = emptyMap(),
    internal val terminalPlacements: Map<Signal, SignalPlacement> = emptyMap(),
) {
    private val orderedCombinational: List<CellInstance>

    init {
        require(instances.map { it.name }.distinct().size == instances.size) { "$name has duplicate instance names" }
        validateDrivers()
        orderedCombinational = computeCombinationalOrder()
    }

    fun evaluate(values: Map<String, Boolean>): Map<String, Boolean> {
        require(values.keys == inputs.keys) { "expected inputs ${inputs.keys}, got ${values.keys}" }
        require(instances.none { it.type.behavior.stateBits > 0 }) {
            "$name contains storage; use SequentialSimulator"
        }
        val state = BooleanArray(signals)
        inputs.forEach { (name, signal) -> state[signal.index] = checkNotNull(values[name]) }
        combinationalOrder().forEach { instance ->
            val inputValues = instance.type.ports
                .filter { it.direction == PortDirection.INPUT }
                .associate { port ->
                    port.name to instance.connections.getValue(port.name).map { state[it.index] }.toBooleanArray()
                }
            val result = instance.type.behavior.evaluate(inputValues, BooleanArray(0))
            writeOutputs(instance, result.outputs, state)
        }
        return outputs.mapValues { (_, signal) -> state[signal.index] }
    }

    fun evaluateWords(values: Map<String, Long>): Map<String, Long> {
        require(values.keys == inputs.keys) { "expected inputs ${inputs.keys}, got ${values.keys}" }
        require(gates.none { it.primitive == Primitive.LATCH }) {
            "$name contains storage; word evaluation is combinational only"
        }
        val state = LongArray(signals)
        inputs.forEach { (name, signal) -> state[signal.index] = checkNotNull(values[name]) }
        gates.forEach { gate ->
            state[gate.output.index] = when (gate.primitive) {
                Primitive.NOT -> state[gate.inputs.single().index].inv()
                Primitive.AND2 -> state[gate.inputs[0].index] and state[gate.inputs[1].index]
                Primitive.OR2 -> state[gate.inputs[0].index] or state[gate.inputs[1].index]
                Primitive.XOR2 -> state[gate.inputs[0].index] xor state[gate.inputs[1].index]
                Primitive.MUX2 -> {
                    val select = state[gate.inputs[0].index]
                    (state[gate.inputs[1].index] and select.inv()) or (state[gate.inputs[2].index] and select)
                }
                Primitive.LATCH -> error("storage requires SequentialSimulator")
            }
        }
        return outputs.mapValues { (_, signal) -> state[signal.index] }
    }

    internal fun combinationalOrder(): List<CellInstance> = orderedCombinational

    private fun computeCombinationalOrder(): List<CellInstance> {
        val combinational = instances.filter { it.type.behavior.stateBits == 0 }
        val driver = mutableMapOf<Signal, CellInstance>()
        instances.forEach { instance ->
            instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach { port ->
                instance.connections.getValue(port.name).forEach { signal ->
                    driver[signal] = instance
                }
            }
        }
        val dependencies = combinational.associateWith { instance ->
            instance.type.ports.filter { it.direction == PortDirection.INPUT }
                .flatMap { instance.connections.getValue(it.name) }
                .mapNotNull { driver[it] }
                .filter { it.type.behavior.stateBits == 0 && it !== instance }
                .toSet()
        }
        val ordered = mutableListOf<CellInstance>()
        val remaining = combinational.toMutableSet()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { dependencies.getValue(it).none { dependency -> dependency in remaining } }
            require(ready.isNotEmpty()) {
                "combinational cycle among ${remaining.take(6).joinToString { it.name }}"
            }
            ordered += ready
            remaining -= ready.toSet()
        }
        return ordered
    }

    internal fun writeOutputs(
        instance: CellInstance,
        outputs: Map<String, BooleanArray>,
        state: BooleanArray,
    ) {
        instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach { port ->
            val bits = checkNotNull(outputs[port.name]) { "${instance.name} did not produce ${port.name}" }
            require(bits.size == port.width)
            instance.connections.getValue(port.name).zip(bits.asIterable()).forEach { (signal, value) ->
                state[signal.index] = value
            }
        }
    }

    private fun validateDrivers() {
        val external = inputs.values.toSet()
        val drivers = mutableMapOf<Signal, MutableList<CellInstance>>()
        instances.forEach { instance ->
            instance.type.ports.filter { it.direction == PortDirection.OUTPUT }.forEach { port ->
                instance.connections.getValue(port.name).forEach { signal ->
                    drivers.getOrPut(signal) { mutableListOf() } += instance
                }
            }
        }
        repeat(signals) { index ->
            val signal = Signal(index)
            val found = drivers[signal].orEmpty()
            require(signal in external || found.isNotEmpty()) { "$signal has no driver" }
            require(signal !in external || found.isEmpty()) { "$signal is driven by both an input and a cell" }
            require(found.size <= 1) { "$signal has multiple drivers" }
        }
    }
}

fun booleanNetlist(name: String, define: BooleanNetlistBuilder.() -> Unit): BooleanNetlist =
    BooleanNetlistBuilder(name).apply(define).build()
