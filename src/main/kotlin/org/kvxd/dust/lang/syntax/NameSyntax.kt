package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class NameSyntax(val name: String, override val location: Token) : ExpressionSyntax
