package org.kvxd.dust.lang

internal data class AttributeSyntax(
    val name: String,
    val arguments: List<Token>,
    override val location: Token,
) : SyntaxNode