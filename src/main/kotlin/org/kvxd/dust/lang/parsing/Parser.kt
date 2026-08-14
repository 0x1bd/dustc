package org.kvxd.dust.lang.parsing

import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.lexing.Lexer
import org.kvxd.dust.lang.lexing.SourceFile
import org.kvxd.dust.lang.lexing.Token
import org.kvxd.dust.lang.lexing.TokenType
import org.kvxd.dust.lang.syntax.AccessSyntax
import org.kvxd.dust.lang.syntax.AssignmentSyntax
import org.kvxd.dust.lang.syntax.AttributeSyntax
import org.kvxd.dust.lang.syntax.BinarySyntax
import org.kvxd.dust.lang.syntax.BlockSyntax
import org.kvxd.dust.lang.syntax.BooleanSyntax
import org.kvxd.dust.lang.syntax.CallSyntax
import org.kvxd.dust.lang.syntax.ExpressionSyntax
import org.kvxd.dust.lang.syntax.ForSyntax
import org.kvxd.dust.lang.syntax.IfSyntax
import org.kvxd.dust.lang.syntax.IndexSyntax
import org.kvxd.dust.lang.syntax.IntegerSyntax
import org.kvxd.dust.lang.syntax.ModuleSyntax
import org.kvxd.dust.lang.syntax.ModuleParameterSyntax
import org.kvxd.dust.lang.syntax.NameSyntax
import org.kvxd.dust.lang.syntax.PortDirection
import org.kvxd.dust.lang.syntax.PortSyntax
import org.kvxd.dust.lang.syntax.StatementSyntax
import org.kvxd.dust.lang.syntax.UnarySyntax
import org.kvxd.dust.lang.syntax.VariableSyntax

