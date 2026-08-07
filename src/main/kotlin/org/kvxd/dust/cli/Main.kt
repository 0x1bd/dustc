package org.kvxd.dust.cli

import kotlin.system.exitProcess
import org.kvxd.dust.lang.DustCompileException
import picocli.CommandLine

fun main(arguments: Array<String>) {
    exitProcess(dustcCommandLine().execute(*arguments))
}

internal fun dustcCommandLine(): CommandLine = CommandLine(DustcCommand()).setExecutionExceptionHandler {
    exception, commandLine, _ ->
    val message = exception.message ?: "compilation failed"
    commandLine.err.println(if (exception is DustCompileException) message else "dustc: error: $message")
    CommandLine.ExitCode.SOFTWARE
}
