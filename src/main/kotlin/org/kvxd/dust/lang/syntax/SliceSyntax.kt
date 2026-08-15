package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class SliceSyntax(
    val target: ExpressionSyntax,
    val first: ExpressionSyntax,
    val end: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
