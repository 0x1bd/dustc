package org.kvxd.dust.technology.definition

internal data class CellSourceLocation(
    val sourceName: String,
    val line: Int,
) {
    fun error(message: String): Nothing = throw IllegalArgumentException("$sourceName:$line: $message")

    override fun toString(): String = "$sourceName:$line"
}
