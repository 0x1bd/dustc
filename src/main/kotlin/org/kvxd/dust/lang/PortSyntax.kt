package org.kvxd.dust.lang

internal data class PortSyntax(
    val direction: PortDirection,
    val name: String,
    val width: Int,
    val group: String?,
    val attributes: List<AttributeSyntax>,
    override val location: Token,
) : SyntaxNode