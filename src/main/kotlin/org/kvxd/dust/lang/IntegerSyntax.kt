package org.kvxd.dust.lang

internal data class IntegerSyntax(val value: Int, override val location: Token) : ExpressionSyntax
