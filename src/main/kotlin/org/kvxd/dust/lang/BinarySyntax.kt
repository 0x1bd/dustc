package org.kvxd.dust.lang

internal data class BinarySyntax(
    val left: ExpressionSyntax,
    val operator: TokenType,
    val right: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
