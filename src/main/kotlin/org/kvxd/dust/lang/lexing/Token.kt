package org.kvxd.dust.lang.lexing

internal data class Token(
    val type: TokenType,
    val value: String,
    val source: SourceFile,
    val line: Int,
    val column: Int,
    val length: Int,
) {
    override fun toString(): String = value.ifEmpty { type.text }
}
