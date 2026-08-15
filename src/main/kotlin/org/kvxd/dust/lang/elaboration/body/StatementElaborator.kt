package org.kvxd.dust.lang.elaboration.body

import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.lang.elaboration.diagnostic.ElaborationDiagnostics
import org.kvxd.dust.lang.elaboration.model.ElaboratedValue
import org.kvxd.dust.lang.elaboration.module.SpecializationKey
import org.kvxd.dust.lang.elaboration.placement.PlacementElaborator
import org.kvxd.dust.lang.elaboration.port.PortElaborator
import org.kvxd.dust.lang.elaboration.port.PortElaborator.OutputBinding
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.syntax.AssignmentSyntax
import org.kvxd.dust.lang.syntax.BlockSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.ForSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.VariableSyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder

internal class StatementElaborator(
    private val diagnostics: ElaborationDiagnostics,
    private val ports: PortElaborator,
    private val placements: PlacementElaborator,
    private val expressions: ExpressionElaborator,
) {
    fun execute(
        block: BlockSyntax,
        parent: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ) {
        val environment = ElaborationEnvironment(parent)
        block.statements.forEach { statement ->
            when (statement) {
                is VariableSyntax -> variable(statement, environment, outputs, builder, callStack)
                is AssignmentSyntax -> assign(statement, environment, outputs, builder, callStack)
                is ForSyntax -> loop(statement, environment, outputs, builder, callStack)
                is BlockSyntax -> execute(statement, environment, outputs, builder, callStack)
                is ExpressionSyntax -> {
                    val value = expressions.evaluate(statement, environment, outputs, builder, callStack)
                    if (value !is ElaboratedValue.Bundle || value.outputs.isNotEmpty()) {
                        fail(statement.location, "unused expression")
                    }
                }
            }
        }
    }

    private fun variable(
        statement: VariableSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ) {
        if (environment.bindings.containsKey(statement.name) || outputs.containsKey(statement.name)) {
            fail(statement.location, "'${statement.name}' is already declared")
        }
        if (statement.mutable && statement.attributes.isNotEmpty()) {
            fail(statement.location, "placement attributes cannot be attached to a mutable binding")
        }
        val value = if (statement.recursive) {
            recursiveBinding(statement, environment, outputs, builder, callStack)
        } else {
            expressions.evaluate(statement.initializer, environment, outputs, builder, callStack)
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
            environment.bindings[statement.name] = ElaborationEnvironment.Binding(value, statement.mutable)
        }
    }

    private fun loop(
        statement: ForSyntax,
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ) {
        val first = expressions.integer(statement.first, environment, outputs, builder, callStack)
        val parsedEnd = expressions.integer(statement.end, environment, outputs, builder, callStack)
        val endExclusive = if (statement.inclusive) {
            diagnostics.checked(statement.location, "loop bound") { Math.addExact(parsedEnd, 1) }
        } else {
            parsedEnd
        }
        if (first > endExclusive) fail(statement.location, "loop range must count upward")
        val iterations = diagnostics.checked(statement.location, "loop range") {
            Math.subtractExact(endExclusive, first)
        }
        repeat(iterations) { offset ->
            val iteration = ElaborationEnvironment(environment)
            val index = diagnostics.checked(statement.location, "loop index") { Math.addExact(first, offset) }
            iteration.bindings[statement.index] = ElaborationEnvironment.Binding(
                ElaboratedValue.Integer(index),
                mutable = false,
            )
            execute(statement.body, iteration, outputs, builder, callStack)
        }
    }

    private fun recursiveBinding(
        statement: VariableSyntax,
        environment: ElaborationEnvironment,
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
                expressions.integer(call.parameters.single(), environment, outputs, builder, callStack).also {
                    if (it !in 1..MAX_BUS_WIDTH) {
                        fail(
                            call.parameters.single().location,
                            "register width must be between 1 and $MAX_BUS_WIDTH",
                        )
                    }
                }
            }

            else -> fail(call.location, "a recursive binding must use dff or a register helper")
        }
        val state = ElaboratedValue.Signals(List(width) { builder.wire() })
        environment.bindings[statement.name] = ElaborationEnvironment.Binding(state, mutable = false)
        val arguments = call.arguments.map {
            expressions.evaluate(it, environment, outputs, builder, callStack)
        }
        val expected = if (call.name in setOf("dff", "register")) 2 else 3
        if (arguments.size != expected) fail(call.location, "${call.name} needs $expected arguments")
        val data = expressions.signals(arguments[0], call.location)
        if (data.size != width) {
            fail(call.location, "${call.name} width $width does not match ${data.size}-bit data")
        }
        val controls = arguments.drop(1).map { value ->
            expressions.signals(value, call.location).also { bits ->
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
        environment: ElaborationEnvironment,
        outputs: Map<String, OutputBinding>,
        builder: BooleanNetlistBuilder,
        callStack: List<SpecializationKey>,
    ) {
        val value = expressions.evaluate(assignment.value, environment, outputs, builder, callStack)
        when (val target = assignment.target) {
            is NameSyntax -> {
                outputs[target.name]?.let { output ->
                    ports.connect(output, null, value, target.location)
                    return
                }
                val binding = environment.find(target.name)
                    ?: fail(target.location, "unknown signal '${target.name}'")
                if (!binding.mutable) fail(target.location, "'${target.name}' is immutable")
                binding.value = value
            }

            is IndexSyntax -> {
                val name = target.target as? NameSyntax
                    ?: fail(target.location, "only a named bus can be assigned by index")
                val index = expressions.integer(target.index, environment, outputs, builder, callStack)
                outputs[name.name]?.let { output ->
                    ports.connect(output, index, value, target.location)
                    return
                }
                val binding = environment.find(name.name)
                    ?: fail(name.location, "unknown signal '${name.name}'")
                if (!binding.mutable) fail(name.location, "'${name.name}' is immutable")
                val signals = binding.value as? ElaboratedValue.Signals
                    ?: fail(name.location, "'${name.name}' is not a bus")
                val assigned = value as? ElaboratedValue.Signals
                    ?: fail(assignment.value.location, "expected a bit")
                if (index !in signals.signals.indices) fail(target.location, "index $index is out of bounds")
                if (assigned.signals.size != 1) {
                    fail(assignment.value.location, "a bus cannot be assigned to one bit")
                }
                binding.value = ElaboratedValue.Signals(
                    signals.signals.toMutableList().also { it[index] = assigned.signals.single() },
                )
            }

            else -> fail(target.location, "assignment target must be a signal or bus bit")
        }
    }

    private fun fail(location: Token, message: String): Nothing = diagnostics.fail(location, message)
}
