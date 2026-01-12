// by Claude - Task for generating kmp-lsp.json
package org.kotlinlsp.gradle

import com.google.gson.GsonBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

/**
 * Task that generates the kmp-lsp.json configuration file.
 */
abstract class GenerateLspConfigTask : DefaultTask() {

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val includeTestSources: Property<Boolean>

    @get:Input
    abstract val includeDependencies: Property<Boolean>

    @get:Input
    abstract val includeTransitiveDependencies: Property<Boolean>

    @get:Input
    abstract val prettyPrint: Property<Boolean>

    @get:Input
    abstract val additionalSourceRoots: ListProperty<String>

    @get:Input
    abstract val excludePatterns: ListProperty<String>

    @TaskAction
    fun generate() {
        val config = buildConfig()

        val gson = if (prettyPrint.get()) {
            GsonBuilder().setPrettyPrinting().create()
        } else {
            GsonBuilder().create()
        }

        val json = gson.toJson(config)
        val output = outputFile.get().asFile

        output.writeText(json)
        logger.lifecycle("Generated LSP config: ${output.absolutePath}")
    }

    private fun buildConfig(): LspConfig {
        val modules = mutableListOf<ModuleConfig>()

        // Try to get Kotlin Multiplatform extension
        val kotlinExtension = project.extensions.findByName("kotlin")

        if (kotlinExtension != null) {
            // KMP project - extract from Kotlin plugin
            modules.addAll(extractKmpModules(kotlinExtension))
        } else {
            // Regular Kotlin/JVM project
            modules.addAll(extractJvmModules())
        }

        // Add additional source roots
        additionalSourceRoots.get().forEach { root ->
            val existingModule = modules.find { it.platform == "jvm" }
            if (existingModule != null) {
                val newRoots = existingModule.sourceRoots.toMutableList()
                newRoots.add(root)
                val index = modules.indexOf(existingModule)
                modules[index] = existingModule.copy(sourceRoots = newRoots)
            }
        }

        return LspConfig(
            name = project.name,
            modules = modules,
            dependencies = emptyList(), // Module-level deps are preferred
            exclude = excludePatterns.get(),
            settings = LspSettings()
        )
    }

