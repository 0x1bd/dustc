package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType

internal data class BinarySyntax(
    val left: ExpressionSyntax,
    val operator: TokenType,
    val right: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
