package org.kvxd.dust.technology.definition

internal object CellIntegerExpression {
    fun evaluate(text: String, variables: Map<String, Int>, location: CellSourceLocation): Int =
        runCatching { Parser(text, variables).parse() }
            .getOrElse { location.error("invalid integer expression '$text': ${it.message}") }

    private class Parser(
        private val source: String,
        private val variables: Map<String, Int>,
    ) {
        private var cursor = 0

        fun parse(): Int {
            val result = additive()
            whitespace()
            require(cursor == source.length) { "unexpected '${source.substring(cursor)}'" }
            return result
        }

        private fun additive(): Int {
            var result = multiplicative()
            while (true) {
                result = when {
                    take('+') -> Math.addExact(result, multiplicative())
                    take('-') -> Math.subtractExact(result, multiplicative())
                    else -> return result
                }
            }
        }

        private fun multiplicative(): Int {
            var result = unary()
            while (true) {
                result = when {
                    take('*') -> Math.multiplyExact(result, unary())
                    take('/') -> {
                        val divisor = unary()
                        require(divisor != 0) { "division by zero" }
                        require(result != Int.MIN_VALUE || divisor != -1) { "integer overflow" }
                        result / divisor
                    }
                    take('%') -> {
                        val divisor = unary()
                        require(divisor != 0) { "division by zero" }
                        result % divisor
                    }
                    else -> return result
                }
            }
        }

        private fun unary(): Int = when {
            take('+') -> unary()
            take('-') -> Math.negateExact(unary())
            else -> primary()
        }

        private fun primary(): Int {
            if (take('(')) {
                val result = additive()
                require(take(')')) { "expected ')'" }
                return result
            }
            whitespace()
            val start = cursor
            if (cursor < source.length && source[cursor].isDigit()) {
                while (cursor < source.length && (source[cursor].isDigit() || source[cursor] == '_')) cursor++
                return source.substring(start, cursor).replace("_", "").toIntOrNull()
                    ?: error("integer literal is out of range")
            }
            if (cursor < source.length && (source[cursor].isLetter() || source[cursor] == '_')) {
                cursor++
                while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) cursor++
                val name = source.substring(start, cursor)
                if (take('(')) {
                    val arguments = mutableListOf<Int>()
                    if (!take(')')) {
                        do arguments += additive() while (take(','))
                        require(take(')')) { "expected ')' after $name arguments" }
                    }
                    return call(name, arguments)
                }
                return requireNotNull(variables[name]) { "unknown integer '$name'" }
            }
            error(if (cursor == source.length) "expected an integer" else "unexpected '${source[cursor]}'")
        }

        private fun call(name: String, arguments: List<Int>): Int = when (name) {
            "abs" -> arguments.singleOrNull()?.let(Math::absExact) ?: error("abs expects one argument")
            "min" -> requireTwo(name, arguments, ::minOf)
            "max" -> requireTwo(name, arguments, ::maxOf)
            "clog2" -> arguments.singleOrNull()?.let { value ->
                require(value > 0) { "clog2 expects a positive argument" }
                Int.SIZE_BITS - Integer.numberOfLeadingZeros(value - 1)
            } ?: error("clog2 expects one argument")
            else -> error("unknown integer function '$name'")
        }

        private fun requireTwo(name: String, values: List<Int>, operation: (Int, Int) -> Int): Int {
            require(values.size == 2) { "$name expects two arguments" }
            return operation(values[0], values[1])
        }

        private fun take(character: Char): Boolean {
            whitespace()
            if (cursor >= source.length || source[cursor] != character) return false
            cursor++
            return true
        }

        private fun whitespace() {
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        }
    }
}
