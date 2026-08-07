package org.kvxd.dust.lang

internal data class IndexSyntax(
    val target: ExpressionSyntax,
    val index: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
