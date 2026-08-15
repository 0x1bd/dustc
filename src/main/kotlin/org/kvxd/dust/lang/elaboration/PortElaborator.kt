package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.CircuitPort
import org.kvxd.dust.CircuitPortDirection
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal
import org.kvxd.dust.physical.io.PhysicalIoEdge

internal class PortElaborator(
    private val diagnostics: ElaborationDiagnostics,
    private val displays: DisplayElaboration,
    private val placements: PlacementElaborator,
) {
    fun validate(module: SpecializedModule): List<CircuitPort> {
        val names = hashSetOf<String>()
        return module.ports.map { resolved ->
            val port = resolved.syntax
            if (!names.add(port.name)) diagnostics.fail(port.location, "duplicate port '${port.name}'")
            diagnostics.validated(port.location, "port") {
                val placement = placements.attributes(port.attributes)
                if (placement.panel && port.group == null) {
                    diagnostics.fail(port.location, "#[panel] requires a named top-level I/O group")
                }
                if (placement.panel && placement.edge in setOf(InterfaceEdge.EAST, InterfaceEdge.WEST)) {
                    diagnostics.fail(
                        port.location,
                        "#[panel] currently supports north/south edges; omit #[edge] for automatic panel placement",
                    )
                }
                if (resolved.display != null) {
                    displays.validatePlacement(placement.edge, placement.panel, placement.tier, placement.near)
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
                        displays.outputEdge
                    } else {
                        placement.edge?.let { PhysicalIoEdge.valueOf(it.name) }
                    },
                    placement.panel,
                    resolved.display,
                )
            }
        }.also { ports ->
            if (ports.none { it.direction == CircuitPortDirection.INPUT }) {
                diagnostics.fail(module.syntax.location, "module has no inputs")
            }
            if (ports.none { it.direction == CircuitPortDirection.OUTPUT }) {
                diagnostics.fail(module.syntax.location, "module has no outputs")
            }
        }
    }

    fun createInputs(
        ports: List<CircuitPort>,
        builder: BooleanNetlistBuilder,
    ): Map<String, ElaboratedValue> = ports.filter { it.direction == CircuitPortDirection.INPUT }.associate { port ->
        port.name to ElaboratedValue.Signals(
            if (port.width == 1) listOf(builder.input(port.name)) else builder.inputBus(port.name, port.width),
        )
    }

    fun createOutputBindings(module: SpecializedModule): Map<String, OutputBinding> =
        module.ports.filter { it.syntax.direction == PortDirection.OUTPUT }.associate { port ->
            port.syntax.name to OutputBinding(port, MutableList(port.width) { null })
        }

    fun completeOutputs(outputs: Map<String, OutputBinding>): Map<String, ElaboratedValue> =
        outputs.mapValues { (_, output) ->
            val missing = output.signals.indices.filter { output.signals[it] == null }
            if (missing.isNotEmpty()) {
                val names = missing.joinToString { bit ->
                    if (output.signals.size == 1) output.port.syntax.name else "${output.port.syntax.name}[$bit]"
                }
                diagnostics.fail(output.port.syntax.location, "unassigned output $names")
            }
            val signals = output.signals.map(::checkNotNull)
            output.displayWrite ?: ElaboratedValue.Signals(signals)
        }

    fun connect(output: OutputBinding, index: Int?, value: ElaboratedValue, location: Token) {
        val signals = when (value) {
            is ElaboratedValue.Signals -> {
                if (output.port.display != null) {
                    diagnostics.fail(
                        location,
                        "display output '${output.port.syntax.name}' needs display_write(...)",
                    )
                }
                value.signals
            }

            is ElaboratedValue.DisplayWrite -> {
                val display = output.port.display
                    ?: diagnostics.fail(location, "display_write(...) can only be assigned to a display output")
                diagnostics.validated(location, "display") {
                    displays.validateAssignment(display, value.write, index)
                }
                output.displayWrite = value
                value.signals
            }

            else -> diagnostics.fail(location, "an output needs a bit value")
        }
        if (index == null) {
            if (signals.size != output.signals.size) {
                diagnostics.fail(
                    location,
                    "${output.port.syntax.name} needs ${output.signals.size} bits, got ${signals.size}",
                )
            }
            signals.forEachIndexed { bit, signal -> connectBit(output, bit, signal, location) }
        } else {
            if (index !in output.signals.indices) {
                diagnostics.fail(location, "${output.port.syntax.name} has no bit $index")
            }
            if (signals.size != 1) diagnostics.fail(location, "a bus cannot be assigned to one output bit")
            connectBit(output, index, signals.single(), location)
        }
    }

    fun attachOutputs(
        ports: List<CircuitPort>,
        values: Map<String, ElaboratedValue>,
        builder: BooleanNetlistBuilder,
        location: Token,
    ) {
        ports.filter { it.direction == CircuitPortDirection.OUTPUT }.forEach { port ->
            val value = values.getValue(port.name)
            if (port.display != null) {
                val write = value as? ElaboratedValue.DisplayWrite
                    ?: diagnostics.fail(location, "a display output needs display_write(...)")
                displays.instantiate(builder, port.name, port.display, write.write)
            } else {
                val signals = (value as? ElaboratedValue.Signals)?.signals
                    ?: diagnostics.fail(location, "expected a bit or bus")
                if (signals.size == 1) {
                    builder.output(port.name, signals.single())
                } else {
                    builder.outputBus(port.name, signals)
                }
            }
        }
    }

    fun placeTerminals(
        module: SpecializedModule,
        builder: BooleanNetlistBuilder,
        values: Map<String, ElaboratedValue>,
    ) {
        placements.placeTerminals(module.syntax, builder, values)
    }

    private fun connectBit(output: OutputBinding, bit: Int, signal: Signal, location: Token) {
        if (output.signals[bit] != null) {
            val name = if (output.signals.size == 1) {
                output.port.syntax.name
            } else {
                "${output.port.syntax.name}[$bit]"
            }
            diagnostics.fail(location, "$name is already assigned")
        }
        output.signals[bit] = signal
    }

    data class OutputBinding(
        val port: ResolvedPort,
        val signals: MutableList<Signal?>,
        var displayWrite: ElaboratedValue.DisplayWrite? = null,
    )
}
