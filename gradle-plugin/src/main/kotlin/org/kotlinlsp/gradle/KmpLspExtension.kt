// by Claude - Extension for configuring the KMP LSP plugin
package org.kotlinlsp.gradle

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

/**
 * Extension for configuring the KMP LSP config generator.
 */
abstract class KmpLspExtension @Inject constructor(project: Project) {

    /**
     * Output file path. Defaults to kmp-lsp.json in project root.
     */
    abstract val outputFile: RegularFileProperty

    /**
     * Whether to include test source sets. Defaults to false.
     */
    abstract val includeTestSources: Property<Boolean>

    /**
     * Whether to include resolved dependencies. Defaults to true.
     */
    abstract val includeDependencies: Property<Boolean>

    /**
     * Whether to include transitive dependencies. Defaults to true.
     */
    abstract val includeTransitiveDependencies: Property<Boolean>

    /**
     * Whether to pretty-print the JSON. Defaults to true.
     */
    abstract val prettyPrint: Property<Boolean>

    /**
     * Additional source roots to include (beyond what Gradle detects).
     */
    abstract val additionalSourceRoots: ListProperty<String>

    /**
     * File patterns to exclude from analysis.
     */
    abstract val excludePatterns: ListProperty<String>

    /**
     * Custom LSP settings to include in the config.
     */
    abstract val settings: Property<LspSettingsConfig>

    init {
        outputFile.convention(project.layout.projectDirectory.file("kmp-lsp.json"))
        includeTestSources.convention(false)
        includeDependencies.convention(true)
        includeTransitiveDependencies.convention(true)
        prettyPrint.convention(true)
        additionalSourceRoots.convention(emptyList())
        excludePatterns.convention(listOf(
            "**/build/**",
            "**/.gradle/**",
            "**/generated/**"
        ))
        settings.convention(LspSettingsConfig())
    }
}

/**
 * LSP settings configuration.
 */
data class LspSettingsConfig(
    val enableDiagnostics: Boolean = true,
    val enableSemanticTokens: Boolean = true,
    val diagnosticsDelay: Int = 500,
    val maxCompletionItems: Int = 100,
    val autoImport: Boolean = true,
    val jdkHome: String? = null,
    val kotlinStdlib: String? = null
)
