package org.kvxd.dust.lang.elaboration.expression

import org.kvxd.dust.lang.lexing.TokenType

internal object IntegerArithmetic {
    fun supports(operator: TokenType): Boolean = operator in OPERATORS

    fun evaluate(operator: TokenType, left: Int, right: Int): Int = when (operator) {
        TokenType.PLUS -> Math.addExact(left, right)
        TokenType.MINUS -> Math.subtractExact(left, right)
        TokenType.STAR -> Math.multiplyExact(left, right)
        TokenType.SLASH -> {
            require(right != 0) { "division by zero" }
            require(left != Int.MIN_VALUE || right != -1) { "integer overflow" }
            left / right
        }

        TokenType.PERCENT -> {
            require(right != 0) { "division by zero" }
            left % right
        }

        else -> error("unexpected integer operator $operator")
    }

    private val OPERATORS = setOf(
        TokenType.PLUS,
        TokenType.MINUS,
        TokenType.STAR,
        TokenType.SLASH,
        TokenType.PERCENT,
    )
}
