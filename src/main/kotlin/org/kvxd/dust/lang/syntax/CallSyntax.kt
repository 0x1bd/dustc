package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class CallSyntax(
    val name: String,
    val parameters: List<ExpressionSyntax>,
    val arguments: List<ExpressionSyntax>,
    override val location: Token,
) : ExpressionSyntax
