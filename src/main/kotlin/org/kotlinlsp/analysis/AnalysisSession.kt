// by Claude - Analysis API based session management
package org.kotlinlsp.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.js.JsPlatforms
import org.jetbrains.kotlin.platform.konan.NativePlatforms
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.kotlinlsp.project.KmpModule
import org.kotlinlsp.project.KmpPlatform
import org.kotlinlsp.project.KmpProject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Kotlin Analysis API sessions for LSP operations.
 * Replaces the old SessionManager that used TopDownAnalyzerFacadeForJVM/BindingContext.
 *
 * Based on Dokka's KotlinAnalysis.kt patterns for multiplatform support.
 */
class AnalysisSession {

    private val logger = LoggerFactory.getLogger(AnalysisSession::class.java)

    private var workspaceRoot: Path? = null

    // Analysis API session
    private var disposable: Disposable? = null
    private var analysisSession: StandaloneAnalysisAPISession? = null

    // Document content cache - maps URI to content
    private val documentContents = ConcurrentHashMap<String, String>()

    // KtFile cache - maps URI to parsed KtFile
    private val ktFileCache = ConcurrentHashMap<String, KtFile>()

    // Module lookup - maps module name to KaModule
    private val moduleCache = ConcurrentHashMap<String, KaModule>()

    // Platform cache for files - maps URI to platform
    private val filePlatformCache = ConcurrentHashMap<String, KmpPlatform>()

    // Project reference
    private var kmpProject: KmpProject? = null

    /**
     * Initialize the analysis session for a workspace.
     */
    fun initialize(rootUri: String, project: KmpProject?) {
        val uri = URI(rootUri)
        workspaceRoot = Paths.get(uri)
        kmpProject = project
        logger.info("Initializing Analysis API session at: $workspaceRoot")

        createAnalysisSession()
    }

    /**
     * Create the Analysis API session with proper module configuration.
     * Following Dokka's KotlinAnalysis.kt patterns.
     */
    private fun createAnalysisSession() {
        // Dispose old session if exists
        dispose()

        disposable = Disposer.newDisposable("AnalysisSession")

        try {
            val project = kmpProject

            analysisSession = buildStandaloneAnalysisAPISession(projectDisposable = disposable!!) {
                buildKtModuleProvider {
                    val platform = platform // Use IntelliJ platform from context

                    // Find JDK for SDK module
                    val jdkHome = findJdkHome()

                    // Create SDK module if JDK found
                    val sdkModule = if (jdkHome != null) {
                        addModule(buildKtSdkModule {
                            this.platform = JvmPlatforms.defaultJvmPlatform
                            addBinaryRootsFromJdkHome(jdkHome.toPath(), isJre = false)
                            libraryName = "JDK"
                        })
                    } else null

                    if (project != null && project.modules.isNotEmpty()) {
                        // Build modules from KMP project structure
                        buildProjectModules(project, sdkModule)
                    } else {
                        // Single-file mode: create a simple source module
                        buildSingleFileModule(sdkModule)
                    }
                }
            }

            logger.info("Analysis API session created successfully")

        } catch (e: Exception) {
            logger.error("Failed to create Analysis API session: ${e.message}", e)
            throw e
        }
    }

    /**
     * Build modules for a KMP project following Dokka patterns.
     * Handles platform-specific modules and expect/actual relationships.
     */
    private fun org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder.buildProjectModules(
        project: KmpProject,
        sdkModule: KaModule?
    ) {
        // First pass: sort modules by dependency order (common before platform)
        val sortedModules = topologicalSortModules(project.modules)

        // Maps module name to created KaModule for dependency resolution
        val createdModules = mutableMapOf<String, KaModule>()

        for (module in sortedModules) {
            val targetPlatform = module.platform.toTargetPlatform()

            // Create library module for this module's dependencies
            val libraryModule = if (module.dependencies.isNotEmpty()) {
                val binaryRoots = module.dependencies.mapNotNull { dep ->
                    if (dep.path.toFile().exists()) dep.path else null
                }

                if (binaryRoots.isNotEmpty()) {
                    addModule(buildKtLibraryModule {
                        this.platform = targetPlatform
                        addBinaryRoots(binaryRoots)
                        libraryName = "${module.name}-deps"
                    })
                } else null
            } else null

            // Create source module
            val sourceModule = addModule(buildKtSourceModule {
                this.platform = targetPlatform
                moduleName = module.name

                // Add source roots
                module.sourceRoots.forEach { sourceRoot ->
                    if (sourceRoot.toFile().exists()) {
                        addSourceRoot(sourceRoot)
                    }
                }

                // Add SDK dependency
                if (sdkModule != null && targetPlatform == JvmPlatforms.defaultJvmPlatform) {
                    addRegularDependency(sdkModule)
                }

                // Add library dependencies
                if (libraryModule != null) {
                    addRegularDependency(libraryModule)
                }

                // Add dependsOn dependencies (for expect/actual)
                module.dependsOn.forEach { depName ->
                    createdModules[depName]?.let { depModule ->
                        addDependsOnDependency(depModule)
                    }
                }
            })

            createdModules[module.name] = sourceModule
            moduleCache[module.name] = sourceModule
        }

        logger.info("Created ${createdModules.size} modules for project ${project.name}")
    }

