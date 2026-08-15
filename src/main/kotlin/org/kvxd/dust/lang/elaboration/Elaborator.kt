package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.Circuit
import org.kvxd.dust.cell.definition.PortDirection as CellPortDirection
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.elaboration.PortElaborator.OutputBinding
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AccessSyntax
import org.kvxd.dust.lang.syntax.AssignmentSyntax
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.BlockSyntax
import org.kvxd.dust.lang.syntax.BooleanSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.ForSyntax
import org.kvxd.dust.lang.syntax.IfSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.lang.syntax.StatementSyntax
import org.kvxd.dust.lang.syntax.UnarySyntax
import org.kvxd.dust.lang.syntax.VariableSyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.Signal

internal class Elaborator(
    modules: List<ModuleSyntax>,
    reporter: DiagnosticReporter,
    private val cellLibrary: CellLibrary,
) {
    private val diagnostics = ElaborationDiagnostics(reporter)
    private val displays = DisplayElaboration(cellLibrary)
    private val specializer = ModuleSpecializer(modules, cellLibrary, displays, diagnostics)
    private val placements = PlacementElaborator(diagnostics)
    private val ports = PortElaborator(diagnostics, displays, placements)

    fun build(module: ModuleSyntax, parameters: Map<String, Int> = emptyMap()): Circuit {
        val specialized = specializer.specialize(module, parameters, module.location)
        val builder = BooleanNetlistBuilder(module.name)
        val circuitPorts = ports.validate(specialized)
        val inputs = ports.createInputs(circuitPorts, builder)
        val outputs = elaborate(
            specialized,
            builder,
            inputs,
            listOf(specialized.key),
            applyTerminalPlacement = true,
        )
        ports.attachOutputs(circuitPorts, outputs, builder, module.location)
        val netlist = try {
            builder.build()
        } catch (exception: IllegalArgumentException) {
            fail(module.location, exception.message ?: "invalid module")
        }
        return try {
            Circuit(module.name, circuitPorts, netlist)
        } catch (exception: IllegalArgumentException) {
            fail(module.location, exception.message ?: "invalid module")
        }
    }

    private fun elaborate(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, ElaboratedValue>,
        callStack: List<SpecializationKey>,
        applyTerminalPlacement: Boolean,
    ): Map<String, ElaboratedValue> {
        val environment = Environment()
        module.arguments.forEach { (name, value) -> environment.bindings[name] = Binding(ElaboratedValue.Integer(value), false) }
        inputs.forEach { (name, value) -> environment.bindings[name] = Binding(value, mutable = false) }
        module.ports.filter { it.syntax.direction == PortDirection.INPUT && it.syntax.group != null }
            .groupBy { checkNotNull(it.syntax.group) }
            .forEach { (group, ports) ->
                environment.placementTargets[group] = ElaboratedValue.Signals(
                    ports.flatMap { port -> (inputs.getValue(port.syntax.name) as ElaboratedValue.Signals).signals },
                )
            }
        val outputs = ports.createOutputBindings(module)
        execute(module.syntax.body, environment, outputs, builder, callStack)
        val result = ports.completeOutputs(outputs)
        if (applyTerminalPlacement) ports.placeTerminals(module, builder, inputs + result)
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
                        placements.placeBinding(
                            statement.attributes,
                            value,
                            builder,
                            statement.location,
                        ) { target ->
                            environment.find(target)?.value ?: environment.findPlacementTarget(target)
                        }
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
                        iteration.bindings[statement.index] = Binding(ElaboratedValue.Integer(index), mutable = false)
                        execute(statement.body, iteration, outputs, builder, callStack)
                    }
                }

                is BlockSyntax -> execute(statement, environment, outputs, builder, callStack)
                is ExpressionSyntax -> {
                    val value = evaluate(statement, environment, outputs, builder, callStack)
                    if (value !is ElaboratedValue.Bundle || value.outputs.isNotEmpty()) {
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
    ): ElaboratedValue.Signals {
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
        val state = ElaboratedValue.Signals(List(width) { builder.wire() })
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
                    ports.connect(output, null, value, target.location)
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
                    ports.connect(output, index, value, target.location)
                    return
                }
                val binding = environment.find(name.name) ?: fail(name.location, "unknown signal '${name.name}'")
                if (!binding.mutable) fail(name.location, "'${name.name}' is immutable")
                val signals = binding.value as? ElaboratedValue.Signals ?: fail(name.location, "'${name.name}' is not a bus")
                val assigned = value as? ElaboratedValue.Signals ?: fail(assignment.value.location, "expected a bit")
                if (index !in signals.signals.indices) fail(target.location, "index $index is out of bounds")
                if (assigned.signals.size != 1) fail(assignment.value.location, "a bus cannot be assigned to one bit")
                binding.value =
                    ElaboratedValue.Signals(signals.signals.toMutableList().also { it[index] = assigned.signals.single() })
            }

            else -> fail(target.location, "assignment target must be a signal or bus bit")
        }
    }

    private fun evaluate(
        expression: ExpressionSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue = when (expression) {
        is IntegerSyntax -> ElaboratedValue.Integer(expression.value)
        is BooleanSyntax -> ElaboratedValue.Signals(listOf(builder.constant(expression.value)))
        is NameSyntax -> {
            if (outputs.containsKey(expression.name)) fail(expression.location, "outputs cannot be read while building")
            environment.find(expression.name)?.value ?: fail(expression.location, "unknown signal '${expression.name}'")
        }

        is IndexSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val signals = target as? ElaboratedValue.Signals ?: fail(expression.target.location, "only a bus can be indexed")
            val index = integer(expression.index, environment, outputs, builder, callStack)
            if (index !in signals.signals.indices) fail(expression.location, "index $index is out of bounds")
            ElaboratedValue.Signals(listOf(signals.signals[index]))
        }

        is AccessSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val bundle = target as? ElaboratedValue.Bundle ?: fail(expression.target.location, "only a module result has outputs")
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
            ElaboratedValue.Signals(low.zip(high) { whenFalse, whenTrue ->
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
    ): ElaboratedValue {
        when (call.name) {
            "clog2" -> {
                if (call.parameters.isNotEmpty() || call.arguments.size != 1) {
                    fail(call.location, "clog2(value) expects one integer argument")
                }
                val value = integer(call.arguments.single(), environment, outputs, builder, callStack)
                if (value <= 0) fail(call.location, "clog2 expects a positive argument")
                return ElaboratedValue.Integer(Int.SIZE_BITS - Integer.numberOfLeadingZeros(value - 1))
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
                return ElaboratedValue.Signals(
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
                return ElaboratedValue.Signals(
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
                return ElaboratedValue.DisplayWrite(tryDisplay(call.location) { displays.write(arguments) })
            }

            "latch" -> {
                if (call.parameters.isNotEmpty()) fail(call.location, "latch does not accept parameters")
                val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
                if (arguments.size != 2 || arguments.any { it !is ElaboratedValue.Signals || it.signals.size != 1 }) {
                    fail(call.location, "latch(data, hold) needs two bits")
                }
                return ElaboratedValue.Signals(
                    listOf(
                        builder.latch(
                            (arguments[0] as ElaboratedValue.Signals).signals.single(),
                            (arguments[1] as ElaboratedValue.Signals).signals.single()
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
                return ElaboratedValue.Signals(
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
                return ElaboratedValue.Signals(low.zip(high) { a, b -> builder.mux(select.single(), a, b) })
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
                0 -> ElaboratedValue.Bundle(emptyMap())
                1 -> ElaboratedValue.Signals(result.values.single())
                else -> ElaboratedValue.Bundle(result.mapValues { ElaboratedValue.Signals(it.value) })
            }
        }

        val specialized = specializer.specialize(call.name, parameterValues, call.location)
            ?: fail(call.location, "unknown gate, module, or library cell '${call.name}'")
        if (callStack.any { it.module == specialized.syntax.name }) {
            fail(
                call.location,
                "recursive module specialization ${(callStack + specialized.key).joinToString(" -> ")}",
            )
        }
        val inputPorts = specialized.ports.filter { it.syntax.direction == PortDirection.INPUT }
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        if (arguments.size != inputPorts.size) {
            fail(call.location, "${specialized.syntax.name} needs ${inputPorts.size} inputs, got ${arguments.size}")
        }
        val bound = inputPorts.zip(arguments).associate { (port, value) ->
            val connected = signals(value, call.location)
            if (connected.size != port.width) {
                fail(
                    call.location,
                    "${specialized.syntax.name}.${port.syntax.name} needs ${port.width} bits, got ${connected.size}"
                )
            }
            port.syntax.name to ElaboratedValue.Signals(connected)
        }
        return ElaboratedValue.Bundle(
            elaborate(specialized, builder, bound, callStack + specialized.key, applyTerminalPlacement = false),
        )
    }

    private fun unary(
        expression: UnarySyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue {
        val operand = evaluate(expression.operand, environment, outputs, builder, callStack)
        return when (expression.operator) {
            TokenType.TILDE, TokenType.BANG -> ElaboratedValue.Signals(signals(operand, expression.location).map(builder::not))
            TokenType.PLUS -> ElaboratedValue.Integer(integerValue(operand, expression.location))
            TokenType.MINUS -> ElaboratedValue.Integer(checked(expression.location, "integer negation") {
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
    ): ElaboratedValue {
        val leftValue = evaluate(expression.left, environment, outputs, builder, callStack)
        val rightValue = evaluate(expression.right, environment, outputs, builder, callStack)
        if (IntegerArithmetic.supports(expression.operator)) {
            val left = integerValue(leftValue, expression.left.location)
            val right = integerValue(rightValue, expression.right.location)
            return ElaboratedValue.Integer(checked(expression.location, "integer expression") {
                IntegerArithmetic.evaluate(expression.operator, left, right)
            })
        }
        val left = signals(leftValue, expression.left.location)
        val right = signals(rightValue, expression.right.location)
        if (left.size != right.size) {
            fail(expression.location, "gate operands have widths ${left.size} and ${right.size}")
        }
        return ElaboratedValue.Signals(left.zip(right) { a, b ->
            when (expression.operator) {
                TokenType.AMP, TokenType.AND -> builder.and(a, b)
                TokenType.PIPE, TokenType.OR -> builder.or(a, b)
                TokenType.CARET -> builder.xor(a, b)
                else -> error("unexpected gate operator ${expression.operator}")
            }
        })
    }

    private fun signals(value: ElaboratedValue, location: Token): List<Signal> =
        (value as? ElaboratedValue.Signals)?.signals ?: fail(location, "expected a bit or bus")

    private fun integer(
        expression: ExpressionSyntax,
        environment: Environment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Int = (evaluate(expression, environment, outputs, builder, callStack) as? ElaboratedValue.Integer)?.value
        ?: fail(expression.location, "expected a compile-time integer")

    private fun integerValue(value: ElaboratedValue, location: Token): Int =
        (value as? ElaboratedValue.Integer)?.value ?: fail(location, "expected a compile-time integer")

    private fun checked(location: Token, description: String, operation: () -> Int): Int =
        diagnostics.checked(location, description, operation)

    private fun <T> tryDisplay(location: Token, operation: () -> T): T =
        diagnostics.validated(location, "display", operation)

    private fun fail(location: Token, message: String): Nothing = diagnostics.fail(location, message)

    private data class Binding(var value: ElaboratedValue, val mutable: Boolean)

    private class Environment(private val parent: Environment? = null) {
        val bindings: MutableMap<String, Binding> = linkedMapOf()
        val placementTargets: MutableMap<String, ElaboratedValue> = linkedMapOf()
        fun find(name: String): Binding? = bindings[name] ?: parent?.find(name)
        fun findPlacementTarget(name: String): ElaboratedValue? = placementTargets[name] ?: parent?.findPlacementTarget(name)
    }

}
