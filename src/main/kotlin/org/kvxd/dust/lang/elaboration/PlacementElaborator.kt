package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AttributeSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.netlist.BooleanNetlistBuilder
import org.kvxd.dust.netlist.InterfaceEdge
import org.kvxd.dust.netlist.Signal

internal class PlacementElaborator(private val diagnostics: ElaborationDiagnostics) {
    fun attributes(attributes: List<AttributeSyntax>): PlacementAttributes {
        var tier: Int? = null
        var edge: InterfaceEdge? = null
        var panel = false
        val near = linkedSetOf<String>()
        attributes.forEach { attribute ->
            when (attribute.name) {
                "tier" -> {
                    if (tier != null) diagnostics.fail(attribute.location, "duplicate #[tier] attribute")
                    if (attribute.arguments.size != 1 || attribute.arguments.single().type != TokenType.INT) {
                        diagnostics.fail(attribute.location, "#[tier] expects one non-negative integer")
                    }
                    val token = attribute.arguments.single()
                    val value = parseInteger(token)
                    if (value < 0) diagnostics.fail(token, "tier must be non-negative")
                    tier = value
                }

                "near" -> {
                    if (attribute.arguments.isEmpty() || attribute.arguments.any { it.type != TokenType.ID }) {
                        diagnostics.fail(attribute.location, "#[near] expects one or more placement target names")
                    }
                    attribute.arguments.forEach { near += it.value }
                }

                "edge" -> {
                    if (edge != null) diagnostics.fail(attribute.location, "duplicate #[edge] attribute")
                    if (attribute.arguments.size != 1 || attribute.arguments.single().type != TokenType.ID) {
                        diagnostics.fail(attribute.location, "#[edge] expects one of north, south, east, west")
                    }
                    edge = when (val value = attribute.arguments.single().value) {
                        "north" -> InterfaceEdge.NORTH
                        "south" -> InterfaceEdge.SOUTH
                        "east" -> InterfaceEdge.EAST
                        "west" -> InterfaceEdge.WEST
                        else -> diagnostics.fail(
                            attribute.arguments.single(),
                            "invalid edge '$value'; expected north, south, east, or west",
                        )
                    }
                }

                "panel" -> {
                    if (panel) diagnostics.fail(attribute.location, "duplicate #[panel] attribute")
                    if (attribute.arguments.isNotEmpty()) {
                        diagnostics.fail(attribute.location, "#[panel] does not take arguments")
                    }
                    panel = true
                }

                else -> diagnostics.fail(attribute.location, "unknown placement attribute '#[${attribute.name}]'")
            }
        }
        return PlacementAttributes(tier, near.toList(), edge, panel)
    }

    fun placeBinding(
        attributes: List<AttributeSyntax>,
        value: ElaboratedValue,
        builder: BooleanNetlistBuilder,
        location: Token,
        target: (String) -> ElaboratedValue?,
    ) {
        val placement = attributes(attributes)
        if (placement.edge != null) {
            diagnostics.fail(location, "#[edge] is only supported on top-level I/O")
        }
        if (placement.panel) {
            diagnostics.fail(location, "#[panel] is only supported on top-level I/O groups")
        }
        val near = placement.near.flatMapTo(linkedSetOf()) { name ->
            val targetValue = target(name) ?: diagnostics.fail(location, "unknown placement target '$name'")
            signals(targetValue, location)
        }
        builder.place(signals(value, location), placement.tier, near)
    }

    fun placeTerminals(
        module: ModuleSyntax,
        builder: BooleanNetlistBuilder,
        values: Map<String, ElaboratedValue>,
    ) {
        val targets = values.toMutableMap()
        module.ports.filter { it.group != null }.groupBy { checkNotNull(it.group) }.forEach { (group, ports) ->
            targets[group] = ElaboratedValue.Signals(
                ports.flatMap { port -> signals(values.getValue(port.name), port.location) },
            )
        }
        module.ports.forEach { port ->
            if (port.attributes.isEmpty()) return@forEach
            val placement = attributes(port.attributes)
            val near = placement.near.flatMapTo(linkedSetOf()) { name ->
                val targetValue = targets[name] ?: diagnostics.fail(port.location, "unknown placement target '$name'")
                signals(targetValue, port.location)
            }
            builder.placeTerminals(
                signals(values.getValue(port.name), port.location),
                placement.tier,
                near,
                placement.edge,
            )
        }
    }

    private fun parseInteger(token: Token): Int {
        val value = when {
            token.value.startsWith("0x", ignoreCase = true) -> token.value.drop(2).toIntOrNull(16)
            token.value.startsWith("0b", ignoreCase = true) -> token.value.drop(2).toIntOrNull(2)
            else -> token.value.toIntOrNull()
        }
        return value ?: diagnostics.fail(token, "integer '${token.value}' does not fit in 32 bits")
    }

    private fun signals(value: ElaboratedValue, location: Token): List<Signal> = when (value) {
        is ElaboratedValue.Signals -> value.signals
        is ElaboratedValue.DisplayWrite -> value.signals
        is ElaboratedValue.Bundle -> value.outputs.values.flatMap { signals(it, location) }
        is ElaboratedValue.Integer -> diagnostics.fail(
            location,
            "placement attributes require a signal, bus, or module output bundle",
        )
    }
}
