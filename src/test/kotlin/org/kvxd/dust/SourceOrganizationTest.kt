package org.kvxd.dust

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceOrganizationTest {
    @Test
    fun `Kotlin types have matching files and packages`() {
        listOf(Path.of("src/main/kotlin"), Path.of("src/test/kotlin")).forEach(::verifySourceRoot)
    }

    private fun verifySourceRoot(root: Path) {
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.forEach { source ->
                val lines = Files.readAllLines(source)
                val declarations = lines.mapNotNull { line -> TOP_LEVEL_TYPE.matchEntire(line)?.groupValues?.get(2) }
                assertTrue(
                    declarations.size <= 1,
                    "$source declares multiple top-level types: $declarations",
                )
                declarations.singleOrNull()?.let { type ->
                    assertEquals(type, source.nameWithoutExtension, "$source must be named after $type")
                }

                val packageName = lines.firstNotNullOfOrNull { PACKAGE.matchEntire(it)?.groupValues?.get(1) }
                assertTrue(packageName != null, "$source has no package declaration")
                val expectedDirectory = root.resolve(checkNotNull(packageName).replace('.', '/')).normalize()
                assertEquals(expectedDirectory, source.parent.normalize(), "$source is outside its package directory")
            }
        }
    }

    private companion object {
        val PACKAGE = Regex("package ([A-Za-z_][A-Za-z0-9_.]*)")
        val TOP_LEVEL_TYPE = Regex(
            "(?:(?:public|internal|private|protected|data|sealed|value|enum|annotation|" +
                "abstract|open|final|expect|actual|inline|external) )*" +
                "(class|interface|object|fun interface) ([A-Za-z_][A-Za-z0-9_]*).*",
        )
    }
}
