package org.kvxd.dust.lang.lexing

import org.kvxd.dust.lang.diagnostic.DiagnosticReporter

internal class Lexer(private val reporter: DiagnosticReporter, private val source: SourceFile) {
    private val text = source.content
    private var start = 0
    private var cursor = 0
    private var line = 1
    private var lineStart = 0

    fun tokenize(): List<Token> = buildList {
        while (true) {
            val token = next()
            add(token)
            if (token.type == TokenType.EOF) return@buildList
        }
    }

    private fun next(): Token {
        skipTrivia()
        start = cursor
        if (atEnd()) return token(TokenType.EOF)

        val character = advance()
        if (character.isLetter() || character == '_') return identifier()
        if (character.isDigit()) return number()

        return when (character) {
            '(' -> token(TokenType.LPAREN)
            ')' -> token(TokenType.RPAREN)
            '{' -> token(TokenType.LBRACE)
            '}' -> token(TokenType.RBRACE)
            '[' -> token(TokenType.LBRACKET)
            ']' -> token(TokenType.RBRACKET)
            ',' -> token(TokenType.COMMA)
            ':' -> token(TokenType.COLON)
            '.' -> if (match('.')) token(TokenType.DOTDOT) else token(TokenType.DOT)
            '=' -> token(TokenType.EQ)
            '^' -> token(TokenType.CARET)
            '~' -> token(TokenType.TILDE)
            '!' -> token(TokenType.BANG)
            '+' -> token(TokenType.PLUS)
            '-' -> token(TokenType.MINUS)
            '*' -> token(TokenType.STAR)
            '/' -> token(TokenType.SLASH)
            '%' -> token(TokenType.PERCENT)
            '#' -> token(TokenType.HASH)
            '<' -> token(TokenType.LESS)
            '>' -> token(TokenType.GREATER)
            '&' -> if (match('&')) error("'&&' is not an operator; use '&' for an AND gate") else token(TokenType.AMP)
            '|' -> if (match('|')) error("'||' is not an operator; use '|' for an OR gate") else token(TokenType.PIPE)
            else -> error("unexpected character '$character'")
        }
    }

    private fun identifier(): Token {
        while (peek().isLetterOrDigit() || peek() == '_') advance()
        val value = text.substring(start, cursor)
        return token(KEYWORDS[value] ?: TokenType.ID, value)
    }

    private fun number(): Token {
        val radix = when {
            text[start] == '0' && peek().lowercaseChar() == 'x' -> {
                advance(); 16
            }

            text[start] == '0' && peek().lowercaseChar() == 'b' -> {
                advance(); 2
            }

            else -> 10
        }
        while (peek() == '_' || peek().digitToIntOrNull(radix) != null) advance()
        return token(TokenType.INT, text.substring(start, cursor).replace("_", ""))
    }

    private fun skipTrivia() {
        while (true) {
            when (peek()) {
                ' ', '\r', '\t' -> advance()
                '\n' -> {
                    advance()
                    line++
                    lineStart = cursor
                }

                '/' -> when (peek(1)) {
                    '/' -> while (peek() != '\n' && !atEnd()) advance()
                    '*' -> blockComment()
                    else -> return
                }

                else -> return
            }
        }
    }

    private fun blockComment() {
        advance()
        advance()
        var depth = 1
        while (depth > 0 && !atEnd()) {
            when {
                peek() == '/' && peek(1) == '*' -> {
                    advance(); advance(); depth++
                }

                peek() == '*' && peek(1) == '/' -> {
                    advance(); advance(); depth--
                }

                else -> {
                    if (peek() == '\n') {
                        line++
                        lineStart = cursor + 1
                    }
                    advance()
                }
            }
        }
        if (depth != 0) reporter.error("unterminated block comment", token(TokenType.EOF))
    }

    private fun advance(): Char = text[cursor++]
    private fun peek(offset: Int = 0): Char = text.getOrNull(cursor + offset) ?: '\u0000'
    private fun atEnd(): Boolean = cursor >= text.length

    private fun match(expected: Char): Boolean {
        if (peek() != expected) return false
        cursor++
        return true
    }

    private fun token(type: TokenType, value: String = ""): Token = Token(
        type,
        value,
        source,
        line,
        start - lineStart + 1,
        (cursor - start).coerceAtLeast(1),
    )

    private fun error(message: String): Token {
        reporter.error(message, token(TokenType.EOF))
        return token(TokenType.EOF)
    }

    private companion object {
        val KEYWORDS: Map<String, TokenType> = mapOf(
            "module" to TokenType.MODULE,
            "const" to TokenType.CONST,
            "input" to TokenType.INPUT,
            "output" to TokenType.OUTPUT,
            "let" to TokenType.LET,
            "rec" to TokenType.REC,
            "mut" to TokenType.MUT,
            "for" to TokenType.FOR,
            "in" to TokenType.IN,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "and" to TokenType.AND,
            "or" to TokenType.OR,
            "true" to TokenType.TRUE,
            "false" to TokenType.FALSE,
        )
    }
}
