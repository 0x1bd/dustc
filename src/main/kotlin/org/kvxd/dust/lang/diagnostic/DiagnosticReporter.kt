package org.kvxd.dust.lang.diagnostic

import org.kvxd.dust.lang.lexing.Token

internal class DiagnosticReporter(private val color: Boolean) {
    private val entries = arrayListOf<Diagnostic>()
    val hasErrors: Boolean get() = entries.isNotEmpty()

    fun error(message: String, token: Token, note: String? = null) {
        entries += Diagnostic(message, token, note)
    }

    fun render(): String = buildString {
        entries.forEach { diagnostic ->
            append(paint(RED + BOLD, "error"))
            append(paint(BOLD, ": ${diagnostic.message}"))
            appendLine()
            val token = diagnostic.token
            val text = token.source.lineText(token.line)
            val gutter = token.line.toString()
            val pad = " ".repeat(gutter.length)
            append(paint(CYAN, "$pad--> "))
            append(token.source.path).append(':').append(token.line).append(':').append(token.column).appendLine()
            append(paint(CYAN, "$pad |")).appendLine()
            append(paint(CYAN, "$gutter |")).append(' ').append(text.replace("\t", "    ")).appendLine()
            val leading = text.take((token.column - 1).coerceAtLeast(0))
            val caretPad = leading.replace("\t", "    ").map { ' ' }.joinToString("")
            append(paint(CYAN, "$pad |")).append(' ').append(caretPad)
            append(paint(RED, "^".repeat(token.length.coerceAtLeast(1)))).appendLine()
            diagnostic.note?.let { append(paint(CYAN, "note")).append(": ").append(it).appendLine() }
            appendLine()
        }
    }

    private fun paint(style: String, text: String): String = if (color) "$style$text$RESET" else text

    private data class Diagnostic(val message: String, val token: Token, val note: String?)

    private companion object {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val RED = "\u001b[31m"
        const val CYAN = "\u001b[36m"
    }
}
