// by Claude - Gradle plugin for generating kmp-lsp.json
package org.kotlinlsp.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin that generates kmp-lsp.json configuration file.
 *
 * Usage:
 * ```kotlin
 * plugins {
 *     id("org.kotlinlsp.gradle") version "1.0.0"
 * }
 *
 * // Optional configuration
 * kmpLsp {
 *     outputFile = file("kmp-lsp.json")
 *     includeTestSources = false
 * }
 * ```
 *
 * Then run: ./gradlew generateLspConfig
 */
class KmpLspPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Create extension for configuration
        val extension = project.extensions.create(
            "kmpLsp",
            KmpLspExtension::class.java,
            project
        )

        // Register the task
        project.tasks.register("generateLspConfig", GenerateLspConfigTask::class.java) { task ->
            task.group = "ide"
            task.description = "Generates kmp-lsp.json configuration for the Kotlin LSP server"

            task.outputFile.set(extension.outputFile)
            task.includeTestSources.set(extension.includeTestSources)
            task.includeDependencies.set(extension.includeDependencies)
            task.includeTransitiveDependencies.set(extension.includeTransitiveDependencies)
            task.prettyPrint.set(extension.prettyPrint)
            task.additionalSourceRoots.set(extension.additionalSourceRoots)
            task.excludePatterns.set(extension.excludePatterns)

            // No dependencies needed - we just read the project model
        }

        // Also add a task to print the config to stdout (useful for debugging)
        project.tasks.register("printLspConfig", PrintLspConfigTask::class.java) { task ->
            task.group = "ide"
            task.description = "Prints the LSP configuration to stdout"
        }
    }
}
