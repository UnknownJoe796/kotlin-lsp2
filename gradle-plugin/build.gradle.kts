// by Claude - Gradle plugin for generating kmp-lsp.json
plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

group = "org.kotlinlsp"
version = "1.0.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(gradleApi())
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.1.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Optional: for better KMP support
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
}

kotlin {
    jvmToolchain(17)
}

gradlePlugin {
    plugins {
        create("kmpLspPlugin") {
            id = "org.kotlinlsp.gradle"
            implementationClass = "org.kotlinlsp.gradle.KmpLspPlugin"
            displayName = "KMP LSP Config Generator"
            description = "Generates kmp-lsp.json configuration for the Kotlin LSP server"
        }
    }
}

// For local testing
publishing {
    repositories {
        maven {
            name = "localPluginRepository"
            url = uri("../build/local-plugin-repository")
        }
    }
}
