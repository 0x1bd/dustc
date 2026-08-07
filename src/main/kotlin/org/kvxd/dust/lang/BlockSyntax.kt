package org.kvxd.dust.lang

internal data class BlockSyntax(
    val statements: List<StatementSyntax>,
    override val location: Token,
) : StatementSyntax
