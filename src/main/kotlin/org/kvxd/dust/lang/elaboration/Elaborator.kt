package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.Circuit
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.elaboration.body.BodyElaborator
import org.kvxd.dust.lang.elaboration.diagnostic.ElaborationDiagnostics
import org.kvxd.dust.lang.elaboration.display.DisplayElaboration
import org.kvxd.dust.lang.elaboration.module.ModuleSpecializer
import org.kvxd.dust.lang.elaboration.placement.PlacementElaborator
import org.kvxd.dust.lang.elaboration.port.PortElaborator
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder

internal class Elaborator(
    modules: List<ModuleSyntax>,
    reporter: DiagnosticReporter,
    cellLibrary: CellLibrary,
) {
    private val diagnostics = ElaborationDiagnostics(reporter)
    private val displays = DisplayElaboration(cellLibrary)
    private val specializer = ModuleSpecializer(modules, cellLibrary, displays, diagnostics)
    private val placements = PlacementElaborator(diagnostics)
    private val ports = PortElaborator(diagnostics, displays, placements)
    private val bodies = BodyElaborator(diagnostics, specializer, ports, placements, displays, cellLibrary)

    fun build(module: ModuleSyntax, parameters: Map<String, Int> = emptyMap()): Circuit {
        val specialized = specializer.specialize(module, parameters, module.location)
        val builder = BooleanNetlistBuilder(module.name)
        val circuitPorts = ports.validate(specialized)
        val inputs = ports.createInputs(circuitPorts, builder)
        val outputs = bodies.elaborate(
            specialized,
            builder,
            inputs,
            listOf(specialized.key),
            applyTerminalPlacement = true,
        )
        ports.attachOutputs(circuitPorts, outputs, builder, module.location)
        val netlist = diagnostics.validated(module.location, "module") { builder.build() }
        return diagnostics.validated(module.location, "module") {
            Circuit(module.name, circuitPorts, netlist)
        }
    }
}
