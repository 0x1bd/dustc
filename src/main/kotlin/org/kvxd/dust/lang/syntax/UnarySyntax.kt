package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType

internal data class UnarySyntax(
    val operator: TokenType,
    val operand: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