    /**
     * Build a simple module for single-file mode.
     */
    private fun org.jetbrains.kotlin.analysis.project.structure.builder.KtModuleProviderBuilder.buildSingleFileModule(
        sdkModule: KaModule?
    ) {
        val workspaceDir = workspaceRoot ?: return

        // Find kotlin-stdlib
        val stdlibPath = findKotlinStdlib()

        // Create library module for stdlib
        val libraryModule = if (stdlibPath != null) {
            addModule(buildKtLibraryModule {
                this.platform = JvmPlatforms.defaultJvmPlatform
                addBinaryRoot(Paths.get(stdlibPath))
                libraryName = "kotlin-stdlib"
            })
        } else null

        // Find source roots
        val sourceRoots = findSourceRoots(workspaceDir)

        val sourceModule = addModule(buildKtSourceModule {
            this.platform = JvmPlatforms.defaultJvmPlatform
            moduleName = "workspace"

            sourceRoots.forEach { root ->
                addSourceRoot(root)
            }

            if (sdkModule != null) {
                addRegularDependency(sdkModule)
            }
            if (libraryModule != null) {
                addRegularDependency(libraryModule)
            }
        })

        moduleCache["workspace"] = sourceModule
    }

    /**
     * Topologically sort modules so dependencies come before dependents.
     */
    private fun topologicalSortModules(modules: List<KmpModule>): List<KmpModule> {
        val result = mutableListOf<KmpModule>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val moduleMap = modules.associateBy { it.name }

        fun visit(module: KmpModule) {
            if (module.name in visited) return
            if (module.name in visiting) {
                logger.warn("Circular dependency detected involving ${module.name}")
                return
            }

            visiting.add(module.name)

            // Visit dependencies first
            module.dependsOn.forEach { depName ->
                moduleMap[depName]?.let { visit(it) }
            }

            visiting.remove(module.name)
            visited.add(module.name)
            result.add(module)
        }

        modules.forEach { visit(it) }
        return result
    }

    /**
     * Update document content and invalidate caches.
     */
    fun updateDocument(uri: String, content: String) {
        documentContents[uri] = content
        ktFileCache.remove(uri)
        logger.debug("Document updated: $uri (${content.length} chars)")
    }

    /**
     * Close a document and clean up caches.
     */
    fun closeDocument(uri: String) {
        documentContents.remove(uri)
        ktFileCache.remove(uri)
        logger.debug("Document closed: $uri")
    }

    /**
     * Get document content by URI.
     */
    fun getDocumentContent(uri: String): String? = documentContents[uri]

    /**
     * Get or create a KtFile for the given URI.
     */
    fun getKtFile(uri: String): KtFile? {
        ktFileCache[uri]?.let { return it }

        val content = documentContents[uri] ?: return null
        val session = analysisSession ?: return null

        val fileName = uri.substringAfterLast("/")

        // Create KtFile using the Analysis API project
        val ktFile = KtPsiFactory(session.project).createFile(fileName, content)
        ktFileCache[uri] = ktFile

        logger.debug("Created KtFile for: $uri")
        return ktFile
    }

    /**
     * Execute an analysis block on a KtFile.
     * This is the main entry point for Analysis API operations.
     */
    fun <R> withAnalysis(ktFile: KtFile, block: KaSession.() -> R): R? {
        return try {
            analyze(ktFile, block)
        } catch (e: Exception) {
            logger.error("Analysis failed: ${e.message}", e)
            null
        }
    }

    /**
     * Get the platform for a file URI.
     */
    fun getPlatformForUri(uri: String): KmpPlatform {
        filePlatformCache[uri]?.let { return it }

        val platform = detectPlatformFromUri(uri)
        filePlatformCache[uri] = platform
        return platform
    }

