package org.kvxd.dust.lang.elaboration

import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.lexing.Token

internal class ElaborationDiagnostics(private val reporter: DiagnosticReporter) {
    fun fail(location: Token, message: String): Nothing {
        reporter.error(message, location)
        throw Error()
    }

    fun <T> checked(location: Token, description: String, operation: () -> T): T = try {
        operation()
    } catch (exception: ArithmeticException) {
        fail(location, "$description overflows 32-bit signed integers")
    } catch (exception: IllegalArgumentException) {
        fail(location, exception.message ?: "invalid $description")
    }

    fun <T> validated(location: Token, description: String, operation: () -> T): T = try {
        operation()
    } catch (exception: IllegalArgumentException) {
        fail(location, exception.message ?: "invalid $description")
    }

    private class Error : RuntimeException()
}
