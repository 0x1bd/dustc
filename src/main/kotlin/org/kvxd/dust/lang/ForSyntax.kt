package org.kvxd.dust.lang

internal data class ForSyntax(
    val index: String,
    val first: ExpressionSyntax,
    val end: ExpressionSyntax,
    val inclusive: Boolean,
    val body: BlockSyntax,
    override val location: Token,
) : StatementSyntax
