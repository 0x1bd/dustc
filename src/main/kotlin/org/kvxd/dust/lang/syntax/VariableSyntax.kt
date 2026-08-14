package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class VariableSyntax(
    val name: String,
    val mutable: Boolean,
    val recursive: Boolean,
    val initializer: ExpressionSyntax,
    val attributes: List<AttributeSyntax>,
    override val location: Token,
) : StatementSyntax
