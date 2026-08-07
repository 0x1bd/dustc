package org.kvxd.dust.lang

internal data class VariableSyntax(
    val name: String,
    val mutable: Boolean,
    val initializer: ExpressionSyntax,
    override val location: Token,
) : StatementSyntax
