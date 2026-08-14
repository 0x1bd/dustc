package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class BooleanSyntax(val value: Boolean, override val location: Token) : ExpressionSyntax