    private fun detectPlatformFromUri(uri: String): KmpPlatform {
        val path = try {
            Paths.get(URI(uri))
        } catch (e: Exception) {
            Paths.get(uri.removePrefix("file://"))
        }

        // Check against each module's source roots
        kmpProject?.modules?.forEach { module ->
            module.sourceRoots.forEach { sourceRoot ->
                if (path.startsWith(sourceRoot)) {
                    return module.platform
                }
            }
        }

        // Fallback: detect from path segments
        val pathStr = path.toString()
        return when {
            pathStr.contains("/commonMain/") || pathStr.contains("\\commonMain\\") -> KmpPlatform.COMMON
            pathStr.contains("/jvmMain/") || pathStr.contains("\\jvmMain\\") -> KmpPlatform.JVM
            pathStr.contains("/jsMain/") || pathStr.contains("\\jsMain\\") -> KmpPlatform.JS
            pathStr.contains("/iosMain/") || pathStr.contains("\\iosMain\\") -> KmpPlatform.NATIVE_IOS
            pathStr.contains("/macosMain/") || pathStr.contains("\\macosMain\\") -> KmpPlatform.NATIVE_MACOS
            pathStr.contains("/linuxMain/") || pathStr.contains("\\linuxMain\\") -> KmpPlatform.NATIVE_LINUX
            pathStr.contains("/mingwMain/") || pathStr.contains("\\mingwMain\\") -> KmpPlatform.NATIVE_WINDOWS
            pathStr.contains("/nativeMain/") || pathStr.contains("\\nativeMain\\") -> KmpPlatform.NATIVE_OTHER
            else -> KmpPlatform.COMMON
        }
    }

    /**
     * Get the module for a file URI.
     */
    fun getModuleForUri(uri: String): KmpModule? {
        val path = try {
            Paths.get(URI(uri))
        } catch (e: Exception) {
            Paths.get(uri.removePrefix("file://"))
        }

        kmpProject?.modules?.forEach { module ->
            module.sourceRoots.forEach { sourceRoot ->
                if (path.startsWith(sourceRoot)) {
                    return module
                }
            }
        }
        return null
    }

    /**
     * Get the KMP project.
     */
    fun getKmpProject(): KmpProject? = kmpProject

    /**
     * Invalidate all caches to force re-analysis.
     */
    fun invalidateSession() {
        ktFileCache.clear()
        logger.debug("Session cache cleared")
    }

    /**
     * Dispose of the analysis session.
     */
    fun dispose() {
        ktFileCache.clear()
        documentContents.clear()
        moduleCache.clear()

        disposable?.let {
            Disposer.dispose(it)
        }
        disposable = null
        analysisSession = null

        logger.info("AnalysisSession disposed")
    }

    // Helper functions

    private fun findJdkHome(): File? {
        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            val jdkHome = File(javaHome)
            if (jdkHome.exists()) {
                // java.home may point to JRE inside JDK, go up one level
                val parent = jdkHome.parentFile
                if (parent != null && File(parent, "lib/tools.jar").exists()) {
                    return parent
                }
                return jdkHome
            }
        }
        return null
    }

    private fun findKotlinStdlib(): String? {
        val classPath = System.getProperty("java.class.path") ?: return null
        return classPath.split(File.pathSeparator)
            .find { it.contains("kotlin-stdlib") && it.endsWith(".jar") && !it.contains("jdk") }
    }

    private fun findSourceRoots(workspaceDir: Path): List<Path> {
        return listOf("src/main/kotlin", "src/main/java", "src/commonMain/kotlin", "src")
            .map { workspaceDir.resolve(it) }
            .filter { java.nio.file.Files.exists(it) }
    }

    companion object {
        /**
         * Convert KmpPlatform to Analysis API TargetPlatform.
         */
        fun KmpPlatform.toTargetPlatform(): TargetPlatform = when (this) {
            KmpPlatform.JVM -> JvmPlatforms.defaultJvmPlatform
            KmpPlatform.JS -> JsPlatforms.defaultJsPlatform
            KmpPlatform.NATIVE_IOS,
            KmpPlatform.NATIVE_MACOS,
            KmpPlatform.NATIVE_LINUX,
            KmpPlatform.NATIVE_WINDOWS,
            KmpPlatform.NATIVE_OTHER -> NativePlatforms.unspecifiedNativePlatform
            KmpPlatform.COMMON -> JvmPlatforms.defaultJvmPlatform  // Fallback for common
        }
    }
}
