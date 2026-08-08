import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("kapt") version "2.4.0"
    `java-library`
    application
    id("org.graalvm.buildtools.native") version "1.1.5"
}

group = "org.kvxd.dust"
version = "0.1.0"

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
            buildArgs.add("-O2")
            buildArgs.add("-march=compatibility")
        }
    }
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
