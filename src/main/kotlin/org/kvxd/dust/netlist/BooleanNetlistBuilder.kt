package org.kvxd.dust.netlist

import org.kvxd.dust.cell.definition.CellType
import org.kvxd.dust.cell.definition.PortDirection
import org.kvxd.dust.cell.library.BuiltinCells

class BooleanNetlistBuilder internal constructor(private val name: String) {
    private val inputs = linkedMapOf<String, Signal>()
    private val outputs = linkedMapOf<String, Signal>()
    private val gates = mutableListOf<Gate>()
    private val instances = mutableListOf<CellInstance>()
    private val placements = mutableMapOf<Signal, SignalPlacement>()
    private val terminalPlacements = mutableMapOf<Signal, SignalPlacement>()
    private val constants = mutableMapOf<Boolean, Signal>()
    private var nextSignal = 0

    fun input(name: String): Signal {
        require(name !in inputs) { "duplicate input '$name'" }
        return Signal(nextSignal++).also { inputs[name] = it }
    }

    fun inputBus(name: String, width: Int): List<Signal> {
        require(width > 0)
        return List(width) { input("$name[$it]") }
    }

    fun wire(): Signal = Signal(nextSignal++)

    fun output(name: String, signal: Signal) {
        require(name !in outputs) { "duplicate output '$name'" }
        require(signal.index in 0 until nextSignal) { "output '$name' uses an unknown signal" }
        outputs[name] = signal
    }

    fun outputBus(name: String, signals: List<Signal>) {
        require(signals.isNotEmpty())
        signals.forEachIndexed { bit, signal -> output("$name[$bit]", signal) }
    }

    fun not(input: Signal): Signal = gate(Primitive.NOT, input)

    fun and(left: Signal, right: Signal): Signal = gate(Primitive.AND2, left, right)

    fun or(left: Signal, right: Signal): Signal = gate(Primitive.OR2, left, right)

    fun xor(left: Signal, right: Signal): Signal = gate(Primitive.XOR2, left, right)

    fun latch(data: Signal, hold: Signal): Signal = gate(Primitive.LATCH, data, hold)

    fun dff(data: Signal, clock: Signal): Signal = gate(Primitive.DFF, data, clock)

    fun dffInto(data: Signal, clock: Signal, output: Signal) =
        gateInto(Primitive.DFF, listOf(data, clock), output)

    fun enabledDff(data: Signal, enable: Signal, clock: Signal): Signal {
        val output = wire()
        enabledDffInto(data, enable, clock, output)
        return output
    }

    fun enabledDffInto(data: Signal, enable: Signal, clock: Signal, output: Signal) {
        val selected = mux(enable, output, data)
        dffInto(selected, clock, output)
    }

    fun resettableDff(data: Signal, reset: Signal, clock: Signal): Signal {
        val output = wire()
        resettableDffInto(data, reset, clock, output)
        return output
    }

    fun resettableDffInto(data: Signal, reset: Signal, clock: Signal, output: Signal) {
        val selected = mux(reset, data, constant(false))
        dffInto(selected, clock, output)
    }

    fun mux(select: Signal, whenFalse: Signal, whenTrue: Signal): Signal =
        gate(Primitive.MUX2, select, whenFalse, whenTrue)

    fun constant(value: Boolean): Signal = constants.getOrPut(value) {
        instance(
            if (value) BuiltinCells.constantHigh else BuiltinCells.constantLow,
            emptyMap(),
            name = if (value) "constant-high" else "constant-low",
        ).getValue("y").single()
    }

    fun all(vararg terms: Signal): Signal {
        require(terms.isNotEmpty())
        return terms.reduce(::and)
    }

    fun any(vararg terms: Signal): Signal {
        require(terms.isNotEmpty())
        return terms.reduce(::or)
    }

