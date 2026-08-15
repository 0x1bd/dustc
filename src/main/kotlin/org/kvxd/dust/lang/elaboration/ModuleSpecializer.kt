package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.MAX_BUS_WIDTH
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.DisplayPortTypeSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.SignalPortTypeSyntax
import org.kvxd.dust.lang.syntax.UnarySyntax

internal class ModuleSpecializer(
    modules: List<ModuleSyntax>,
    private val cellLibrary: CellLibrary,
    private val displays: DisplayElaboration,
    private val diagnostics: ElaborationDiagnostics,
) {
    private val modules = modules.associateBy { it.name }
    private val specializations = linkedMapOf<SpecializationKey, SpecializedModule>()

    init {
        modules.forEach(::validateDeclaration)
    }

    fun specialize(module: ModuleSyntax, parameters: Map<String, Int>, location: Token): SpecializedModule =
        createSpecialization(module, parameters, location)

    fun specialize(name: String, parameters: List<Int>, location: Token): SpecializedModule? {
        val module = modules[name] ?: return null
        if (parameters.size > module.parameters.size) {
            diagnostics.fail(location, "${module.name} accepts ${module.parameters.size} parameters, got ${parameters.size}")
        }
        return createSpecialization(
            module,
            module.parameters.take(parameters.size).mapIndexed { index, parameter ->
                parameter.name to parameters[index]
            }.toMap(),
            location,
        )
    }

    private fun validateDeclaration(module: ModuleSyntax) {
        val duplicate = module.parameters.groupBy { it.name }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) {
            diagnostics.fail(
                duplicate.last().location,
                "duplicate module parameter '${duplicate.first().name}'",
            )
        }
        val firstDefault = module.parameters.indexOfFirst { it.default != null }
        if (firstDefault >= 0) {
            module.parameters.drop(firstDefault).firstOrNull { it.default == null }?.let { parameter ->
                diagnostics.fail(
                    parameter.location,
                    "required parameter '${parameter.name}' follows a parameter with a default",
                )
            }
        }
        module.parameters.forEach { parameter ->
            if (module.ports.any { it.name == parameter.name }) {
                diagnostics.fail(parameter.location, "parameter '${parameter.name}' conflicts with a port")
            }
        }
        if (module.name in cellLibrary.providerNames()) {
            diagnostics.fail(module.location, "module '${module.name}' is ambiguous with a bundled library cell")
        }
        if (module.name in STORAGE_INTRINSICS) {
            diagnostics.fail(module.location, "module '${module.name}' is ambiguous with a built-in storage function")
        }
    }

    private fun createSpecialization(
        module: ModuleSyntax,
        supplied: Map<String, Int>,
        location: Token,
    ): SpecializedModule {
        supplied.keys.firstOrNull { suppliedName -> module.parameters.none { it.name == suppliedName } }
            ?.let { unknown -> diagnostics.fail(location, "${module.name} has no parameter '$unknown'") }
        val arguments = linkedMapOf<String, Int>()
        module.parameters.forEach { parameter ->
            arguments[parameter.name] = supplied[parameter.name] ?: parameter.default?.let { default ->
                constantInteger(default, arguments)
            } ?: diagnostics.fail(location, "${module.name} needs parameter '${parameter.name}'")
        }
        val key = SpecializationKey(module.name, module.parameters.map { arguments.getValue(it.name) })
        return specializations.getOrPut(key) {
            val ports = module.ports.map { port ->
                when (val type = port.type) {
                    is SignalPortTypeSyntax -> {
                        val width = constantInteger(type.width, arguments)
                        if (width !in 1..MAX_BUS_WIDTH) {
                            diagnostics.fail(
                                type.width.location,
                                "bus width must be between 1 and $MAX_BUS_WIDTH, got $width",
                            )
                        }
                        ResolvedPort(port, width, display = null)
                    }

                    is DisplayPortTypeSyntax -> {
                        val display = diagnostics.validated(type.location, "display") {
                            displays.resolvePort(
                                port.direction,
                                constantInteger(type.width, arguments),
                                constantInteger(type.height, arguments),
                            )
                        }
                        ResolvedPort(port, display.inputWidth, display)
                    }
                }
            }
            SpecializedModule(module, arguments.toMap(), ports, key)
        }
    }

    private fun constantInteger(expression: ExpressionSyntax, values: Map<String, Int>): Int = when (expression) {
        is IntegerSyntax -> expression.value
        is NameSyntax -> values[expression.name]
            ?: diagnostics.fail(expression.location, "unknown compile-time integer '${expression.name}'")

        is UnarySyntax -> {
            val operand = constantInteger(expression.operand, values)
            when (expression.operator) {
                TokenType.PLUS -> operand
                TokenType.MINUS -> diagnostics.checked(expression.location, "integer negation") {
                    Math.negateExact(operand)
                }

                else -> diagnostics.fail(expression.location, "expected a compile-time integer")
            }
        }

        is BinarySyntax -> {
            if (!IntegerArithmetic.supports(expression.operator)) {
                diagnostics.fail(expression.location, "expected a compile-time integer")
            }
            val left = constantInteger(expression.left, values)
            val right = constantInteger(expression.right, values)
            diagnostics.checked(expression.location, "integer expression") {
                IntegerArithmetic.evaluate(expression.operator, left, right)
            }
        }

        is CallSyntax -> {
            if (expression.name != "clog2" || expression.parameters.isNotEmpty() || expression.arguments.size != 1) {
                diagnostics.fail(expression.location, "expected a compile-time integer")
            }
            val argument = constantInteger(expression.arguments.single(), values)
            if (argument <= 0) diagnostics.fail(expression.location, "clog2 expects a positive argument")
            Int.SIZE_BITS - Integer.numberOfLeadingZeros(argument - 1)
        }

        else -> diagnostics.fail(expression.location, "expected a compile-time integer")
    }

    private companion object {
        val STORAGE_INTRINSICS = setOf("register", "enabled_register", "resettable_register")
    }
}
