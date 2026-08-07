package org.kvxd.dust.lang

internal data class AccessSyntax(
    val target: ExpressionSyntax,
    val member: String,
    override val location: Token,
) : ExpressionSyntax