    internal fun build(): BooleanNetlist {
        require(name.isNotBlank())
        require(inputs.isNotEmpty()) { "$name has no inputs" }
        require(outputs.isNotEmpty() || instances.any { instance ->
            instance.type.ports.none { it.direction == PortDirection.OUTPUT }
        }) { "$name has no outputs or physical sinks" }
        return BooleanNetlist(
            name,
            nextSignal,
            inputs.toMap(),
            outputs.toMap(),
            gates.toList(),
            instances.toList(),
            placements.toMap(),
            terminalPlacements.toMap(),
        )
    }

    fun instance(
        type: CellType,
        inputs: Map<String, List<Signal>>,
        name: String = "cell-${instances.size}-${type.id}",
    ): Map<String, List<Signal>> {
        val outputs = type.ports.filter { it.direction == PortDirection.OUTPUT }
            .associate { port -> port.name to List(port.width) { wire() } }
        connect(type, inputs + outputs, name)
        return outputs
    }

    internal fun place(signals: List<Signal>, tier: Int?, near: Set<Signal>) {
        mergePlacement(placements, signals, tier, near, null)
    }

    internal fun placeTerminals(signals: List<Signal>, tier: Int?, near: Set<Signal>, edge: InterfaceEdge?) {
        mergePlacement(terminalPlacements, signals, tier, near, edge)
    }

    private fun mergePlacement(
        target: MutableMap<Signal, SignalPlacement>,
        signals: List<Signal>,
        tier: Int?,
        near: Set<Signal>,
        edge: InterfaceEdge?,
    ) {
        require(signals.isNotEmpty())
        require(signals.all { it.index in 0 until nextSignal })
        require(near.all { it.index in 0 until nextSignal })
        signals.forEach { signal ->
            val previous = target[signal] ?: SignalPlacement()
            val mergedTier = when {
                tier == null -> previous.tier
                previous.tier == null -> tier
                previous.tier == tier -> tier
                else -> error("conflicting tier constraints for $signal: ${previous.tier} and $tier")
            }
            val mergedEdge = when {
                edge == null -> previous.edge
                previous.edge == null -> edge
                previous.edge == edge -> edge
                else -> error("conflicting edge constraints for $signal: ${previous.edge} and $edge")
            }
            target[signal] = SignalPlacement(mergedTier, previous.near + near - signal, mergedEdge)
        }
    }

    fun connect(
        type: CellType,
        connections: Map<String, List<Signal>>,
        name: String = "cell-${instances.size}-${type.id}",
    ) {
        require(connections.values.flatten().all { it.index in 0 until nextSignal }) { "cell uses an unknown signal" }
        instances += CellInstance(name, type, connections)
    }

    private fun gate(
        primitive: Primitive,
        vararg inputs: Signal,
        instanceName: String? = null,
    ): Signal {
        val expected = primitive.cellType.ports.count { it.direction == PortDirection.INPUT }
        require(inputs.size == expected)
        require(inputs.all { it.index in 0 until nextSignal }) { "gate uses an unknown signal" }
        val output = Signal(nextSignal++)
        gateInto(primitive, inputs.toList(), output, instanceName)
        return output
    }

    private fun gateInto(
        primitive: Primitive,
        inputs: List<Signal>,
        output: Signal,
        instanceName: String? = null,
    ) {
        val expected = primitive.cellType.ports.count { it.direction == PortDirection.INPUT }
        require(inputs.size == expected)
        require(inputs.all { it.index in 0 until nextSignal }) { "gate uses an unknown signal" }
        require(output.index in 0 until nextSignal) { "gate output uses an unknown signal" }
        gates += Gate(primitive, inputs.toList(), output)
        val inputPorts = primitive.cellType.ports.filter { it.direction == PortDirection.INPUT }
        val connections = buildMap {
            inputPorts.zip(inputs.asIterable()).forEach { (port, signal) -> put(port.name, listOf(signal)) }
            val outputPort = primitive.cellType.ports.single { it.direction == PortDirection.OUTPUT }
            put(outputPort.name, listOf(output))
        }
        instances += CellInstance(
            instanceName ?: "gate-${gates.lastIndex}-${primitive.name.lowercase()}",
            primitive.cellType,
            connections,
            primitive = primitive,
        )
    }
}
