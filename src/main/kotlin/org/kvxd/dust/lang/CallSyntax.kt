package org.kvxd.dust.lang

internal data class CallSyntax(
    val name: String,
    val arguments: List<ExpressionSyntax>,
    override val location: Token,
) : ExpressionSyntax
