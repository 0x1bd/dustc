package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class SignalPortTypeSyntax(
    val width: ExpressionSyntax,
    override val location: Token,
) : PortTypeSyntax
