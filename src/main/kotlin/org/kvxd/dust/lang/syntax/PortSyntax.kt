package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class PortSyntax(
    val direction: PortDirection,
    val name: String,
    val width: ExpressionSyntax,
    val group: String?,
    val attributes: List<AttributeSyntax>,
    override val location: Token,
) : SyntaxNode
