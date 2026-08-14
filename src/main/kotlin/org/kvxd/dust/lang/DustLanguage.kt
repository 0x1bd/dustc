package org.kvxd.dust.lang

import org.kvxd.dust.Circuit
import org.kvxd.dust.cell.library.CellLibrary
import org.kvxd.dust.lang.diagnostic.DiagnosticReporter
import org.kvxd.dust.lang.elaboration.Elaborator
import org.kvxd.dust.lang.lexing.SourceFile
import org.kvxd.dust.lang.parsing.Parser
import org.kvxd.dust.technology.MinecraftRedstone

object DustLanguage {
    fun compile(
        source: String,
        sourceName: String = "<source>",
        color: Boolean = false,
        parameters: Map<String, Int> = emptyMap(),
        moduleName: String? = null,
        cellLibrary: CellLibrary = MinecraftRedstone.technology.cellLibrary,
    ): List<Circuit> {
        val file = SourceFile(sourceName, source)
        val reporter = DiagnosticReporter(color)
        val userModules = Parser(reporter, file).parse()
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())

        val duplicate = userModules.groupBy { it.name }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) {
            reporter.error("duplicate module '${duplicate.first().name}'", duplicate.last().location)
            throw DustCompileException(reporter.render().trimEnd())
        }
        if (userModules.isEmpty()) return emptyList()
        val libraryModules = BundledDustModules.sources.flatMap { librarySource ->
            Parser(reporter, librarySource).parse()
        }
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())
        val libraryNames = libraryModules.mapTo(hashSetOf()) { it.name }
        userModules.firstOrNull { it.name in libraryNames }?.let { ambiguous ->
            reporter.error("module '${ambiguous.name}' is ambiguous with a bundled Dust library module", ambiguous.location)
            throw DustCompileException(reporter.render().trimEnd())
        }
        val modules = userModules + libraryModules

        val selected = when {
            moduleName != null -> {
                val selectedModule = userModules.singleOrNull { it.name == moduleName }
                if (selectedModule == null) {
                    reporter.error("no module named '$moduleName'", modules.first().location)
                    throw DustCompileException(reporter.render().trimEnd())
                }
                listOf(selectedModule)
            }

            parameters.isNotEmpty() && userModules.size != 1 -> {
                reporter.error("select one top-level module when supplying parameters", userModules.first().location)
                throw DustCompileException(reporter.render().trimEnd())
            }

            else -> {
                val concrete = userModules.filter { module -> module.parameters.all { it.default != null } }
                if (concrete.isEmpty()) userModules.take(1) else concrete
            }
        }
        val circuits = arrayListOf<Circuit>()
        try {
            val elaborator = Elaborator(modules, reporter, cellLibrary)
            for (module in selected) {
                circuits += elaborator.build(module, if (selected.size == 1) parameters else emptyMap())
            }
        } catch (exception: RuntimeException) {
            if (!reporter.hasErrors) throw exception
        }
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())
        return circuits
    }

    fun compileTop(
        source: String,
        sourceName: String = "<source>",
        requestedName: String? = null,
        preferredName: String? = null,
        parameters: Map<String, Int> = emptyMap(),
        color: Boolean = false,
        cellLibrary: CellLibrary = MinecraftRedstone.technology.cellLibrary,
    ): Circuit {
        val names = moduleNames(source, sourceName, color)
        val selected = when {
            requestedName != null -> requestedName
            names.size == 1 -> names.single()
            preferredName in names -> checkNotNull(preferredName)
            else -> error("$sourceName declares $names; select one with --module")
        }
        return compile(source, sourceName, color, parameters, selected, cellLibrary).single()
    }

    private fun moduleNames(source: String, sourceName: String, color: Boolean): List<String> {
        val reporter = DiagnosticReporter(color)
        val modules = Parser(reporter, SourceFile(sourceName, source)).parse()
        if (reporter.hasErrors) throw DustCompileException(reporter.render().trimEnd())
        val duplicate = modules.groupBy { it.name }.values.firstOrNull { it.size > 1 }
        if (duplicate != null) {
            reporter.error("duplicate module '${duplicate.first().name}'", duplicate.last().location)
            throw DustCompileException(reporter.render().trimEnd())
        }
        require(modules.isNotEmpty()) { "$sourceName does not declare a module" }
        return modules.map { it.name }
    }
}
