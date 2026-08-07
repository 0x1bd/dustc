package org.kvxd.dust.lang

internal data class NameSyntax(val name: String, override val location: Token) : ExpressionSyntax
