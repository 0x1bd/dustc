package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.Circuit
import org.kvxd.dust.CircuitPort
import org.kvxd.dust.CircuitPortDirection
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AccessSyntax
import org.kvxd.dust.lang.syntax.AssignmentSyntax
import org.kvxd.dust.lang.syntax.AttributeSyntax
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.BlockSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.ForSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.lang.syntax.PortSyntax
import org.kvxd.dust.lang.syntax.StatementSyntax
import org.kvxd.dust.lang.syntax.UnarySyntax
import org.kvxd.dust.lang.syntax.VariableSyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.io.PhysicalIoEdge

internal class Elaborator(
    modules: List<ModuleSyntax>,
    private val reporter: DiagnosticReporter,
) {
    private val modules = modules.associateBy { it.name }

    fun build(module: ModuleSyntax): Circuit {
        val builder = BooleanNetlistBuilder(module.name)
        val ports = validatePorts(module)
        val inputs = ports.filter { it.direction == CircuitPortDirection.INPUT }.associate { port ->
            port.name to Value.Signals(
                if (port.width == 1) listOf(builder.input(port.name)) else builder.inputBus(port.name, port.width),
            )
        }
        val outputs = elaborate(module, builder, inputs, listOf(module.name), applyTerminalPlacement = true)
        ports.filter { it.direction == CircuitPortDirection.OUTPUT }.forEach { port ->
            val signals = (outputs.getValue(port.name) as Value.Signals).signals
            if (signals.size == 1) builder.output(port.name, signals.single()) else builder.outputBus(port.name, signals)
        }
        val netlist = try {
            builder.build()
        } catch (exception: IllegalArgumentException) {
            fail(module.location, exception.message ?: "invalid module")
        }
        return try {
            Circuit(module.name, ports, netlist)
        } catch (exception: IllegalArgumentException) {
            fail(module.location, exception.message ?: "invalid module")
        }
    }

    private fun validatePorts(module: ModuleSyntax): List<CircuitPort> {
        val names = hashSetOf<String>()
        return module.ports.map { port ->
            if (!names.add(port.name)) fail(port.location, "duplicate port '${port.name}'")
            try {
                val placement = placementAttributes(port.attributes)
                if (placement.panel && port.group == null) {
                    fail(port.location, "#[panel] requires a named top-level I/O group")
                }
                if (placement.panel && placement.edge in setOf(InterfaceEdge.EAST, InterfaceEdge.WEST)) {
                    fail(port.location, "#[panel] currently supports north/south edges; omit #[edge] for automatic panel placement")
                }
                CircuitPort(
                    port.name,
                    port.width,
                    if (port.direction == PortDirection.INPUT) {
                        CircuitPortDirection.INPUT
                    } else {
                        CircuitPortDirection.OUTPUT
                    },
                    port.group,
                    placement.edge?.let { PhysicalIoEdge.valueOf(it.name) },
                    placement.panel,
                )
            } catch (exception: IllegalArgumentException) {
                fail(port.location, exception.message ?: "invalid port")
            }
        }.also { ports ->
            if (ports.none { it.direction == CircuitPortDirection.INPUT }) fail(module.location, "module has no inputs")
            if (ports.none { it.direction == CircuitPortDirection.OUTPUT }) fail(module.location, "module has no outputs")
        }
    }

    private fun elaborate(
        module: ModuleSyntax,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, Value>,
        callStack: List<String>,
        applyTerminalPlacement: Boolean,
    ): Map<String, Value> {
        val environment = Environment()
        inputs.forEach { (name, value) -> environment.bindings[name] = Binding(value, mutable = false) }
        module.ports.filter { it.direction == PortDirection.INPUT && it.group != null }
            .groupBy { checkNotNull(it.group) }
            .forEach { (group, ports) ->
                environment.placementTargets[group] = Value.Signals(
                    ports.flatMap { port -> (inputs.getValue(port.name) as Value.Signals).signals },
                )
            }
        val outputPorts = module.ports.filter { it.direction == PortDirection.OUTPUT }
        val outputs = outputPorts.associate { port ->
            port.name to OutputBinding(port, MutableList(port.width) { null })
        }
        execute(module.body, environment, outputs, builder, callStack)
        val result = outputs.mapValues { (_, output) ->
            val missing = output.signals.indices.filter { output.signals[it] == null }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString { bit ->
                    if (output.signals.size == 1) output.port.name else "${output.port.name}[$bit]"
                }
                fail(output.port.location, "unassigned output $names")
            }
            Value.Signals(output.signals.map(::checkNotNull))
        }
        if (applyTerminalPlacement) applyPortPlacement(module, builder, inputs + result)
        return result
    }

    private fun execute(
        block: BlockSyntax,
        parent: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<String>,
    ) {
        val environment = Environment(parent)
        block.statements.forEach { statement ->
            when (statement) {
                is VariableSyntax -> {
                    if (environment.bindings.containsKey(statement.name) || outputs.containsKey(statement.name)) {
                        fail(statement.location, "'${statement.name}' is already declared")
                    }
                    if (statement.mutable && statement.attributes.isNotEmpty()) {
                        fail(statement.location, "placement attributes cannot be attached to a mutable binding")
                    }
                    val value = evaluate(statement.initializer, environment, outputs, builder, callStack)
                    if (statement.attributes.isNotEmpty()) {
                        val placement = placementAttributes(statement.attributes)
                        if (placement.edge != null) fail(statement.location, "#[edge] is only supported on top-level I/O")
                        if (placement.panel) fail(statement.location, "#[panel] is only supported on top-level I/O groups")
                        val targets = placement.near.flatMapTo(linkedSetOf()) { target ->
                            val targetValue = environment.find(target)?.value ?: environment.findPlacementTarget(target)
                            ?: fail(statement.location, "unknown placement target '$target'")
                            placementSignals(targetValue, statement.location)
                        }
                        builder.place(placementSignals(value, statement.location), placement.tier, targets)
                    }
                    environment.bindings[statement.name] = Binding(value, statement.mutable)
                }
                is AssignmentSyntax -> assign(statement, environment, outputs, builder, callStack)
                is ForSyntax -> {
                    val first = integer(statement.first, environment, outputs, builder, callStack)
                    val parsedEnd = integer(statement.end, environment, outputs, builder, callStack)
                    val endExclusive = if (statement.inclusive) parsedEnd + 1 else parsedEnd
                    if (first > endExclusive) fail(statement.location, "loop range must count upward")
                    repeat(endExclusive - first) { offset ->
                        val iteration = Environment(environment)
                        iteration.bindings[statement.index] = Binding(Value.Integer(first + offset), mutable = false)
                        execute(statement.body, iteration, outputs, builder, callStack)
                    }
                }
                is BlockSyntax -> execute(statement, environment, outputs, builder, callStack)
                is ExpressionSyntax -> fail(statement.location, "unused expression")
            }
        }
    }

    private fun assign(
        assignment: AssignmentSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<String>,
    ) {
        val value = evaluate(assignment.value, environment, outputs, builder, callStack)
        when (val target = assignment.target) {
            is NameSyntax -> {
                outputs[target.name]?.let { output ->
                    connect(output, null, value, target.location)
                    return
                }
                val binding = environment.find(target.name) ?: fail(target.location, "unknown signal '${target.name}'")
                if (!binding.mutable) fail(target.location, "'${target.name}' is immutable")
                binding.value = value
            }
            is IndexSyntax -> {
                val name = target.target as? NameSyntax
                    ?: fail(target.location, "only a named bus can be assigned by index")
                val index = integer(target.index, environment, outputs, builder, callStack)
                outputs[name.name]?.let { output ->
                    connect(output, index, value, target.location)
                    return
                }
                val binding = environment.find(name.name) ?: fail(name.location, "unknown signal '${name.name}'")
                if (!binding.mutable) fail(name.location, "'${name.name}' is immutable")
                val signals = binding.value as? Value.Signals ?: fail(name.location, "'${name.name}' is not a bus")
                val assigned = value as? Value.Signals ?: fail(assignment.value.location, "expected a bit")
                if (index !in signals.signals.indices) fail(target.location, "index $index is out of bounds")
                if (assigned.signals.size != 1) fail(assignment.value.location, "a bus cannot be assigned to one bit")
                binding.value = Value.Signals(signals.signals.toMutableList().also { it[index] = assigned.signals.single() })
            }
            else -> fail(target.location, "assignment target must be a signal or bus bit")
        }
    }

    private fun connect(output: OutputBinding, index: Int?, value: Value, location: Token) {
        val signals = (value as? Value.Signals)?.signals ?: fail(location, "an output needs a bit value")
        if (index == null) {
            if (signals.size != output.signals.size) {
                fail(location, "${output.port.name} needs ${output.signals.size} bits, got ${signals.size}")
            }
            signals.forEachIndexed { bit, signal -> connectBit(output, bit, signal, location) }
        } else {
            if (index !in output.signals.indices) fail(location, "${output.port.name} has no bit $index")
            if (signals.size != 1) fail(location, "a bus cannot be assigned to one output bit")
            connectBit(output, index, signals.single(), location)
        }
    }

    private fun connectBit(output: OutputBinding, bit: Int, signal: Signal, location: Token) {
        if (output.signals[bit] != null) {
            val name = if (output.signals.size == 1) output.port.name else "${output.port.name}[$bit]"
            fail(location, "$name is already assigned")
        }
        output.signals[bit] = signal
    }

    private fun evaluate(
        expression: ExpressionSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<String>,
    ): Value = when (expression) {
        is IntegerSyntax -> Value.Integer(expression.value)
        is NameSyntax -> {
            if (outputs.containsKey(expression.name)) fail(expression.location, "outputs cannot be read while building")
            environment.find(expression.name)?.value ?: fail(expression.location, "unknown signal '${expression.name}'")
        }
        is IndexSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val signals = target as? Value.Signals ?: fail(expression.target.location, "only a bus can be indexed")
            val index = integer(expression.index, environment, outputs, builder, callStack)
            if (index !in signals.signals.indices) fail(expression.location, "index $index is out of bounds")
            Value.Signals(listOf(signals.signals[index]))
        }
        is AccessSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val bundle = target as? Value.Bundle ?: fail(expression.target.location, "only a module result has outputs")
            bundle.outputs[expression.member] ?: fail(expression.location, "module has no output '${expression.member}'")
        }
        is UnarySyntax -> {
            val operand = signals(evaluate(expression.operand, environment, outputs, builder, callStack), expression.location)
            Value.Signals(operand.map(builder::not))
        }
        is BinarySyntax -> {
            val left = signals(evaluate(expression.left, environment, outputs, builder, callStack), expression.left.location)
            val right = signals(evaluate(expression.right, environment, outputs, builder, callStack), expression.right.location)
            if (left.size != right.size) {
                fail(expression.location, "gate operands have widths ${left.size} and ${right.size}")
            }
            Value.Signals(left.zip(right) { a, b ->
                when (expression.operator) {
                    TokenType.AMP, TokenType.AND -> builder.and(a, b)
                    TokenType.PIPE, TokenType.OR -> builder.or(a, b)
                    TokenType.CARET -> builder.xor(a, b)
                    else -> error("unexpected gate operator ${expression.operator}")
                }
            })
        }
        is CallSyntax -> call(expression, environment, outputs, builder, callStack)
    }

    private fun call(
        call: CallSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<String>,
    ): Value {
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        when (call.name) {
            "latch" -> {
                if (arguments.size != 2 || arguments.any { it !is Value.Signals || it.signals.size != 1 }) {
                    fail(call.location, "latch(data, hold) needs two bits")
                }
                return Value.Signals(
                    listOf(builder.latch((arguments[0] as Value.Signals).signals.single(), (arguments[1] as Value.Signals).signals.single())),
                )
            }
            "mux" -> {
                if (arguments.size != 3) fail(call.location, "mux(select, low, high) needs three arguments")
                val select = signals(arguments[0], call.location)
                val low = signals(arguments[1], call.location)
                val high = signals(arguments[2], call.location)
                if (select.size != 1 || low.size != high.size) {
                    fail(call.location, "mux needs one select bit and equal-width values")
                }
                return Value.Signals(low.zip(high) { a, b -> builder.mux(select.single(), a, b) })
            }
        }

        val module = modules[call.name] ?: fail(call.location, "unknown gate or module '${call.name}'")
        if (module.name in callStack) {
            fail(call.location, "recursive module call ${callStack.plus(module.name).joinToString(" -> ")}")
        }
        val inputPorts = module.ports.filter { it.direction == PortDirection.INPUT }
        if (arguments.size != inputPorts.size) {
            fail(call.location, "${module.name} needs ${inputPorts.size} inputs, got ${arguments.size}")
        }
        val bound = inputPorts.zip(arguments).associate { (port, value) ->
            val connected = signals(value, call.location)
            if (connected.size != port.width) {
                fail(call.location, "${module.name}.${port.name} needs ${port.width} bits, got ${connected.size}")
            }
            port.name to Value.Signals(connected)
        }
        return Value.Bundle(elaborate(module, builder, bound, callStack + module.name, applyTerminalPlacement = false))
    }

    private fun applyPortPlacement(
        module: ModuleSyntax,
        builder: BooleanNetlistBuilder,
        values: Map<String, Value>,
    ) {
        val targets = values.toMutableMap()
        module.ports.filter { it.group != null }.groupBy { checkNotNull(it.group) }.forEach { (group, ports) ->
            targets[group] = Value.Signals(ports.flatMap { port -> placementSignals(values.getValue(port.name), port.location) })
        }
        module.ports.forEach { port ->
            if (port.attributes.isEmpty()) return@forEach
            val placement = placementAttributes(port.attributes)
            val near = placement.near.flatMapTo(linkedSetOf()) { target ->
                val targetValue = targets[target] ?: fail(port.location, "unknown placement target '$target'")
                placementSignals(targetValue, port.location)
            }
            builder.placeTerminals(placementSignals(values.getValue(port.name), port.location), placement.tier, near, placement.edge)
        }
    }

    private fun placementAttributes(attributes: List<AttributeSyntax>): PlacementAttributes {
        var tier: Int? = null
        var edge: InterfaceEdge? = null
        var panel = false
        val near = linkedSetOf<String>()
        attributes.forEach { attribute ->
            when (attribute.name) {
                "tier" -> {
                    if (tier != null) fail(attribute.location, "duplicate #[tier] attribute")
                    if (attribute.arguments.size != 1 || attribute.arguments.single().type != TokenType.INT) {
                        fail(attribute.location, "#[tier] expects one non-negative integer")
                    }
                    val token = attribute.arguments.single()
                    val value = parseAttributeInteger(token)
                    if (value < 0) fail(token, "tier must be non-negative")
                    tier = value
                }
                "near" -> {
                    if (attribute.arguments.isEmpty() || attribute.arguments.any { it.type != TokenType.ID }) {
                        fail(attribute.location, "#[near] expects one or more placement target names")
                    }
                    attribute.arguments.forEach { near += it.value }
                }
                "edge" -> {
                    if (edge != null) fail(attribute.location, "duplicate #[edge] attribute")
                    if (attribute.arguments.size != 1 || attribute.arguments.single().type != TokenType.ID) {
                        fail(attribute.location, "#[edge] expects one of north, south, east, west")
                    }
                    edge = when (val value = attribute.arguments.single().value) {
                        "north" -> InterfaceEdge.NORTH
                        "south" -> InterfaceEdge.SOUTH
                        "east" -> InterfaceEdge.EAST
                        "west" -> InterfaceEdge.WEST
                        else -> fail(attribute.arguments.single(), "invalid edge '$value'; expected north, south, east, or west")
                    }
                }
                "panel" -> {
                    if (panel) fail(attribute.location, "duplicate #[panel] attribute")
                    if (attribute.arguments.isNotEmpty()) {
                        fail(attribute.location, "#[panel] does not take arguments")
                    }
                    panel = true
                }
                else -> fail(attribute.location, "unknown placement attribute '#[${attribute.name}]'")
            }
        }
        return PlacementAttributes(tier, near.toList(), edge, panel)
    }

    private fun parseAttributeInteger(token: Token): Int {
        val value = when {
            token.value.startsWith("0x", ignoreCase = true) -> token.value.drop(2).toIntOrNull(16)
            token.value.startsWith("0b", ignoreCase = true) -> token.value.drop(2).toIntOrNull(2)
            else -> token.value.toIntOrNull()
        }
        return value ?: fail(token, "integer '${token.value}' does not fit in 32 bits")
    }

    private fun placementSignals(value: Value, location: Token): List<Signal> = when (value) {
        is Value.Signals -> value.signals
        is Value.Bundle -> value.outputs.values.flatMap { placementSignals(it, location) }
        is Value.Integer -> fail(location, "placement attributes require a signal, bus, or module output bundle")
    }

    private fun signals(value: Value, location: Token): List<Signal> =
        (value as? Value.Signals)?.signals ?: fail(location, "expected a bit or bus")

    private fun integer(
        expression: ExpressionSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<String>,
    ): Int = (evaluate(expression, environment, outputs, builder, callStack) as? Value.Integer)?.value
        ?: fail(expression.location, "expected a compile-time integer")

    private fun fail(location: Token, message: String): Nothing {
        reporter.error(message, location)
        throw ElaborationError()
    }

    private sealed interface Value {
        data class Signals(val signals: List<Signal>) : Value
        data class Bundle(val outputs: Map<String, Value>) : Value
        data class Integer(val value: Int) : Value
    }

    private data class PlacementAttributes(
        val tier: Int?,
        val near: List<String>,
        val edge: InterfaceEdge?,
        val panel: Boolean,
    )
    private data class Binding(var value: Value, val mutable: Boolean)
    private data class OutputBinding(val port: PortSyntax, val signals: MutableList<Signal?>)

    private class Environment(private val parent: Environment? = null) {
        val bindings: MutableMap<String, Binding> = linkedMapOf()
        val placementTargets: MutableMap<String, Value> = linkedMapOf()
        fun find(name: String): Binding? = bindings[name] ?: parent?.find(name)
        fun findPlacementTarget(name: String): Value? = placementTargets[name] ?: parent?.findPlacementTarget(name)
    }

    private class ElaborationError : RuntimeException()
}
