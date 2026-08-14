import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("kapt") version "2.4.0"
    application
    id("org.graalvm.buildtools.native") version "1.1.5"
}

group = "org.kvxd.dust"

val dustcVersion: Provider<String> =
    providers.fileContents(layout.projectDirectory.file("dustc.version")).asText.map(String::trim)

version = dustcVersion.get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation("net.kyori:adventure-nbt:5.2.0")
    implementation("info.picocli:picocli:4.7.7")
    kapt("info.picocli:picocli-codegen:4.7.7")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("org.kvxd.dust.cli.MainKt")
    applicationName = "dustc"
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("dustc")
            mainClass.set("org.kvxd.dust.cli.MainKt")
            sharedLibrary.set(false)
            buildArgs.add("-O2")
            if (System.getProperty("os.arch") in setOf("x86_64", "amd64")) {
                buildArgs.add("-march=compatibility")
            }
        }
    }
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    description = "Generates BuildInfo.kt from dustc.version."
    val version = dustcVersion
    val outputDirectory = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    inputs.property("version", version)
    outputs.dir(outputDirectory)
    doLast {
        val target = outputDirectory.get().file("org/kvxd/dust/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package org.kvxd.dust

            const val DUSTC_VERSION: String = "${version.get()}"
            """.trimIndent() + "\n",
        )
    }
}

val generateDustStandardLibrary = tasks.register("generateDustStandardLibrary") {
    description = "Embeds Dust standard-library sources in generated Kotlin."
    val sourceDirectory = layout.projectDirectory.dir("src/main/dust")
    val sourceFiles = fileTree(sourceDirectory) { include("**/*.dust") }
    val outputDirectory = layout.buildDirectory.dir("generated/dustStandardLibrary/kotlin")
    inputs.files(sourceFiles).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDirectory)

    doLast {
        fun kotlinString(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }

        val root = sourceDirectory.asFile
        val sources = sourceFiles.files.filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        val target = outputDirectory.get().file("org/kvxd/dust/lang/DustStandardLibrarySources.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            buildString {
                appendLine("package org.kvxd.dust.lang")
                appendLine()
                appendLine("import org.kvxd.dust.lang.lexing.SourceFile")
                appendLine()
                appendLine("internal val dustStandardLibrarySources: List<SourceFile> = listOf(")
                sources.forEach { source ->
                    val relativePath = source.relativeTo(root).invariantSeparatorsPath
                    append("    SourceFile(")
                    append(kotlinString("<stdlib>/$relativePath"))
                    append(", ")
                    append(kotlinString(source.readText()))
                    appendLine("),")
                }
                appendLine(")")
            },
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
    kotlin.srcDir(generateDustStandardLibrary)
}

val extensionManifest = layout.projectDirectory.file("editors/vscode/package.json")
val manifestVersion = Regex("^  \"version\": \"([^\"]*)\"", RegexOption.MULTILINE)

tasks.register("syncVersion") {
    description = "Writes dustc.version into the VS Code extension manifest."
    val version = dustcVersion
    val manifest = extensionManifest
    inputs.property("version", version)
    doLast {
        val file = manifest.asFile
        val text = file.readText()
        val updated = manifestVersion.replaceFirst(text, "  \"version\": \"${version.get()}\"")
        if (updated == text) {
            logger.lifecycle("editors/vscode/package.json already at ${version.get()}")
        } else {
            file.writeText(updated)
            logger.lifecycle("editors/vscode/package.json set to ${version.get()}")
        }
    }
}

val verifyVersion = tasks.register("verifyVersion") {
    description = "Fails when the VS Code extension manifest drifts from dustc.version."
    val version = dustcVersion
    val manifest = extensionManifest
    inputs.property("version", version)
    inputs.file(manifest)
    doLast {
        val declared = manifestVersion.find(manifest.asFile.readText())?.groupValues?.get(1)
        if (declared != version.get()) {
            error(
                "editors/vscode/package.json declares version ${declared ?: "<none>"}, " +
                    "but dustc.version says ${version.get()}; run ./gradlew syncVersion",
            )
        }
    }
}

tasks.named("check") {
    dependsOn(verifyVersion)
}

tasks.register<Copy>("installNative") {
    dependsOn(tasks.named("nativeCompile"))
    from(layout.buildDirectory.file("native/nativeCompile/dustc"))
    into(layout.projectDirectory)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