internal class Parser(
    private val reporter: DiagnosticReporter,
    source: SourceFile,
) {
    private val tokens = Lexer(reporter, source).tokenize()
    private var position = 0

    fun parse(): List<ModuleSyntax> = buildList {
        while (!check(TokenType.EOF)) {
            try {
                add(parseModule())
            } catch (_: ParseError) {
                synchronizeModule()
            }
        }
    }

    private fun parseModule(): ModuleSyntax {
        val location = consume(TokenType.MODULE, "expected a module declaration")
        val name = consumeIdentifier("expected a module name")
        val parameters = if (match(TokenType.LESS)) parseModuleParameters() else emptyList()
        consume(TokenType.LPAREN, "expected '(' after module name")
        val ports = arrayListOf<PortSyntax>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break
                ports += parsePortDeclaration(parseAttributes())
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "expected ')' after module ports")
        return ModuleSyntax(name, parameters, ports, parseBlock(), location)
    }

    private fun parseModuleParameters(): List<ModuleParameterSyntax> {
        val parameters = arrayListOf<ModuleParameterSyntax>()
        if (check(TokenType.GREATER)) error("a module parameter list cannot be empty")
        do {
            val location = consume(TokenType.CONST, "expected 'const' in module parameter list")
            val name = consumeIdentifier("expected a module parameter name")
            consume(TokenType.COLON, "expected ':' after module parameter name")
            val type = consume(TokenType.ID, "expected parameter type 'int'")
            if (type.value != "int") reporter.error("unknown parameter type '${type.value}'", type)
            val default = if (match(TokenType.EQ)) parseExpression() else null
            parameters += ModuleParameterSyntax(name, default, location)
        } while (match(TokenType.COMMA) && !check(TokenType.GREATER))
        consume(TokenType.GREATER, "expected '>' after module parameters")
        return parameters
    }

    private fun parsePortDeclaration(attributes: List<AttributeSyntax>): List<PortSyntax> {
        val direction = when {
            match(TokenType.INPUT) -> PortDirection.INPUT
            match(TokenType.OUTPUT) -> PortDirection.OUTPUT
            else -> error("expected 'input' or 'output'")
        }
        val first = consume(TokenType.ID, "expected a port or group name")
        if (!match(TokenType.LBRACE)) {
            return listOf(parsePort(direction, first, group = null, attributes))
        }

        val ports = arrayListOf<PortSyntax>()
        if (check(TokenType.RBRACE)) error("I/O group '${first.value}' cannot be empty")
        do {
            if (check(TokenType.RBRACE)) break
            ports += parsePort(direction, consume(TokenType.ID, "expected a port name"), first.value, attributes)
        } while (match(TokenType.COMMA))
        consume(TokenType.RBRACE, "expected '}' after I/O group '${first.value}'")
        return ports
    }

    private fun parsePort(
        direction: PortDirection,
        nameToken: Token,
        group: String?,
        attributes: List<AttributeSyntax>,
    ): PortSyntax {
        consume(TokenType.COLON, "expected ':' after port name")
        val type = current()
        val typeName = consumeIdentifier("expected 'bit' or 'bits<width>'")
        val width = when (typeName) {
            "bit" -> IntegerSyntax(1, type)
            "bits" -> {
                consume(TokenType.LESS, "expected '<' before bus width")
                val value = parseExpression()
                consume(TokenType.GREATER, "expected '>' after bus width")
                value
            }

            else -> {
                reporter.error("unknown signal type '$typeName'", type)
                IntegerSyntax(1, type)
            }
        }
        return PortSyntax(direction, nameToken.value, width, group, attributes, nameToken)
    }

    private fun parseBlock(): BlockSyntax {
        val location = consume(TokenType.LBRACE, "expected '{'")
        val statements = arrayListOf<StatementSyntax>()
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            try {
                statements += parseStatement()
            } catch (_: ParseError) {
                synchronizeStatement()
            }
        }
        consume(TokenType.RBRACE, "expected '}'")
        return BlockSyntax(statements, location)
    }

    private fun parseStatement(): StatementSyntax {
        val attributes = parseAttributes()
        if (attributes.isNotEmpty()) {
            if (!check(TokenType.LET)) error("placement attributes are only supported on 'let' bindings inside a module")
            return parseVariable(attributes)
        }
        return when (current().type) {
            TokenType.LET -> parseVariable(emptyList())
            TokenType.FOR -> parseFor()
            TokenType.LBRACE -> parseBlock()
            else -> parseExpressionStatement()
        }
    }

    private fun parseVariable(attributes: List<AttributeSyntax>): VariableSyntax {
        val location = advance()
        val mutable = match(TokenType.MUT)
        val name = consumeIdentifier("expected a signal name")
        consume(TokenType.EQ, "a local signal needs an initializer")
        return VariableSyntax(name, mutable, parseExpression(), attributes, location)
    }

    private fun parseFor(): ForSyntax {
        val location = advance()
        val name = consumeIdentifier("expected a loop index")
        consume(TokenType.IN, "expected 'in'")
        val first = parseExpression()
        consume(TokenType.DOTDOT, "expected '..' in loop range")
        val inclusive = match(TokenType.EQ)
        val end = parseExpression()
        return ForSyntax(name, first, end, inclusive, parseBlock(), location)
    }

    private fun parseExpressionStatement(): StatementSyntax {
        val expression = parseExpression()
        if (match(TokenType.EQ)) return AssignmentSyntax(expression, parseExpression(), previous())
        return expression
    }

    private fun parseExpression(): ExpressionSyntax = parseBinary(1)

    private fun parseBinary(minimumPrecedence: Int): ExpressionSyntax {
        var left = parseUnary()
        while (true) {
            val operator = current().type
            val precedence = precedence(operator)
            if (precedence < minimumPrecedence || precedence == 0) break
            val location = advance()
            val right = parseBinary(precedence + 1)
            left = BinarySyntax(left, operator, right, location)
        }
        return left
    }

    private fun precedence(type: TokenType): Int = when (type) {
        TokenType.OR, TokenType.PIPE -> 1
        TokenType.CARET -> 2
        TokenType.AND, TokenType.AMP -> 3
        TokenType.PLUS, TokenType.MINUS -> 4
        TokenType.STAR, TokenType.SLASH, TokenType.PERCENT -> 5
        else -> 0
    }

    private fun parseUnary(): ExpressionSyntax {
        if (
            check(TokenType.TILDE) || check(TokenType.BANG) ||
            check(TokenType.PLUS) || check(TokenType.MINUS)
        ) {
            val location = advance()
            return UnarySyntax(location.type, parseUnary(), location)
        }
        return parsePostfix(parsePrimary())
    }

    private fun parsePostfix(start: ExpressionSyntax): ExpressionSyntax {
        var expression = start
        while (true) {
            expression = when {
                match(TokenType.DOT) -> {
                    val location = previous()
                    AccessSyntax(expression, consumeIdentifier("expected an output name"), location)
                }

                match(TokenType.LBRACKET) -> {
                    val location = previous()
                    val index = parseExpression()
                    consume(TokenType.RBRACKET, "expected ']'")
                    IndexSyntax(expression, index, location)
                }

                else -> return expression
            }
        }
    }

    private fun parsePrimary(): ExpressionSyntax {
        val token = current()
        return when (token.type) {
            TokenType.INT -> {
                advance()
                IntegerSyntax(parseInteger(token), token)
            }

            TokenType.TRUE, TokenType.FALSE -> {
                advance()
                BooleanSyntax(token.type == TokenType.TRUE, token)
            }

            TokenType.ID -> {
                advance()
                val parameters = if (match(TokenType.LESS)) parseSpecializationArguments() else emptyList()
                if (check(TokenType.LPAREN)) CallSyntax(token.value, parameters, parseArguments(), token)
                else if (parameters.isNotEmpty()) error("a specialization must be followed by a call")
                else NameSyntax(token.value, token)
            }

            TokenType.IF -> parseIfExpression()

            TokenType.LPAREN -> {
                advance()
                val expression = parseExpression()
                consume(TokenType.RPAREN, "expected ')'")
                expression
            }

            else -> error("expected a signal or gate expression")
        }
    }

    private fun parseSpecializationArguments(): List<ExpressionSyntax> {
        val parameters = arrayListOf<ExpressionSyntax>()
        if (check(TokenType.GREATER)) error("a specialization argument list cannot be empty")
        do {
            parameters += parseExpression()
        } while (match(TokenType.COMMA))
        consume(TokenType.GREATER, "expected '>' after specialization arguments")
        return parameters
    }

    private fun parseIfExpression(): IfSyntax {
        val location = consume(TokenType.IF, "expected 'if'")
        val condition = parseExpression()
        consume(TokenType.LBRACE, "expected '{' after if condition")
        val whenTrue = parseExpression()
        consume(TokenType.RBRACE, "expected '}' after if value")
        consume(TokenType.ELSE, "a hardware if expression needs an else branch")
        consume(TokenType.LBRACE, "expected '{' after else")
        val whenFalse = parseExpression()
        consume(TokenType.RBRACE, "expected '}' after else value")
        return IfSyntax(condition, whenTrue, whenFalse, location)
    }

    private fun parseArguments(): List<ExpressionSyntax> {
        consume(TokenType.LPAREN, "expected '('")
        val arguments = arrayListOf<ExpressionSyntax>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.RPAREN)) break
                arguments += parseExpression()
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "expected ')' after arguments")
        return arguments
    }

    private fun parseAttributes(): List<AttributeSyntax> = buildList {
        while (match(TokenType.HASH)) {
            val location = previous()
            consume(TokenType.LBRACKET, "expected '[' after '#'")
            val name = consumeIdentifier("expected an attribute name")
            val arguments = arrayListOf<Token>()
            if (match(TokenType.LPAREN)) {
                if (!check(TokenType.RPAREN)) {
                    do {
                        val argument = current()
                        if (argument.type != TokenType.ID && argument.type != TokenType.INT) {
                            error("attribute arguments must be names or integers")
                        }
                        arguments += advance()
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RPAREN, "expected ')' after attribute arguments")
            }
            consume(TokenType.RBRACKET, "expected ']' after attribute")
            add(AttributeSyntax(name, arguments, location))
        }
    }

    private fun parseInteger(token: Token): Int {
        val value = when {
            token.value.startsWith("0x", ignoreCase = true) -> token.value.drop(2).toIntOrNull(16)
            token.value.startsWith("0b", ignoreCase = true) -> token.value.drop(2).toIntOrNull(2)
            else -> token.value.toIntOrNull()
        }
        if (value == null) reporter.error("integer '${token.value}' does not fit in 32 bits", token)
        return value ?: 0
    }

    private fun synchronizeModule() {
        while (!check(TokenType.EOF) && !check(TokenType.MODULE)) advance()
    }

    private fun synchronizeStatement() {
        var depth = 0
        while (!check(TokenType.EOF)) {
            when (current().type) {
                TokenType.LBRACE -> depth++
                TokenType.RBRACE -> {
                    if (depth == 0) return
                    depth--
                }

                TokenType.LET, TokenType.FOR, TokenType.HASH -> if (depth == 0) return
                else -> if (depth == 0 && startsLine()) return
            }
            advance()
        }
    }

    private fun startsLine(): Boolean = position > 0 && current().line > previous().line
    private fun current(): Token = tokens[position]
    private fun previous(): Token = tokens[if (position == 0) 0 else position - 1]
    private fun check(type: TokenType): Boolean = current().type == type

    private fun advance(): Token {
        val token = current()
        if (position < tokens.lastIndex) position++
        return token
    }

    private fun match(type: TokenType): Boolean {
        if (!check(type)) return false
        advance()
        return true
    }

    private fun consume(type: TokenType, message: String): Token =
        if (check(type)) advance() else error(message)

    private fun consumeIdentifier(message: String): String = consume(TokenType.ID, message).value

    private fun error(message: String): Nothing {
        reporter.error(message, current())
        throw ParseError()
    }

    private class ParseError : RuntimeException()
}
