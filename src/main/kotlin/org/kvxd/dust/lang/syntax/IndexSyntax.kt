package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class IndexSyntax(
    val target: ExpressionSyntax,
    val index: ExpressionSyntax,
    override val location: Token,
) : ExpressionSyntax
