package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class DisplayPortTypeSyntax(
    val width: ExpressionSyntax,
    val height: ExpressionSyntax,
    override val location: Token,
) : PortTypeSyntax
