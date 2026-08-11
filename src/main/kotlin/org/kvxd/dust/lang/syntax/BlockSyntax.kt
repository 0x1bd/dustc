package org.kvxd.dust.lang.syntax

import org.kvxd.dust.lang.lexing.Token

internal data class BlockSyntax(
    val statements: List<StatementSyntax>,
    override val location: Token,
) : StatementSyntax
