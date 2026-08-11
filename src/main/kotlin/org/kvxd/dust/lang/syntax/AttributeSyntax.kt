package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class AttributeSyntax(
    val name: String,
    val arguments: List<Token>,
    override val location: Token,
) : SyntaxNode
