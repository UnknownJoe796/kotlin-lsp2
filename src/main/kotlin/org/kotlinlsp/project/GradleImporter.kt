// by Claude
package org.kotlinlsp.project

import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.idea.IdeaProject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Imports KMP projects using the Gradle Tooling API.
 *
 * This importer parses build.gradle.kts files and extracts:
 * - Source sets and their directories
 * - Dependencies (JARs and KLIBs)
 * - Module dependencies (for expect/actual support)
 *
 * Priority order:
 * 1. Explicit kmp-lsp.json configuration (if present)
 * 2. Gradle Tooling API
 * 3. Manual directory scanning fallback
 */
class GradleImporter {

    private val logger = LoggerFactory.getLogger(GradleImporter::class.java)

    /**
     * Import a project from the given root directory.
     *
     * Checks for explicit kmp-lsp.json first, then falls back to Gradle detection.
     */
    fun importProject(projectRoot: Path): KmpProject? {
        // Priority 1: Explicit configuration file
        val explicitConfig = ProjectConfig.load(projectRoot)
        if (explicitConfig != null) {
            logger.info("Using explicit kmp-lsp.json configuration")
            return explicitConfig.toKmpProject(projectRoot)
        }

        // Priority 2: Gradle auto-detection
        if (!isGradleProject(projectRoot)) {
            logger.warn("Not a Gradle project and no kmp-lsp.json found: $projectRoot")
            return fallbackManualImport(projectRoot)
        }

        logger.info("Importing Gradle project from: $projectRoot")

        return try {
            val connector = GradleConnector.newConnector()
                .forProjectDirectory(projectRoot.toFile())

            connector.connect().use { connection ->
                importProjectFromConnection(projectRoot, connection)
            }
        } catch (e: Exception) {
            logger.error("Failed to import Gradle project: ${e.message}", e)
            // Fall back to manual scanning if Gradle connection fails
            fallbackManualImport(projectRoot)
        }
    }

    private fun importProjectFromConnection(projectRoot: Path, connection: ProjectConnection): KmpProject {
        logger.info("Connected to Gradle, fetching project model...")

        // Try to get the IdeaProject model
        val ideaProject = try {
            connection.getModel(IdeaProject::class.java)
        } catch (e: Exception) {
            logger.warn("Could not get IdeaProject model: ${e.message}")
            null
        }

        if (ideaProject != null) {
            return importFromIdeaProject(projectRoot, ideaProject)
        }

        // Fall back to manual scanning
        return fallbackManualImport(projectRoot)
    }

    private fun importFromIdeaProject(projectRoot: Path, ideaProject: IdeaProject): KmpProject {
        val modules = mutableListOf<KmpModule>()

        ideaProject.modules.forEach { ideaModule ->
            val moduleName = ideaModule.name
            val platform = KmpPlatform.fromSourceSetName(moduleName)

            // Collect source directories
            val sourceRoots = mutableListOf<Path>()
            ideaModule.contentRoots.forEach { contentRoot ->
                contentRoot.sourceDirectories.forEach { srcDir ->
                    val srcPath = srcDir.directory.toPath()
                    if (srcPath.exists()) {
                        sourceRoots.add(srcPath)
                    }
                }
            }

            // Collect dependencies
            val dependencies = mutableListOf<LibraryDependency>()
            ideaModule.dependencies.forEach { dep ->
                // This is limited - IdeaProject doesn't give us full dependency info
                // We'd need to use a custom Gradle model for full KLIB support
            }

            if (sourceRoots.isNotEmpty()) {
                modules.add(KmpModule(
                    name = moduleName,
                    platform = platform,
                    sourceRoots = sourceRoots,
                    dependencies = dependencies,
                    dependsOn = emptyList()  // Would need custom model
                ))
            }
        }

        return KmpProject(
            name = ideaProject.name,
            rootPath = projectRoot,
            modules = modules
        )
    }

