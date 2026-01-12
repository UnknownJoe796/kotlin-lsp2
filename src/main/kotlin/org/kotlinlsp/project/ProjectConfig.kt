// by Claude - Explicit JSON project configuration
package org.kotlinlsp.project

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Explicit project configuration loaded from kmp-lsp.json.
 *
 * This allows users to manually configure their project structure
 * instead of relying on Gradle auto-detection.
 */
data class ProjectConfig(
    /** Project name */
    val name: String? = null,

    /** List of modules in the project */
    val modules: List<ModuleConfig> = emptyList(),

    /** Global dependencies applied to all modules */
    val dependencies: List<String> = emptyList(),

    /** Source roots to scan (if modules not specified) */
    val sourceRoots: List<String> = emptyList(),

    /** File patterns to exclude from analysis */
    val exclude: List<String> = emptyList(),

    /** LSP behavior settings */
    val settings: LspSettings = LspSettings()
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ProjectConfig::class.java)
        private val gson = Gson()

        const val CONFIG_FILE_NAME = "kmp-lsp.json"

        /**
         * Load configuration from a project root.
         * Returns null if no config file exists.
         */
        fun load(projectRoot: Path): ProjectConfig? {
            val configFile = projectRoot.resolve(CONFIG_FILE_NAME)
            if (!Files.exists(configFile)) {
                return null
            }

            return try {
                val content = Files.readString(configFile)
                val config = gson.fromJson(content, ProjectConfig::class.java)
                logger.info("Loaded project config from $configFile")
                config
            } catch (e: Exception) {
                logger.error("Failed to parse $CONFIG_FILE_NAME: ${e.message}", e)
                null
            }
        }

        /**
         * Generate a sample configuration file.
         */
        fun generateSample(): String {
            val sample = ProjectConfig(
                name = "my-kotlin-project",
                modules = listOf(
                    ModuleConfig(
                        name = "commonMain",
                        platform = "common",
                        sourceRoots = listOf("src/commonMain/kotlin"),
                        dependencies = emptyList(),
                        dependsOn = emptyList()
                    ),
                    ModuleConfig(
                        name = "jvmMain",
                        platform = "jvm",
                        sourceRoots = listOf("src/jvmMain/kotlin"),
                        dependencies = listOf(
                            "~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.0/*.jar"
                        ),
                        dependsOn = listOf("commonMain")
                    ),
                    ModuleConfig(
                        name = "iosMain",
                        platform = "ios",
                        sourceRoots = listOf("src/iosMain/kotlin"),
                        dependencies = emptyList(),
                        dependsOn = listOf("commonMain")
                    )
                ),
                dependencies = listOf(
                    // Global dependencies can use glob patterns
                    "libs/*.jar"
                ),
                exclude = listOf(
                    "**/build/**",
                    "**/.gradle/**",
                    "**/generated/**"
                ),
                settings = LspSettings(
                    enableDiagnostics = true,
                    enableSemanticTokens = true,
                    diagnosticsDelay = 500,
                    maxCompletionItems = 100
                )
            )
            return gson.newBuilder().setPrettyPrinting().create().toJson(sample)
        }
    }
}

/**
 * Configuration for a single module.
 */
data class ModuleConfig(
    /** Module name (used for dependsOn references) */
    val name: String,

    /** Platform: common, jvm, js, ios, macos, linux, windows, native */
    val platform: String = "jvm",

    /** Source root directories (relative to project root) */
    val sourceRoots: List<String> = emptyList(),

    /** Dependencies - paths to JARs or KLIBs (supports glob patterns) */
    val dependencies: List<String> = emptyList(),

    /** Other modules this module depends on (for expect/actual) */
    val dependsOn: List<String> = emptyList(),

    /** Whether this is a source module (vs test module) */
    val isSource: Boolean = true
)

/**
 * LSP behavior settings.
 */
data class LspSettings(
    /** Enable real-time diagnostics */
    val enableDiagnostics: Boolean = true,

    /** Enable semantic token highlighting */
    val enableSemanticTokens: Boolean = true,

    /** Delay before computing diagnostics (ms) */
    val diagnosticsDelay: Int = 500,

    /** Maximum completion items to return */
    val maxCompletionItems: Int = 100,

    /** Enable auto-import suggestions in completions */
    val autoImport: Boolean = true,

    /** JDK home path (auto-detected if not specified) */
    val jdkHome: String? = null,

    /** Kotlin stdlib path (auto-detected if not specified) */
    val kotlinStdlib: String? = null
)

/**
 * Convert ProjectConfig to KmpProject.
 */
fun ProjectConfig.toKmpProject(projectRoot: Path): KmpProject {
    val resolvedModules = modules.map { moduleConfig ->
        KmpModule(
            name = moduleConfig.name,
            platform = parsePlatform(moduleConfig.platform),
            sourceRoots = moduleConfig.sourceRoots.map { projectRoot.resolve(it) },
            dependencies = resolveDependencies(projectRoot, moduleConfig.dependencies + this.dependencies),
            dependsOn = moduleConfig.dependsOn,
            isSource = moduleConfig.isSource
        )
    }

    return KmpProject(
        name = this.name ?: projectRoot.fileName.toString(),
        rootPath = projectRoot,
        modules = resolvedModules
    )
}

/**
 * Parse platform string to KmpPlatform.
 */
private fun parsePlatform(platform: String): KmpPlatform {
    return when (platform.lowercase()) {
        "common" -> KmpPlatform.COMMON
        "jvm", "java" -> KmpPlatform.JVM
        "js", "javascript" -> KmpPlatform.JS
        "ios" -> KmpPlatform.NATIVE_IOS
        "macos", "osx" -> KmpPlatform.NATIVE_MACOS
        "linux" -> KmpPlatform.NATIVE_LINUX
        "windows", "mingw" -> KmpPlatform.NATIVE_WINDOWS
        "native" -> KmpPlatform.NATIVE_OTHER
        else -> KmpPlatform.JVM
    }
}

/**
 * Resolve dependency paths, expanding glob patterns.
 */
private fun resolveDependencies(projectRoot: Path, patterns: List<String>): List<LibraryDependency> {
    val dependencies = mutableListOf<LibraryDependency>()

    for (pattern in patterns) {
        val expandedPattern = pattern.replace("~", System.getProperty("user.home"))

        if (pattern.contains("*")) {
            // Glob pattern - expand it
            try {
                val searchRoot = if (expandedPattern.startsWith("/")) {
                    Path.of(expandedPattern.substringBefore("*"))
                } else {
                    projectRoot.resolve(expandedPattern.substringBefore("*"))
                }

                if (Files.exists(searchRoot)) {
                    Files.walk(searchRoot, 5).use { stream ->
                        stream.filter { path ->
                            !Files.isDirectory(path) &&
                            (path.toString().endsWith(".jar") || path.toString().endsWith(".klib"))
                        }.forEach { path ->
                            dependencies.add(LibraryDependency.fromPath(path))
                        }
                    }
                }
            } catch (e: Exception) {
                // Skip invalid patterns
            }
        } else {
            // Direct path
            val path = if (expandedPattern.startsWith("/")) {
                Path.of(expandedPattern)
            } else {
                projectRoot.resolve(expandedPattern)
            }

            if (Files.exists(path)) {
                dependencies.add(LibraryDependency.fromPath(path))
            }
        }
    }

    return dependencies
}
