package org.kvxd.dust.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import org.kvxd.dust.Circuit
import org.kvxd.dust.lang.DustLanguage

class CircuitSourceLoader {
    fun load(source: Path, requestedName: String? = null): Circuit {
        require(Files.isRegularFile(source)) { "design file does not exist: $source" }
        require(source.extension == "dust") { "design file must end in .dust: $source" }
        val circuits = DustLanguage.compile(Files.readString(source), source.toString(), color = System.console() != null)
        require(circuits.isNotEmpty()) { "$source does not declare a module" }

        requestedName?.let { name ->
            return circuits.singleOrNull { it.name == name }
                ?: error("$source has no module named '$name'; found ${circuits.map { it.name }}")
        }
        if (circuits.size == 1) return circuits.single()

        val fileName = source.nameWithoutExtension
        return circuits.singleOrNull { it.name == fileName }
            ?: error("$source declares ${circuits.map { it.name }}; select one with --module")
    }
}
