package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class AssignmentSyntax(
    val target: ExpressionSyntax,
    val value: ExpressionSyntax,
    override val location: Token,
) : StatementSyntax
