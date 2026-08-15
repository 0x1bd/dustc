package org.kvxd.dust.lang.elaboration.body

import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.elaboration.diagnostic.ElaborationDiagnostics
import org.kvxd.dust.lang.elaboration.display.DisplayElaboration
import org.kvxd.dust.lang.elaboration.model.ElaboratedValue
import org.kvxd.dust.lang.elaboration.module.ModuleSpecializer
import org.kvxd.dust.lang.elaboration.module.SpecializationKey
import org.kvxd.dust.lang.elaboration.module.SpecializedModule
import org.kvxd.dust.lang.elaboration.placement.PlacementElaborator
import org.kvxd.dust.lang.elaboration.port.PortElaborator
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.netlist.BooleanNetlistBuilder

internal class BodyElaborator(
    diagnostics: ElaborationDiagnostics,
    specializer: ModuleSpecializer,
    private val ports: PortElaborator,
    placements: PlacementElaborator,
    displays: DisplayElaboration,
    cellLibrary: CellLibrary,
) : ModuleInstantiator {
    private val expressions: ExpressionElaborator
    private val statements: StatementElaborator

    init {
        expressions = ExpressionElaborator(diagnostics, specializer, displays, cellLibrary, this)
        statements = StatementElaborator(diagnostics, ports, placements, expressions)
    }

    fun elaborate(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, ElaboratedValue>,
        callStack: List<SpecializationKey>,
        applyTerminalPlacement: Boolean,
    ): Map<String, ElaboratedValue> {
        val environment = ElaborationEnvironment()
        module.arguments.forEach { (name, value) ->
            environment.bindings[name] = ElaborationEnvironment.Binding(
                ElaboratedValue.Integer(value),
                mutable = false,
            )
        }
        inputs.forEach { (name, value) ->
            environment.bindings[name] = ElaborationEnvironment.Binding(value, mutable = false)
        }
        module.ports.filter { it.syntax.direction == PortDirection.INPUT && it.syntax.group != null }
            .groupBy { checkNotNull(it.syntax.group) }
            .forEach { (group, groupedPorts) ->
                environment.placementTargets[group] = ElaboratedValue.Signals(
                    groupedPorts.flatMap { port ->
                        (inputs.getValue(port.syntax.name) as ElaboratedValue.Signals).signals
                    },
                )
            }
        val outputs = ports.createOutputBindings(module)
        statements.execute(module.syntax.body, environment, outputs, builder, callStack)
        val result = ports.completeOutputs(outputs)
        if (applyTerminalPlacement) ports.placeTerminals(module, builder, inputs + result)
        return result
    }

    override fun instantiate(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        inputs: Map<String, ElaboratedValue>,
        callStack: List<SpecializationKey>,
    ): Map<String, ElaboratedValue> = elaborate(
        module,
        builder,
        inputs,
        callStack,
        applyTerminalPlacement = false,
    )
}
