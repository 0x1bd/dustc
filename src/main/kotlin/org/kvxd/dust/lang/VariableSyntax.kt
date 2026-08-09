package org.kvxd.dust.lang

internal data class VariableSyntax(
    val name: String,
    val mutable: Boolean,
    val initializer: ExpressionSyntax,
    val attributes: List<AttributeSyntax>,
    override val location: Token,
) : StatementSyntax