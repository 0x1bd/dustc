package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class ModuleSyntax(
    val name: String,
    val ports: List<PortSyntax>,
    val body: BlockSyntax,
    override val location: Token,
) : SyntaxNode