    /**
     * Manual fallback import when Gradle Tooling API connection fails.
     * Scans the project directory for standard KMP source set layout.
     */
    private fun fallbackManualImport(projectRoot: Path): KmpProject {
        logger.info("Using manual project import for: $projectRoot")

        val modules = mutableListOf<KmpModule>()
        val srcDir = projectRoot.resolve("src")

        if (srcDir.exists() && srcDir.isDirectory()) {
            // Scan for source sets: src/commonMain, src/jvmMain, src/iosMain, etc.
            Files.list(srcDir).use { stream ->
                stream.forEach { sourceSetDir ->
                    if (sourceSetDir.isDirectory()) {
                        val sourceSetName = sourceSetDir.name
                        val platform = KmpPlatform.fromSourceSetName(sourceSetName)

                        // Look for kotlin source directory
                        val kotlinDir = sourceSetDir.resolve("kotlin")
                        val sourceRoots = if (kotlinDir.exists() && kotlinDir.isDirectory()) {
                            listOf(kotlinDir)
                        } else if (sourceSetDir.resolve("java").exists()) {
                            listOf(sourceSetDir.resolve("java"))
                        } else {
                            emptyList()
                        }

                        if (sourceRoots.isNotEmpty()) {
                            modules.add(KmpModule(
                                name = sourceSetName,
                                platform = platform,
                                sourceRoots = sourceRoots,
                                dependencies = findDependencies(projectRoot, platform),
                                dependsOn = inferDependsOn(sourceSetName)
                            ))
                        }
                    }
                }
            }
        }

        // Also check for single-module project layout (src/main/kotlin)
        val mainKotlin = projectRoot.resolve("src/main/kotlin")
        if (mainKotlin.exists() && mainKotlin.isDirectory()) {
            modules.add(KmpModule(
                name = "main",
                platform = KmpPlatform.JVM,
                sourceRoots = listOf(mainKotlin),
                dependencies = findDependencies(projectRoot, KmpPlatform.JVM),
                dependsOn = emptyList()
            ))
        }

        return KmpProject(
            name = projectRoot.name,
            rootPath = projectRoot,
            modules = modules
        )
    }

    /**
     * Find dependencies by scanning the build directory for resolved artifacts.
     */
    private fun findDependencies(projectRoot: Path, platform: KmpPlatform): List<LibraryDependency> {
        val dependencies = mutableListOf<LibraryDependency>()

        // Check Gradle cache for dependencies
        val gradleCache = projectRoot.resolve("build/classes")
        if (gradleCache.exists()) {
            // Scan for KLIBs
            scanForLibraries(gradleCache, dependencies)
        }

        // Check for local libs directory
        val libsDir = projectRoot.resolve("libs")
        if (libsDir.exists() && libsDir.isDirectory()) {
            scanForLibraries(libsDir, dependencies)
        }

        return dependencies
    }

    private fun scanForLibraries(dir: Path, dependencies: MutableList<LibraryDependency>) {
        try {
            Files.walk(dir).use { stream ->
                stream.filter { path ->
                    !path.isDirectory() &&
                    (path.extension == "jar" || path.extension == "klib")
                }.forEach { path ->
                    dependencies.add(LibraryDependency.fromPath(path))
                }
            }
        } catch (e: Exception) {
            // Ignore scan errors
        }
    }

    /**
     * Infer dependsOn relationships from source set name conventions.
     */
    private fun inferDependsOn(sourceSetName: String): List<String> {
        return when {
            sourceSetName.endsWith("Main") && !sourceSetName.startsWith("common") -> {
                // Platform main depends on commonMain
                listOf("commonMain")
            }
            sourceSetName.endsWith("Test") -> {
                // Tests depend on their main source set
                val mainName = sourceSetName.replace("Test", "Main")
                if (sourceSetName.startsWith("common")) {
                    listOf(mainName)
                } else {
                    listOf(mainName, "commonTest")
                }
            }
            else -> emptyList()
        }
    }

    private fun isGradleProject(path: Path): Boolean {
        return path.resolve("build.gradle.kts").exists() ||
               path.resolve("build.gradle").exists() ||
               path.resolve("settings.gradle.kts").exists() ||
               path.resolve("settings.gradle").exists()
    }
}
