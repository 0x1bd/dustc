package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class IntegerSyntax(val value: Int, override val location: Token) : ExpressionSyntax
