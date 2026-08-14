package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class IfSyntax(
    val condition: ExpressionSyntax,
    val whenTrue: ExpressionSyntax,
    val whenFalse: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
