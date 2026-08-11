package org.kvxd.dust.lang

import org.kvxd.dust.Circuit
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.elaboration.Elaborator
import org.kvxd.dust.lang.lexing.SourceFile
import org.kvxd.dust.lang.parsing.Parser

object DustLanguage {
    fun compile(source: String, sourceName: String = "<source>", color: Boolean = false): List<Circuit> {
        val file = SourceFile(sourceName, source)
        val reporter = DiagnosticReporter(color)
        val modules = Parser(reporter, file).parse()
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())

        val duplicate = modules.groupBy { it.name }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) {
            reporter.error("duplicate module '${duplicate.first().name}'", duplicate.last().location)
            throw DustCompileException(reporter.render().trimEnd())
        }

        val elaborator = Elaborator(modules, reporter)
        val circuits = arrayListOf<Circuit>()
        for (module in modules) {
            try {
                circuits += elaborator.build(module)
            } catch (exception: RuntimeException) {
                if (!reporter.hasErrors) throw exception
                break
            }
        }
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())
        return circuits
    }
}
