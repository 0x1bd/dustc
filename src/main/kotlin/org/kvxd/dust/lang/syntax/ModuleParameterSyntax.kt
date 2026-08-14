package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class ModuleParameterSyntax(
    val name: String,
    val default: ExpressionSyntax?,
    override val location: Token,
) : SyntaxNode
