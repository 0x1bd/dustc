package org.kvxd.dust.lang.lexing

internal class SourceFile(val path: String, val content: String) {
    private val lineStarts: IntArray by lazy {
        val starts = arrayListOf(0)
        for (index in content.indices) if (content[index] == '\n') starts += index + 1
        starts.toIntArray()
    }

    fun lineText(line: Int): String {
        if (line !in 1..lineStarts.size) return ""
        val from = lineStarts[line - 1]
        var to = if (line < lineStarts.size) lineStarts[line] else content.length
        while (to > from && content[to - 1] in "\r\n") to--
        return content.substring(from, to)
    }
}
