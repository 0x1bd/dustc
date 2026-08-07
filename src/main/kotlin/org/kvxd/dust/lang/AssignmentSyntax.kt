package org.kvxd.dust.lang

internal data class AssignmentSyntax(
    val target: ExpressionSyntax,
    val value: ExpressionSyntax,
    override val location: Token,
) : StatementSyntax
