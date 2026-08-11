package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class AccessSyntax(
    val target: ExpressionSyntax,
    val member: String,
    override val location: Token,
) : ExpressionSyntax