    private fun extractKmpModules(kotlinExtension: Any): List<ModuleConfig> {
        val modules = mutableListOf<ModuleConfig>()

        try {
            // Use reflection to access KMP source sets
            val sourceSetsMethod = kotlinExtension.javaClass.getMethod("getSourceSets")
            val sourceSets = sourceSetsMethod.invoke(kotlinExtension) as? Iterable<*> ?: return modules

            logger.lifecycle("Found ${sourceSets.count()} source sets")

            for (sourceSet in sourceSets) {
                if (sourceSet == null) continue

                val name = sourceSet.javaClass.getMethod("getName").invoke(sourceSet) as? String ?: continue
                logger.lifecycle("Processing source set: $name")

                // Skip test source sets if configured
                if (!includeTestSources.get() && name.contains("Test", ignoreCase = true)) {
                    logger.lifecycle("  Skipping test source set: $name")
                    continue
                }

                val platform = detectPlatform(name)
                val sourceRoots = extractSourceRootsComprehensive(sourceSet, name)
                val dependencies = if (includeDependencies.get()) {
                    extractDependencies(sourceSet, name)
                } else {
                    emptyList()
                }
                val dependsOn = extractDependsOn(sourceSet)

                logger.lifecycle("  Platform: $platform, Source roots: $sourceRoots, DependsOn: $dependsOn")

                if (sourceRoots.isNotEmpty()) {
                    modules.add(ModuleConfig(
                        name = name,
                        platform = platform,
                        sourceRoots = sourceRoots,
                        dependencies = dependencies,
                        dependsOn = dependsOn,
                        isSource = !name.contains("Test", ignoreCase = true)
                    ))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract KMP modules: ${e.message}", e)
            // Fall back to JVM extraction
            return extractJvmModules()
        }

        return modules
    }

    private fun extractJvmModules(): List<ModuleConfig> {
        val modules = mutableListOf<ModuleConfig>()

        // Get source sets from Java/Kotlin plugin
        val sourceSets = project.extensions.findByName("sourceSets") as? org.gradle.api.tasks.SourceSetContainer
            ?: return modules

        for (sourceSet in sourceSets) {
            val name = sourceSet.name

            // Skip test source sets if configured
            if (!includeTestSources.get() && name.contains("test", ignoreCase = true)) {
                continue
            }

            val sourceRoots = mutableListOf<String>()

            // Kotlin sources
            sourceSet.extensions.findByName("kotlin")?.let { kotlinSourceSet ->
                try {
                    val srcDirs = kotlinSourceSet.javaClass.getMethod("getSrcDirs").invoke(kotlinSourceSet) as? Set<*>
                    srcDirs?.filterIsInstance<File>()?.filter { it.exists() }?.forEach {
                        sourceRoots.add(project.relativePath(it))
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            // Java sources as fallback
            if (sourceRoots.isEmpty()) {
                sourceSet.java.srcDirs.filter { it.exists() }.forEach {
                    sourceRoots.add(project.relativePath(it))
                }
            }

            val dependencies = if (includeDependencies.get()) {
                extractConfigurationDependencies(sourceSet.compileClasspathConfigurationName)
            } else {
                emptyList()
            }

            if (sourceRoots.isNotEmpty()) {
                modules.add(ModuleConfig(
                    name = name,
                    platform = "jvm",
                    sourceRoots = sourceRoots,
                    dependencies = dependencies,
                    dependsOn = if (name == "main") emptyList() else listOf("main"),
                    isSource = !name.contains("test", ignoreCase = true)
                ))
            }
        }

        return modules
    }

    private fun extractSourceRoots(sourceSet: Any): List<String> {
        return extractSourceRootsComprehensive(sourceSet, "unknown")
    }

    private fun extractSourceRootsComprehensive(sourceSet: Any, sourceSetName: String): List<String> {
        val roots = mutableListOf<String>()

        // Method 1: Try to get kotlin source directories via getKotlin()
        try {
            val kotlinMethod = sourceSet.javaClass.getMethod("getKotlin")
            val kotlinSourceSet = kotlinMethod.invoke(sourceSet)

            val srcDirsMethod = kotlinSourceSet.javaClass.getMethod("getSrcDirs")
            val srcDirs = srcDirsMethod.invoke(kotlinSourceSet) as? Set<*>

            srcDirs?.filterIsInstance<File>()?.forEach {
                if (it.exists()) {
                    roots.add(project.relativePath(it))
                }
            }
        } catch (e: Exception) {
            logger.debug("Method 1 (getKotlin) failed for $sourceSetName: ${e.message}")
        }

        // Method 2: Try via SourceDirectorySet
        if (roots.isEmpty()) {
            try {
                val kotlinMethod = sourceSet.javaClass.methods.find { it.name == "getKotlin" }
                if (kotlinMethod != null) {
                    val kotlinSourceDirSet = kotlinMethod.invoke(sourceSet)
                    val srcDirsMethod = kotlinSourceDirSet.javaClass.methods.find { it.name == "getSrcDirs" }
                    if (srcDirsMethod != null) {
                        val srcDirs = srcDirsMethod.invoke(kotlinSourceDirSet) as? Set<*>
                        srcDirs?.filterIsInstance<File>()?.forEach {
                            if (it.exists()) {
                                roots.add(project.relativePath(it))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("Method 2 (SourceDirectorySet) failed for $sourceSetName: ${e.message}")
            }
        }

        // Method 3: Fall back to conventional path
        if (roots.isEmpty()) {
            val conventionalPath = project.file("src/$sourceSetName/kotlin")
            if (conventionalPath.exists()) {
                roots.add(project.relativePath(conventionalPath))
            }
        }

        return roots
    }

    private fun extractDependencies(sourceSet: Any, sourceSetName: String): List<String> {
        val deps = mutableListOf<String>()

        // Try to find the compilation's compile classpath
        val configName = "${sourceSetName}CompileClasspath"
        val altConfigName = "${sourceSetName}CompileDependenciesMetadata"

        val config = project.configurations.findByName(configName)
            ?: project.configurations.findByName(altConfigName)

        if (config != null && config.isCanBeResolved) {
            deps.addAll(extractConfigurationDependencies(config))
        }

        return deps
    }

    private fun extractConfigurationDependencies(configName: String): List<String> {
        val config = project.configurations.findByName(configName) ?: return emptyList()
        return extractConfigurationDependencies(config)
    }

    private fun extractConfigurationDependencies(config: Configuration): List<String> {
        val deps = mutableListOf<String>()

        if (!config.isCanBeResolved) {
            return deps
        }

        try {
            val resolved = config.resolvedConfiguration
            val artifacts = if (includeTransitiveDependencies.get()) {
                resolved.resolvedArtifacts
            } else {
                resolved.firstLevelModuleDependencies.flatMap { it.moduleArtifacts }
            }

            for (artifact in artifacts) {
                val file = artifact.file
                if (file.exists() && (file.extension == "jar" || file.extension == "klib")) {
                    deps.add(file.absolutePath)
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not resolve dependencies for ${config.name}: ${e.message}")
        }

        return deps
    }

    private fun extractDependsOn(sourceSet: Any): List<String> {
        val dependsOn = mutableListOf<String>()

        try {
            val dependsOnMethod = sourceSet.javaClass.getMethod("getDependsOn")
            val deps = dependsOnMethod.invoke(sourceSet) as? Set<*>

            deps?.forEach { dep ->
                val nameMethod = dep?.javaClass?.getMethod("getName")
                val name = nameMethod?.invoke(dep) as? String
                if (name != null) {
                    dependsOn.add(name)
                }
            }
        } catch (e: Exception) {
            logger.debug("Could not extract dependsOn: ${e.message}")
        }

        return dependsOn
    }

    private fun detectPlatform(sourceSetName: String): String {
        val name = sourceSetName.lowercase()
        return when {
            name.contains("common") -> "common"
            name.contains("jvm") -> "jvm"
            name.contains("js") -> "js"
            name.contains("ios") -> "ios"
            name.contains("macos") -> "macos"
            name.contains("linux") -> "linux"
            name.contains("mingw") || name.contains("windows") -> "windows"
            name.contains("native") -> "native"
            name.contains("android") -> "jvm" // Android uses JVM platform
            else -> "jvm" // Default to JVM
        }
    }
}

// Data classes for JSON output
data class LspConfig(
    val name: String,
    val modules: List<ModuleConfig>,
    val dependencies: List<String>,
    val exclude: List<String>,
    val settings: LspSettings
)

data class ModuleConfig(
    val name: String,
    val platform: String,
    val sourceRoots: List<String>,
    val dependencies: List<String>,
    val dependsOn: List<String>,
    val isSource: Boolean = true
)

data class LspSettings(
    val enableDiagnostics: Boolean = true,
    val enableSemanticTokens: Boolean = true,
    val diagnosticsDelay: Int = 500,
    val maxCompletionItems: Int = 100,
    val autoImport: Boolean = true,
    val jdkHome: String? = null,
    val kotlinStdlib: String? = null
)
