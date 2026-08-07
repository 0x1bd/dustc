package org.kvxd.dust.lang

internal data class ModuleSyntax(
    val name: String,
    val ports: List<PortSyntax>,
    val body: BlockSyntax,
    override val location: Token,
) : SyntaxNode
