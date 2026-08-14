package org.kvxd.dust.lang

import java.nio.charset.StandardCharsets
import org.kvxd.dust.lang.lexing.SourceFile

internal object BundledDustModules {
    private const val RESOURCE_ROOT = "org/kvxd/dust/lang/stdlib"

    val sources: List<SourceFile> by lazy {
        read("$RESOURCE_ROOT/index")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { path ->
                require(path.endsWith(".dust") && path.split('/').none { it == ".." }) {
                    "invalid bundled Dust source path '$path'"
                }
                SourceFile("<stdlib>/$path", read("$RESOURCE_ROOT/$path"))
            }
            .toList()
    }

    private fun read(path: String): String {
        val stream = checkNotNull(BundledDustModules::class.java.classLoader.getResourceAsStream(path)) {
            "missing bundled Dust resource '$path'"
        }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
