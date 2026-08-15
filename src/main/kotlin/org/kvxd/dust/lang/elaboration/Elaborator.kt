package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.Circuit
import org.kvxd.dust.CircuitPort
import org.kvxd.dust.CircuitPortDirection
import org.kvxd.dust.DisplayDimensions
import org.kvxd.dust.cell.definition.PortDirection as CellPortDirection
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.cell.library.DisplayCell
import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AccessSyntax
import org.kvxd.dust.lang.syntax.AssignmentSyntax
import org.kvxd.dust.lang.syntax.AttributeSyntax
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.BlockSyntax
import org.kvxd.dust.lang.syntax.BooleanSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.DisplayPortTypeSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.ForSyntax
import org.kvxd.dust.lang.syntax.IfSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.lang.syntax.PortSyntax
import org.kvxd.dust.lang.syntax.SignalPortTypeSyntax
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
    private val cellLibrary: CellLibrary,
) {
    private val modules = modules.associateBy { it.name }
    private val specializations = linkedMapOf<SpecializationKey, SpecializedModule>()

    init {
        modules.forEach { module ->
            val duplicate = module.parameters.groupBy { it.name }.values.firstOrNull { it.size > 1 }
            if (duplicate != null) fail(
                duplicate.last().location,
                "duplicate module parameter '${duplicate.first().name}'"
            )
            val firstDefault = module.parameters.indexOfFirst { it.default != null }
            if (firstDefault >= 0) {
                module.parameters.drop(firstDefault).firstOrNull { it.default == null }?.let { parameter ->
                    fail(
                        parameter.location,
                        "required parameter '${parameter.name}' follows a parameter with a default"
                    )
                }
            }
            module.parameters.forEach { parameter ->
                if (module.ports.any { it.name == parameter.name }) {
                    fail(parameter.location, "parameter '${parameter.name}' conflicts with a port")
                }
            }
            if (module.name in cellLibrary.providerNames()) {
                fail(module.location, "module '${module.name}' is ambiguous with a bundled library cell")
            }
            if (module.name in STORAGE_INTRINSICS) {
                fail(module.location, "module '${module.name}' is ambiguous with a built-in storage function")
            }
        }
    }

    fun build(module: ModuleSyntax, parameters: Map<String, Int> = emptyMap()): Circuit {
        val specialized = specialize(module, parameters, module.location)
        val builder = BooleanNetlistBuilder(module.name)
        val ports = validatePorts(specialized)
        val inputs = ports.filter { it.direction == CircuitPortDirection.INPUT }.associate { port ->
            port.name to Value.Signals(
                if (port.width == 1) listOf(builder.input(port.name)) else builder.inputBus(port.name, port.width),
            )
        }
        val outputs = elaborate(
            specialized,
            builder,
            inputs,
            listOf(specialized.key),
            applyTerminalPlacement = true,
        )
        ports.filter { it.direction == CircuitPortDirection.OUTPUT }.forEach { port ->
            val value = outputs.getValue(port.name)
            if (port.display != null) {
                cellLibrary.instantiateDisplay(
                    builder,
                    port.name,
                    port.display,
                    displayWrite(value, module.location).cellInputs(),
                )
            } else {
                val signals = signals(value, module.location)
                if (signals.size == 1) builder.output(port.name, signals.single()) else builder.outputBus(
                    port.name,
                    signals,
                )
            }
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

    private fun validatePorts(module: SpecializedModule): List<CircuitPort> {
        val names = hashSetOf<String>()
        return module.ports.map { resolved ->
            val port = resolved.syntax
            if (!names.add(port.name)) fail(port.location, "duplicate port '${port.name}'")
            try {
                val placement = placementAttributes(port.attributes)
                if (placement.panel && port.group == null) {
                    fail(port.location, "#[panel] requires a named top-level I/O group")
                }
                if (placement.panel && placement.edge in setOf(InterfaceEdge.EAST, InterfaceEdge.WEST)) {
                    fail(
                        port.location,
                        "#[panel] currently supports north/south edges; omit #[edge] for automatic panel placement"
                    )
                }
                if (resolved.display != null) {
                    if (placement.edge != null && placement.edge != InterfaceEdge.NORTH) {
                        fail(port.location, "a display output faces the north edge")
                    }
                    if (placement.panel || placement.tier != null || placement.near.isNotEmpty()) {
                        fail(port.location, "display output placement is automatic")
                    }
                }
                CircuitPort(
                    port.name,
                    resolved.width,
                    if (port.direction == PortDirection.INPUT) {
                        CircuitPortDirection.INPUT
                    } else {
                        CircuitPortDirection.OUTPUT
                    },
                    port.group,
                    if (resolved.display != null) {
                        PhysicalIoEdge.NORTH
                    } else {
                        placement.edge?.let { PhysicalIoEdge.valueOf(it.name) }
                    },
                    placement.panel,
                    resolved.display,
                )
            } catch (exception: IllegalArgumentException) {
                fail(port.location, exception.message ?: "invalid port")
            }
        }.also { ports ->
            if (ports.none { it.direction == CircuitPortDirection.INPUT }) fail(
                module.syntax.location,
                "module has no inputs"
            )
            if (ports.none { it.direction == CircuitPortDirection.OUTPUT }) fail(
                module.syntax.location,
                "module has no outputs"
            )
        }
    }

    private fun elaborate(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, Value>,
        callStack: List<SpecializationKey>,
        applyTerminalPlacement: Boolean,
    ): Map<String, Value> {
        val environment = Environment()
        module.arguments.forEach { (name, value) -> environment.bindings[name] = Binding(Value.Integer(value), false) }
        inputs.forEach { (name, value) -> environment.bindings[name] = Binding(value, mutable = false) }
        module.ports.filter { it.syntax.direction == PortDirection.INPUT && it.syntax.group != null }
            .groupBy { checkNotNull(it.syntax.group) }
            .forEach { (group, ports) ->
                environment.placementTargets[group] = Value.Signals(
                    ports.flatMap { port -> (inputs.getValue(port.syntax.name) as Value.Signals).signals },
                )
            }
        val outputPorts = module.ports.filter { it.syntax.direction == PortDirection.OUTPUT }
        val outputs = outputPorts.associate { port ->
            port.syntax.name to OutputBinding(port, MutableList(port.width) { null })
        }
        execute(module.syntax.body, environment, outputs, builder, callStack)
        val result = outputs.mapValues { (_, output) ->
            val missing = output.signals.indices.filter { output.signals[it] == null }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString { bit ->
                    if (output.signals.size == 1) {
                        output.port.syntax.name
                    } else {
                        "${output.port.syntax.name}[$bit]"
                    }
                }
                fail(output.port.syntax.location, "unassigned output $names")
            }
            val signals = output.signals.map(::checkNotNull)
            output.displayWrite ?: Value.Signals(signals)
        }
        if (applyTerminalPlacement) applyPortPlacement(module.syntax, builder, inputs + result)
        return result
    }

    private fun execute(
        block: BlockSyntax,
        parent: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
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
                    val value = if (statement.recursive) {
                        recursiveBinding(statement, environment, outputs, builder, callStack)
                    } else {
                        evaluate(statement.initializer, environment, outputs, builder, callStack)
                    }
                    if (statement.attributes.isNotEmpty()) {
                        val placement = placementAttributes(statement.attributes)
                        if (placement.edge != null) fail(
                            statement.location,
                            "#[edge] is only supported on top-level I/O"
                        )
                        if (placement.panel) fail(
                            statement.location,
                            "#[panel] is only supported on top-level I/O groups"
                        )
                        val targets = placement.near.flatMapTo(linkedSetOf()) { target ->
                            val targetValue = environment.find(target)?.value ?: environment.findPlacementTarget(target)
                            ?: fail(statement.location, "unknown placement target '$target'")
                            placementSignals(targetValue, statement.location)
                        }
                        builder.place(placementSignals(value, statement.location), placement.tier, targets)
                    }
                    if (!statement.recursive) {
                        environment.bindings[statement.name] = Binding(value, statement.mutable)
                    }
                }

                is AssignmentSyntax -> assign(statement, environment, outputs, builder, callStack)
                is ForSyntax -> {
                    val first = integer(statement.first, environment, outputs, builder, callStack)
                    val parsedEnd = integer(statement.end, environment, outputs, builder, callStack)
                    val endExclusive = if (statement.inclusive) checked(statement.location, "loop bound") {
                        Math.addExact(parsedEnd, 1)
                    } else parsedEnd
                    if (first > endExclusive) fail(statement.location, "loop range must count upward")
                    val iterations = checked(statement.location, "loop range") {
                        Math.subtractExact(endExclusive, first)
                    }
                    repeat(iterations) { offset ->
                        val iteration = Environment(environment)
                        val index = checked(statement.location, "loop index") { Math.addExact(first, offset) }
                        iteration.bindings[statement.index] = Binding(Value.Integer(index), mutable = false)
                        execute(statement.body, iteration, outputs, builder, callStack)
                    }
                }

                is BlockSyntax -> execute(statement, environment, outputs, builder, callStack)
                is ExpressionSyntax -> {
                    val value = evaluate(statement, environment, outputs, builder, callStack)
                    if (value !is Value.Bundle || value.outputs.isNotEmpty()) {
                        fail(statement.location, "unused expression")
                    }
                }
            }
        }
    }

    private fun recursiveBinding(
        statement: VariableSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Value.Signals {
        val call = statement.initializer as? CallSyntax
            ?: fail(statement.location, "a recursive binding must be initialized by a register call")
        val width = when (call.name) {
            "dff" -> {
                if (call.parameters.isNotEmpty()) fail(call.location, "dff does not accept parameters")
                1
            }

            "register", "enabled_register", "resettable_register" -> {
                if (call.parameters.size != 1) {
                    fail(call.location, "a recursive ${call.name} binding needs an explicit width parameter")
                }
                integer(call.parameters.single(), environment, outputs, builder, callStack).also {
                    if (it !in 1..MAX_BUS_WIDTH) {
                        fail(call.parameters.single().location, "register width must be between 1 and $MAX_BUS_WIDTH")
                    }
                }
            }

            else -> fail(call.location, "a recursive binding must use dff or a register helper")
        }
        val state = Value.Signals(List(width) { builder.wire() })
        environment.bindings[statement.name] = Binding(state, mutable = false)
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        val expected = when (call.name) {
            "dff", "register" -> 2
            else -> 3
        }
        if (arguments.size != expected) fail(call.location, "${call.name} needs $expected arguments")
        val data = signals(arguments[0], call.location)
        if (data.size != width) fail(call.location, "${call.name} width $width does not match ${data.size}-bit data")
        val controls = arguments.drop(1).map { value ->
            signals(value, call.location).also { bits ->
                if (bits.size != 1) fail(call.location, "${call.name} control inputs must be bits")
            }.single()
        }
        state.signals.zip(data).forEach { (output, input) ->
            when (call.name) {
                "dff", "register" -> builder.dffInto(input, controls[0], output)
                "enabled_register" -> builder.enabledDffInto(input, controls[0], controls[1], output)
                else -> builder.resettableDffInto(input, controls[0], controls[1], output)
            }
        }
        return state
    }

    private fun assign(
        assignment: AssignmentSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
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
                binding.value =
                    Value.Signals(signals.signals.toMutableList().also { it[index] = assigned.signals.single() })
            }

            else -> fail(target.location, "assignment target must be a signal or bus bit")
        }
    }

    private fun connect(output: OutputBinding, index: Int?, value: Value, location: Token) {
        val signals = when (value) {
            is Value.Signals -> {
                if (output.port.display != null) {
                    fail(location, "display output '${output.port.syntax.name}' needs display_write(...)")
                }
                value.signals
            }

            is Value.DisplayWrite -> {
                val display = output.port.display
                    ?: fail(location, "display_write(...) can only be assigned to a display output")
                if (index != null) fail(location, "a display output cannot be assigned by bit")
                if (value.x.size != display.xWidth || value.y.size != display.yWidth) {
                    fail(
                        location,
                        "display<${display.width}, ${display.height}> needs ${display.xWidth}-bit x and " +
                            "${display.yWidth}-bit y coordinates",
                    )
                }
                output.displayWrite = value
                value.signals
            }

            else -> fail(location, "an output needs a bit value")
        }
        if (index == null) {
            if (signals.size != output.signals.size) {
                fail(location, "${output.port.syntax.name} needs ${output.signals.size} bits, got ${signals.size}")
            }
            signals.forEachIndexed { bit, signal -> connectBit(output, bit, signal, location) }
        } else {
            if (index !in output.signals.indices) fail(location, "${output.port.syntax.name} has no bit $index")
            if (signals.size != 1) fail(location, "a bus cannot be assigned to one output bit")
            connectBit(output, index, signals.single(), location)
        }
    }

    private fun connectBit(output: OutputBinding, bit: Int, signal: Signal, location: Token) {
        if (output.signals[bit] != null) {
            val name = if (output.signals.size == 1) {
                output.port.syntax.name
            } else {
                "${output.port.syntax.name}[$bit]"
            }
            fail(location, "$name is already assigned")
        }
        output.signals[bit] = signal
    }

    private fun evaluate(
        expression: ExpressionSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Value = when (expression) {
        is IntegerSyntax -> Value.Integer(expression.value)
        is BooleanSyntax -> Value.Signals(listOf(builder.constant(expression.value)))
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
            bundle.outputs[expression.member] ?: fail(
                expression.location,
                "module has no output '${expression.member}'"
            )
        }

        is UnarySyntax -> unary(expression, environment, outputs, builder, callStack)
        is BinarySyntax -> {
            binary(expression, environment, outputs, builder, callStack)
        }

        is IfSyntax -> {
            val select = signals(
                evaluate(expression.condition, environment, outputs, builder, callStack),
                expression.condition.location,
            )
            if (select.size != 1) fail(expression.condition.location, "an if condition must be one bit")
            val high = signals(
                evaluate(expression.whenTrue, environment, outputs, builder, callStack),
                expression.whenTrue.location,
            )
            val low = signals(
                evaluate(expression.whenFalse, environment, outputs, builder, callStack),
                expression.whenFalse.location,
            )
            if (high.size != low.size) {
                fail(expression.location, "if branches have widths ${high.size} and ${low.size}")
            }
            Value.Signals(low.zip(high) { whenFalse, whenTrue ->
                builder.mux(select.single(), whenFalse, whenTrue)
            })
        }

        is CallSyntax -> call(expression, environment, outputs, builder, callStack)
    }

    private fun call(
        call: CallSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Value {
        when (call.name) {
            "clog2" -> {
                if (call.parameters.isNotEmpty() || call.arguments.size != 1) {
                    fail(call.location, "clog2(value) expects one integer argument")
                }
                val value = integer(call.arguments.single(), environment, outputs, builder, callStack)
                if (value <= 0) fail(call.location, "clog2 expects a positive argument")
                return Value.Integer(Int.SIZE_BITS - Integer.numberOfLeadingZeros(value - 1))
            }

            "const_bits" -> {
                if (call.parameters.size != 1 || call.arguments.size != 1) {
                    fail(call.location, "const_bits<N>(value) expects one width and one value")
                }
                val width = integer(call.parameters.single(), environment, outputs, builder, callStack)
                val value = integer(call.arguments.single(), environment, outputs, builder, callStack)
                if (width !in 1..ULong.SIZE_BITS) {
                    fail(call.parameters.single().location, "constant width must be between 1 and ${ULong.SIZE_BITS}")
                }
                if (value < 0 || (width < Int.SIZE_BITS && value.toLong() >= (1L shl width))) {
                    fail(call.arguments.single().location, "constant value $value does not fit $width bits")
                }
                return Value.Signals(
                    List(width) { bit -> builder.constant(bit < Int.SIZE_BITS && value and (1 shl bit) != 0) },
                )
            }

            "decode" -> {
                if (call.parameters.size != 1 || call.arguments.size != 1) {
                    fail(call.location, "decode<N>(value) expects one output width and one address")
                }
                val width = integer(call.parameters.single(), environment, outputs, builder, callStack)
                if (width !in 2..ULong.SIZE_BITS) {
                    fail(call.parameters.single().location, "decoder width must be between 2 and ${ULong.SIZE_BITS}")
                }
                val address = signals(
                    evaluate(call.arguments.single(), environment, outputs, builder, callStack),
                    call.arguments.single().location,
                )
                val addressWidth = Int.SIZE_BITS - Integer.numberOfLeadingZeros(width - 1)
                if (address.size != addressWidth) {
                    fail(
                        call.arguments.single().location,
                        "decode<$width> needs a $addressWidth-bit address, got ${address.size} bits",
                    )
                }
                val inverted = address.map(builder::not)
                return Value.Signals(
                    List(width) { decoded ->
                        address.indices.map { bit ->
                            if (decoded and (1 shl bit) == 0) inverted[bit] else address[bit]
                        }.reduce(builder::and)
                    },
                )
            }

            "display_write" -> {
                if (call.parameters.isNotEmpty() || call.arguments.size != 5) {
                    fail(call.location, "display_write(x, y, value, plot, plot_all) expects five arguments")
                }
                val arguments = call.arguments.map { argument ->
                    signals(evaluate(argument, environment, outputs, builder, callStack), argument.location)
                }
                if (arguments.drop(2).any { it.size != 1 }) {
                    fail(call.location, "display_write value, plot, and plot_all inputs must be bits")
                }
                return Value.DisplayWrite(
                    x = arguments[0],
                    y = arguments[1],
                    pixelValue = arguments[2].single(),
                    plot = arguments[3].single(),
                    plotAll = arguments[4].single(),
                )
            }

            "latch" -> {
                if (call.parameters.isNotEmpty()) fail(call.location, "latch does not accept parameters")
                val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
                if (arguments.size != 2 || arguments.any { it !is Value.Signals || it.signals.size != 1 }) {
                    fail(call.location, "latch(data, hold) needs two bits")
                }
                return Value.Signals(
                    listOf(
                        builder.latch(
                            (arguments[0] as Value.Signals).signals.single(),
                            (arguments[1] as Value.Signals).signals.single()
                        )
                    ),
                )
            }

            "register", "enabled_register", "resettable_register" -> {
                val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
                val expected = when (call.name) {
                    "register" -> 2
                    else -> 3
                }
                if (arguments.size != expected) fail(call.location, "${call.name} needs $expected arguments")
                val data = signals(arguments[0], call.location)
                if (call.parameters.size > 1) fail(call.location, "${call.name} accepts at most one width parameter")
                call.parameters.singleOrNull()?.let { parameter ->
                    val width = integer(parameter, environment, outputs, builder, callStack)
                    if (width != data.size) fail(parameter.location, "${call.name} width $width does not match ${data.size}-bit data")
                }
                val control = arguments.drop(1).map { value ->
                    signals(value, call.location).also { bits ->
                        if (bits.size != 1) fail(call.location, "${call.name} control inputs must be bits")
                    }.single()
                }
                return Value.Signals(
                    when (call.name) {
                        "register" -> data.map { builder.dff(it, control[0]) }
                        "enabled_register" -> data.map { builder.enabledDff(it, control[0], control[1]) }
                        else -> data.map { builder.resettableDff(it, control[0], control[1]) }
                    },
                )
            }

            "mux" -> {
                if (call.parameters.isNotEmpty()) fail(call.location, "mux does not accept parameters")
                val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
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

        val parameterValues = call.parameters.map { integer(it, environment, outputs, builder, callStack) }
        cellLibrary.provider(call.name)?.let {
            val cell = try {
                cellLibrary.specialize(call.name, parameterValues)
            } catch (exception: IllegalArgumentException) {
                fail(call.location, exception.message ?: "invalid ${call.name} specialization")
            }
            val inputPorts = cell.logicalType.ports.filter { it.direction == CellPortDirection.INPUT }
            val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
            if (arguments.size != inputPorts.size) {
                fail(call.location, "${call.name} needs ${inputPorts.size} inputs, got ${arguments.size}")
            }
            val bound = inputPorts.zip(arguments).associate { (port, value) ->
                val connected = signals(value, call.location)
                if (connected.size != port.width) {
                    fail(call.location, "${call.name}.${port.name} needs ${port.width} bits, got ${connected.size}")
                }
                port.name to connected
            }
            val result = builder.instance(cell.logicalType, bound)
            return when (result.size) {
                0 -> Value.Bundle(emptyMap())
                1 -> Value.Signals(result.values.single())
                else -> Value.Bundle(result.mapValues { Value.Signals(it.value) })
            }
        }

        val module = modules[call.name] ?: fail(call.location, "unknown gate, module, or library cell '${call.name}'")
        val specialized = specialize(module, parameterValues, call.location)
        if (callStack.any { it.module == module.name }) {
            fail(
                call.location,
                "recursive module specialization ${(callStack + specialized.key).joinToString(" -> ")}",
            )
        }
        val inputPorts = specialized.ports.filter { it.syntax.direction == PortDirection.INPUT }
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        if (arguments.size != inputPorts.size) {
            fail(call.location, "${module.name} needs ${inputPorts.size} inputs, got ${arguments.size}")
        }
        val bound = inputPorts.zip(arguments).associate { (port, value) ->
            val connected = signals(value, call.location)
            if (connected.size != port.width) {
                fail(
                    call.location,
                    "${module.name}.${port.syntax.name} needs ${port.width} bits, got ${connected.size}"
                )
            }
            port.syntax.name to Value.Signals(connected)
        }
        return Value.Bundle(
            elaborate(specialized, builder, bound, callStack + specialized.key, applyTerminalPlacement = false),
        )
    }

    private fun displayWrite(value: Value, location: Token): Value.DisplayWrite =
        value as? Value.DisplayWrite ?: fail(location, "a display output needs display_write(...)")

    private fun unary(
        expression: UnarySyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Value {
        val operand = evaluate(expression.operand, environment, outputs, builder, callStack)
        return when (expression.operator) {
            TokenType.TILDE, TokenType.BANG -> Value.Signals(signals(operand, expression.location).map(builder::not))
            TokenType.PLUS -> Value.Integer(integerValue(operand, expression.location))
            TokenType.MINUS -> Value.Integer(checked(expression.location, "integer negation") {
                Math.negateExact(integerValue(operand, expression.location))
            })

            else -> error("unexpected unary operator ${expression.operator}")
        }
    }

    private fun binary(
        expression: BinarySyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Value {
        val leftValue = evaluate(expression.left, environment, outputs, builder, callStack)
        val rightValue = evaluate(expression.right, environment, outputs, builder, callStack)
        if (expression.operator in INTEGER_OPERATORS) {
            val left = integerValue(leftValue, expression.left.location)
            val right = integerValue(rightValue, expression.right.location)
            return Value.Integer(checked(expression.location, "integer expression") {
                when (expression.operator) {
                    TokenType.PLUS -> Math.addExact(left, right)
                    TokenType.MINUS -> Math.subtractExact(left, right)
                    TokenType.STAR -> Math.multiplyExact(left, right)
                    TokenType.SLASH -> {
                        require(right != 0) { "division by zero" }
                        require(left != Int.MIN_VALUE || right != -1) { "integer overflow" }
                        left / right
                    }

                    TokenType.PERCENT -> {
                        require(right != 0) { "division by zero" }
                        left % right
                    }

                    else -> error("unexpected integer operator ${expression.operator}")
                }
            })
        }
        val left = signals(leftValue, expression.left.location)
        val right = signals(rightValue, expression.right.location)
        if (left.size != right.size) {
            fail(expression.location, "gate operands have widths ${left.size} and ${right.size}")
        }
        return Value.Signals(left.zip(right) { a, b ->
            when (expression.operator) {
                TokenType.AMP, TokenType.AND -> builder.and(a, b)
                TokenType.PIPE, TokenType.OR -> builder.or(a, b)
                TokenType.CARET -> builder.xor(a, b)
                else -> error("unexpected gate operator ${expression.operator}")
            }
        })
    }

    private fun specialize(module: ModuleSyntax, values: List<Int>, location: Token): SpecializedModule {
        if (values.size > module.parameters.size) {
            fail(location, "${module.name} accepts ${module.parameters.size} parameters, got ${values.size}")
        }
        return specialize(
            module,
            module.parameters.take(values.size).mapIndexed { index, parameter -> parameter.name to values[index] }
                .toMap(),
            location,
        )
    }

    private fun specialize(module: ModuleSyntax, supplied: Map<String, Int>, location: Token): SpecializedModule {
        supplied.keys.firstOrNull { suppliedName -> module.parameters.none { it.name == suppliedName } }
            ?.let { unknown ->
                fail(location, "${module.name} has no parameter '$unknown'")
            }
        val arguments = linkedMapOf<String, Int>()
        module.parameters.forEach { parameter ->
            arguments[parameter.name] = supplied[parameter.name] ?: parameter.default?.let { default ->
                constantInteger(default, arguments)
            } ?: fail(location, "${module.name} needs parameter '${parameter.name}'")
        }
        val key = SpecializationKey(module.name, module.parameters.map { arguments.getValue(it.name) })
        return specializations.getOrPut(key) {
            val ports = module.ports.map { port ->
                when (val type = port.type) {
                    is SignalPortTypeSyntax -> {
                        val width = constantInteger(type.width, arguments)
                        if (width !in 1..MAX_BUS_WIDTH) {
                            fail(type.width.location, "bus width must be between 1 and $MAX_BUS_WIDTH, got $width")
                        }
                        ResolvedPort(port, width, display = null)
                    }

                    is DisplayPortTypeSyntax -> {
                        if (port.direction != PortDirection.OUTPUT) {
                            fail(port.location, "a display must be an output")
                        }
                        val display = try {
                            cellLibrary.displayDimensions(
                                constantInteger(type.width, arguments),
                                constantInteger(type.height, arguments),
                            )
                        } catch (exception: IllegalArgumentException) {
                            fail(type.location, exception.message ?: "invalid display dimensions")
                        }
                        ResolvedPort(
                            port,
                            display.inputWidth,
                            display,
                        )
                    }
                }
            }
            SpecializedModule(module, arguments.toMap(), ports, key)
        }
    }

    private fun constantInteger(expression: ExpressionSyntax, values: Map<String, Int>): Int = when (expression) {
        is IntegerSyntax -> expression.value
        is NameSyntax -> values[expression.name]
            ?: fail(expression.location, "unknown compile-time integer '${expression.name}'")

        is UnarySyntax -> {
            val operand = constantInteger(expression.operand, values)
            when (expression.operator) {
                TokenType.PLUS -> operand
                TokenType.MINUS -> checked(expression.location, "integer negation") { Math.negateExact(operand) }
                else -> fail(expression.location, "expected a compile-time integer")
            }
        }

        is BinarySyntax -> {
            if (expression.operator !in INTEGER_OPERATORS) fail(expression.location, "expected a compile-time integer")
            val left = constantInteger(expression.left, values)
            val right = constantInteger(expression.right, values)
            checked(expression.location, "integer expression") {
                when (expression.operator) {
                    TokenType.PLUS -> Math.addExact(left, right)
                    TokenType.MINUS -> Math.subtractExact(left, right)
                    TokenType.STAR -> Math.multiplyExact(left, right)
                    TokenType.SLASH -> {
                        require(right != 0) { "division by zero" }
                        require(left != Int.MIN_VALUE || right != -1) { "integer overflow" }
                        left / right
                    }

                    TokenType.PERCENT -> {
                        require(right != 0) { "division by zero" }
                        left % right
                    }

                    else -> error("unexpected integer operator ${expression.operator}")
                }
            }
        }

        is CallSyntax -> {
            if (expression.name != "clog2" || expression.parameters.isNotEmpty() || expression.arguments.size != 1) {
                fail(expression.location, "expected a compile-time integer")
            }
            val argument = constantInteger(expression.arguments.single(), values)
            if (argument <= 0) fail(expression.location, "clog2 expects a positive argument")
            Int.SIZE_BITS - Integer.numberOfLeadingZeros(argument - 1)
        }

        else -> fail(expression.location, "expected a compile-time integer")
    }

    private fun applyPortPlacement(
        module: ModuleSyntax,
        builder: BooleanNetlistBuilder,
        values: Map<String, Value>,
    ) {
        val targets = values.toMutableMap()
        module.ports.filter { it.group != null }.groupBy { checkNotNull(it.group) }.forEach { (group, ports) ->
            targets[group] =
                Value.Signals(ports.flatMap { port -> placementSignals(values.getValue(port.name), port.location) })
        }
        module.ports.forEach { port ->
            if (port.attributes.isEmpty()) return@forEach
            val placement = placementAttributes(port.attributes)
            val near = placement.near.flatMapTo(linkedSetOf()) { target ->
                val targetValue = targets[target] ?: fail(port.location, "unknown placement target '$target'")
                placementSignals(targetValue, port.location)
            }
            builder.placeTerminals(
                placementSignals(values.getValue(port.name), port.location),
                placement.tier,
                near,
                placement.edge
            )
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
                        else -> fail(
                            attribute.arguments.single(),
                            "invalid edge '$value'; expected north, south, east, or west"
                        )
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
        is Value.DisplayWrite -> value.signals
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
        callStack: List<SpecializationKey>,
    ): Int = (evaluate(expression, environment, outputs, builder, callStack) as? Value.Integer)?.value
        ?: fail(expression.location, "expected a compile-time integer")

    private fun integerValue(value: Value, location: Token): Int =
        (value as? Value.Integer)?.value ?: fail(location, "expected a compile-time integer")

    private fun checked(location: Token, description: String, operation: () -> Int): Int = try {
        operation()
    } catch (exception: ArithmeticException) {
        fail(location, "$description overflows 32-bit signed integers")
    } catch (exception: IllegalArgumentException) {
        fail(location, exception.message ?: "invalid $description")
    }

    private fun fail(location: Token, message: String): Nothing {
        reporter.error(message, location)
        throw ElaborationError()
    }

    private sealed interface Value {
        data class Signals(val signals: List<Signal>) : Value
        data class DisplayWrite(
            val x: List<Signal>,
            val y: List<Signal>,
            val pixelValue: Signal,
            val plot: Signal,
            val plotAll: Signal,
        ) : Value {
            val signals: List<Signal> = x + y + pixelValue + plot + plotAll

            fun cellInputs(): DisplayCell.Inputs = DisplayCell.Inputs(x, y, pixelValue, plot, plotAll)
        }
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
    private data class OutputBinding(
        val port: ResolvedPort,
        val signals: MutableList<Signal?>,
        var displayWrite: Value.DisplayWrite? = null,
    )

    private data class ResolvedPort(
        val syntax: PortSyntax,
        val width: Int,
        val display: DisplayDimensions?,
    )

    private data class SpecializedModule(
        val syntax: ModuleSyntax,
        val arguments: Map<String, Int>,
        val ports: List<ResolvedPort>,
        val key: SpecializationKey,
    )

    private data class SpecializationKey(val module: String, val parameters: List<Int>) {
        override fun toString(): String =
            if (parameters.isEmpty()) module else "$module<${parameters.joinToString(", ")}>"
    }

    private class Environment(private val parent: Environment? = null) {
        val bindings: MutableMap<String, Binding> = linkedMapOf()
        val placementTargets: MutableMap<String, Value> = linkedMapOf()
        fun find(name: String): Binding? = bindings[name] ?: parent?.find(name)
        fun findPlacementTarget(name: String): Value? = placementTargets[name] ?: parent?.findPlacementTarget(name)
    }

    private class ElaborationError : RuntimeException()

    private companion object {
        val STORAGE_INTRINSICS = setOf("register", "enabled_register", "resettable_register")
        val INTEGER_OPERATORS = setOf(
            TokenType.PLUS,
            TokenType.MINUS,
            TokenType.STAR,
            TokenType.SLASH,
            TokenType.PERCENT,
        )
    }
}
