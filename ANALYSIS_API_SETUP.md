# Kotlin Analysis API Setup Guide

## The Problem

The obvious dependency `analysis-api-standalone-for-ide` has **broken transitive dependencies** in Maven. This is true across all Kotlin versions tested (2.0.0 through 2.3.0).

```kotlin
// THIS DOES NOT WORK - transitive deps fail to resolve
implementation("org.jetbrains.kotlin:analysis-api-standalone-for-ide:2.3.0")
```

Error: `analysis-api-standalone-base`, `analysis-api-fir-standalone-base`, `analysis-api-standalone` are NOT published.

## The Solution

Use **individual Analysis API modules** with `isTransitive = false`:

```kotlin
// build.gradle.kts

plugins {
    kotlin("jvm") version "2.3.0"
}

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

val kotlinVersion = "2.3.0"
val intellijVersion = "243.21565.193"  // IntelliJ 2024.3

dependencies {
    // IntelliJ Platform core (required by Analysis API)
    implementation("com.jetbrains.intellij.platform:core:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:core-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:util:$intellijVersion")

    // Kotlin compiler
    implementation("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")

    // Analysis API modules - MUST use isTransitive = false
    listOf(
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
}
```

## Verified Working

Tested January 2026 with Kotlin 2.3.0:
- All 8 modules resolve successfully
- Code using `KaSession`, `analyze {}`, `KaSymbol` compiles
- Same approach used by [amgdev9/kotlin-lsp](https://github.com/amgdev9/kotlin-lsp)

## Basic Usage Pattern

```kotlin
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.KaSession

// Inside an analysis session
analyze(ktFile) {
    // 'this' is KaSession
    val symbol = ktFile.symbol
    val returnType = symbol.returnType
    // etc.
}
```

## What Analysis API Provides

- **Multi-platform support**: JVM, Native, JS analysis
- **Incremental analysis**: Only re-analyze changed code
- **Proper module handling**: Cross-module type resolution
- **KLIB integration**: Full type info from Kotlin libraries
- **Modern API**: Stable, supported by JetBrains

## References

- [amgdev9/kotlin-lsp](https://github.com/amgdev9/kotlin-lsp) - Working example
- [Kotlin Analysis API docs](https://kotl.in/analysis-api)
- [detekt issue #8626](https://github.com/detekt/detekt/issues/8626) - Similar dependency issues
