package org.kvxd.dust.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import org.kvxd.dust.Circuit
import org.kvxd.dust.lang.DustLanguage

class CircuitSourceLoader {
    fun load(
        source: Path,
        requestedName: String? = null,
        parameters: Map<String, Int> = emptyMap(),
    ): Circuit {
        require(Files.isRegularFile(source)) { "design file does not exist: $source" }
        require(source.extension == "dust") { "design file must end in .dust: $source" }
        return DustLanguage.compileTop(
            Files.readString(source),
            source.toString(),
            requestedName = requestedName,
            parameters = parameters,
            color = System.console() != null,
        )
    }
}
