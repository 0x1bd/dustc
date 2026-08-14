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

val packageBundledDustModules = tasks.register<Sync>("packageBundledDustModules") {
    description = "Packages bundled Dust modules as discoverable resources."
    val sourceDirectory = layout.projectDirectory.dir("src/main/dust")
    val sourceFiles = fileTree(sourceDirectory) { include("**/*.dust") }
    val outputDirectory = layout.buildDirectory.dir("generated/bundledDustModules/resources")
    inputs.files(sourceFiles).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDirectory)
    from(sourceDirectory) {
        include("**/*.dust")
        into("org/kvxd/dust/lang/stdlib")
    }
    into(outputDirectory)

    doLast {
        val root = sourceDirectory.asFile
        val sources = sourceFiles.files.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
        val target = outputDirectory.get().file("org/kvxd/dust/lang/stdlib/index").asFile
        target.parentFile.mkdirs()
        target.writeText(sources.joinToString(separator = "\n", postfix = "\n"))
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo)
}

tasks.processResources {
    from(packageBundledDustModules)
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
