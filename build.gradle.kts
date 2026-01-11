// by Claude - Migrated to Kotlin Analysis API
plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "org.kotlinlsp"
version = "0.1.0"

repositories {
    mavenCentral()
    // Gradle tooling API
    maven("https://repo.gradle.org/gradle/libs-releases")
    // Gradle plugin portal
    gradlePluginPortal()
    // JetBrains repositories
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    // IntelliJ platform releases
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    // Kotlin dev repository for development versions
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/dev")
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
}

val kotlinVersion = "2.3.0"
val lsp4jVersion = "0.23.1"
val intellijVersion = "243.21565.193"  // IntelliJ 2024.3

dependencies {
    // IntelliJ Platform core (required by Analysis API)
    implementation("com.jetbrains.intellij.platform:core:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:core-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:util:$intellijVersion")

    // Kotlin compiler
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")

    // Analysis API modules - MUST use isTransitive = false
    // See ANALYSIS_API_SETUP.md for why this is required
    listOf(
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide"
    ).forEach {
        implementation("$it:$kotlinVersion") { isTransitive = false }
    }

    // Caffeine cache (used by Analysis API internally)
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")

    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    // LSP4J for language server protocol
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:$lsp4jVersion")

    // Gradle Tooling API for project import
    implementation("org.gradle:gradle-tooling-api:8.5") {
        // Exclude SLF4J to avoid conflicts
        exclude(group = "org.slf4j")
    }

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("org.kotlinlsp.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Create fat JAR for distribution
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("kmp-lsp")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isZip64 = true  // Enable for large archives

    manifest {
        attributes["Main-Class"] = "org.kotlinlsp.MainKt"
    }

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
}
