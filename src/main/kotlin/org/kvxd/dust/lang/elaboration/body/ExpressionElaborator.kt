package org.kvxd.dust.lang.elaboration.body

import org.kvxd.dust.cell.definition.PortDirection as CellPortDirection
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.lang.elaboration.diagnostic.ElaborationDiagnostics
import org.kvxd.dust.lang.elaboration.display.DisplayElaboration
import org.kvxd.dust.lang.elaboration.expression.IntegerArithmetic
import org.kvxd.dust.lang.elaboration.model.ElaboratedValue
import org.kvxd.dust.lang.elaboration.module.ModuleSpecializer
import org.kvxd.dust.lang.elaboration.module.SpecializationKey
import org.kvxd.dust.lang.elaboration.port.PortElaborator.OutputBinding
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AccessSyntax
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.BooleanSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.IfSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.lang.syntax.SliceSyntax
import org.kvxd.dust.lang.syntax.UnarySyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.Signal

internal class ExpressionElaborator(
    private val diagnostics: ElaborationDiagnostics,
    private val specializer: ModuleSpecializer,
    private val displays: DisplayElaboration,
    private val cellLibrary: CellLibrary,
    private val modules: ModuleInstantiator,
) {
    fun evaluate(
        expression: ExpressionSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue = when (expression) {
        is IntegerSyntax -> ElaboratedValue.Integer(expression.value)
        is BooleanSyntax -> ElaboratedValue.Signals(listOf(builder.constant(expression.value)))
        is NameSyntax -> {
            if (outputs.containsKey(expression.name)) {
                fail(expression.location, "outputs cannot be read while building")
            }
            environment.find(expression.name)?.value
                ?: fail(expression.location, "unknown signal '${expression.name}'")
        }

        is IndexSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val signals = target as? ElaboratedValue.Signals
                ?: fail(expression.target.location, "only a bus can be indexed")
            val index = integer(expression.index, environment, outputs, builder, callStack)
            if (index !in signals.signals.indices) fail(expression.location, "index $index is out of bounds")
            ElaboratedValue.Signals(listOf(signals.signals[index]))
        }

        is SliceSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val signals = target as? ElaboratedValue.Signals
                ?: fail(expression.target.location, "only a bus can be sliced")
            val range = sliceRange(expression, signals.signals.size, environment, outputs, builder, callStack)
            ElaboratedValue.Signals(signals.signals.slice(range))
        }

        is AccessSyntax -> {
            val target = evaluate(expression.target, environment, outputs, builder, callStack)
            val bundle = target as? ElaboratedValue.Bundle
                ?: fail(expression.target.location, "only a module result has outputs")
            bundle.outputs[expression.member]
                ?: fail(expression.location, "module has no output '${expression.member}'")
        }

        is UnarySyntax -> unary(expression, environment, outputs, builder, callStack)
        is BinarySyntax -> binary(expression, environment, outputs, builder, callStack)
        is IfSyntax -> conditional(expression, environment, outputs, builder, callStack)
        is CallSyntax -> call(expression, environment, outputs, builder, callStack)
    }

    fun signals(value: ElaboratedValue, location: Token): List<Signal> =
        (value as? ElaboratedValue.Signals)?.signals ?: fail(location, "expected a bit or bus")

    fun integer(
        expression: ExpressionSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): Int = (evaluate(expression, environment, outputs, builder, callStack) as? ElaboratedValue.Integer)?.value
        ?: fail(expression.location, "expected a compile-time integer")

    fun sliceRange(
        expression: SliceSyntax,
        width: Int,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): IntRange {
        val first = integer(expression.first, environment, outputs, builder, callStack)
        val end = integer(expression.end, environment, outputs, builder, callStack)
        if (first !in 0..width || end !in 0..width) {
            fail(expression.location, "slice $first..$end is out of bounds for a $width-bit bus")
        }
        if (first >= end) fail(expression.location, "slice range must select at least one bit")
        return first until end
    }

    private fun conditional(
        expression: IfSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
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
        return ElaboratedValue.Signals(low.zip(high) { whenFalse, whenTrue ->
            builder.mux(select.single(), whenFalse, whenTrue)
        })
    }

    private fun call(
        call: CallSyntax,
        environment: ElaborationEnvironment,
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

            "decode" -> return decode(call, environment, outputs, builder, callStack)
            "display_write" -> return displayWrite(call, environment, outputs, builder, callStack)
            "latch" -> return latch(call, environment, outputs, builder, callStack)
            "register", "enabled_register", "resettable_register" ->
                return register(call, environment, outputs, builder, callStack)

            "ram" -> return ram(call, environment, outputs, builder, callStack)
            "mux" -> return mux(call, environment, outputs, builder, callStack)
        }

        val parameterValues = call.parameters.map { integer(it, environment, outputs, builder, callStack) }
        cellLibrary.provider(call.name)?.let {
            return libraryCell(call, parameterValues, environment, outputs, builder, callStack)
        }
        return module(call, parameterValues, environment, outputs, builder, callStack)
    }

    private fun decode(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
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

    private fun ram(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
        if (call.parameters.size != 2 || call.arguments.size != 4) {
            fail(
                call.location,
                "ram<ADDRESS_BITS, DATA_BITS>(address, write_data, write_enable, clock) " +
                    "expects two widths and four arguments",
            )
        }
        val addressBits = integer(call.parameters[0], environment, outputs, builder, callStack)
        val dataBits = integer(call.parameters[1], environment, outputs, builder, callStack)
        if (addressBits !in 1..16) {
            fail(call.parameters[0].location, "RAM address width must be between 1 and 16")
        }
        if (dataBits !in 1..MAX_BUS_WIDTH) {
            fail(call.parameters[1].location, "RAM data width must be between 1 and $MAX_BUS_WIDTH")
        }
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        val address = signals(arguments[0], call.arguments[0].location)
        val writeData = signals(arguments[1], call.arguments[1].location)
        val writeEnable = signals(arguments[2], call.arguments[2].location)
        val clock = signals(arguments[3], call.arguments[3].location)
        if (address.size != addressBits) {
            fail(call.arguments[0].location, "ram<$addressBits, $dataBits> needs a $addressBits-bit address")
        }
        if (writeData.size != dataBits) {
            fail(call.arguments[1].location, "ram<$addressBits, $dataBits> needs $dataBits-bit write data")
        }
        if (writeEnable.size != 1 || clock.size != 1) {
            fail(call.location, "RAM write enable and clock inputs must be bits")
        }
        return ElaboratedValue.Signals(builder.ram(address, writeData, writeEnable.single(), clock.single()))
    }

    private fun displayWrite(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.DisplayWrite {
        if (call.parameters.isNotEmpty() || call.arguments.size != 5) {
            fail(call.location, "display_write(x, y, value, plot, plot_all) expects five arguments")
        }
        val arguments = call.arguments.map { argument ->
            signals(evaluate(argument, environment, outputs, builder, callStack), argument.location)
        }
        return ElaboratedValue.DisplayWrite(
            diagnostics.validated(call.location, "display") { displays.write(arguments) },
        )
    }

    private fun latch(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
        if (call.parameters.isNotEmpty()) fail(call.location, "latch does not accept parameters")
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        if (arguments.size != 2 || arguments.any { it !is ElaboratedValue.Signals || it.signals.size != 1 }) {
            fail(call.location, "latch(data, hold) needs two bits")
        }
        return ElaboratedValue.Signals(
            listOf(
                builder.latch(
                    (arguments[0] as ElaboratedValue.Signals).signals.single(),
                    (arguments[1] as ElaboratedValue.Signals).signals.single(),
                ),
            ),
        )
    }

    private fun register(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
        val arguments = call.arguments.map { evaluate(it, environment, outputs, builder, callStack) }
        val expected = if (call.name == "register") 2 else 3
        if (arguments.size != expected) fail(call.location, "${call.name} needs $expected arguments")
        val data = signals(arguments[0], call.location)
        if (call.parameters.size > 1) fail(call.location, "${call.name} accepts at most one width parameter")
        call.parameters.singleOrNull()?.let { parameter ->
            val width = integer(parameter, environment, outputs, builder, callStack)
            if (width != data.size) {
                fail(parameter.location, "${call.name} width $width does not match ${data.size}-bit data")
            }
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

    private fun mux(
        call: CallSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Signals {
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

    private fun libraryCell(
        call: CallSyntax,
        parameters: List<Int>,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue {
        val cell = diagnostics.validated(call.location, "${call.name} specialization") {
            cellLibrary.specialize(call.name, parameters)
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

    private fun module(
        call: CallSyntax,
        parameters: List<Int>,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue.Bundle {
        val specialized = specializer.specialize(call.name, parameters, call.location)
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
                    "${specialized.syntax.name}.${port.syntax.name} needs ${port.width} bits, got ${connected.size}",
                )
            }
            port.syntax.name to ElaboratedValue.Signals(connected)
        }
        return ElaboratedValue.Bundle(
            modules.instantiate(specialized, builder, bound, callStack + specialized.key),
        )
    }

    private fun unary(
        expression: UnarySyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue {
        val operand = evaluate(expression.operand, environment, outputs, builder, callStack)
        return when (expression.operator) {
            TokenType.TILDE, TokenType.BANG ->
                ElaboratedValue.Signals(signals(operand, expression.location).map(builder::not))

            TokenType.PLUS -> ElaboratedValue.Integer(integerValue(operand, expression.location))
            TokenType.MINUS -> ElaboratedValue.Integer(
                diagnostics.checked(expression.location, "integer negation") {
                    Math.negateExact(integerValue(operand, expression.location))
                },
            )

            else -> error("unexpected unary operator ${expression.operator}")
        }
    }

    private fun binary(
        expression: BinarySyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ): ElaboratedValue {
        val leftValue = evaluate(expression.left, environment, outputs, builder, callStack)
        val rightValue = evaluate(expression.right, environment, outputs, builder, callStack)
        if (IntegerArithmetic.supports(expression.operator)) {
            val left = integerValue(leftValue, expression.left.location)
            val right = integerValue(rightValue, expression.right.location)
            return ElaboratedValue.Integer(
                diagnostics.checked(expression.location, "integer expression") {
                    IntegerArithmetic.evaluate(expression.operator, left, right)
                },
            )
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

    private fun integerValue(value: ElaboratedValue, location: Token): Int =
        (value as? ElaboratedValue.Integer)?.value ?: fail(location, "expected a compile-time integer")

    private fun fail(location: Token, message: String): Nothing = diagnostics.fail(location, message)
}
