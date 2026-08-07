package org.kvxd.dust.lang

internal data class UnarySyntax(
    val operator: TokenType,
    val operand: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
