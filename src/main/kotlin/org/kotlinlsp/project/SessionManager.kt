// by Claude
package org.kotlinlsp.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.BindingContext
import org.kotlinlsp.index.IndexManager
import org.kotlinlsp.index.SymbolLocation
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages document state and Kotlin compiler environment.
 * Provides PSI-based analysis with full type resolution for LSP features.
 */
class SessionManager {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    private var workspaceRoot: Path? = null

    // Compiler environment
    private var disposable: Disposable? = null
    private var environment: KotlinCoreEnvironment? = null
    private var psiFactory: KtPsiFactory? = null

    // Document content cache - maps URI to content
    private val documentContents = ConcurrentHashMap<String, String>()

    // KtFile cache - maps URI to parsed KtFile
    private val ktFileCache = ConcurrentHashMap<String, KtFile>()

    // Analysis cache - maps URI to BindingContext (for type resolution)
    private val analysisCache = ConcurrentHashMap<String, BindingContext>()

    // Collected diagnostics during analysis
    private val diagnosticsCollector = DiagnosticsCollector()

    // Project import
    private val gradleImporter = GradleImporter()
    private var kmpProject: KmpProject? = null

    // Symbol index for cross-file navigation - by Claude
    private var indexManager: IndexManager? = null

    fun initializeWorkspace(rootUri: String) {
        val uri = URI(rootUri)
        workspaceRoot = Paths.get(uri)
        logger.info("Workspace initialized at: $workspaceRoot")

        // Try to import as a Gradle/KMP project
        importProject()

        // Initialize compiler environment
        initializeCompilerEnvironment()

        // Build symbol index for cross-file navigation - by Claude
        buildSymbolIndex()
    }

    /**
     * Import the project using GradleImporter.
     */
    private fun importProject() {
        val root = workspaceRoot ?: return

        logger.info("Attempting to import project from: $root")
        kmpProject = gradleImporter.importProject(root)

        if (kmpProject != null) {
            logger.info("Project imported: ${kmpProject!!.name} with ${kmpProject!!.modules.size} modules")
            kmpProject!!.modules.forEach { module ->
                logger.info("  - ${module.name} (${module.platform}): ${module.sourceRoots.size} source roots, ${module.dependencies.size} dependencies")
            }
        } else {
            logger.info("No Gradle project found, using single-file mode")
        }
    }

    /**
     * Get the imported KMP project, if available.
     */
    fun getKmpProject(): KmpProject? = kmpProject

    /**
     * Build the symbol index from project source roots.
     * by Claude
     */
    private fun buildSymbolIndex() {
        val root = workspaceRoot ?: return

        // Create index manager with PSI factory provider
        indexManager = IndexManager { uri, content ->
            val factory = psiFactory ?: return@IndexManager null
            val fileName = uri.substringAfterLast("/")
            factory.createFile(fileName, content)
        }

        // Collect source roots
        val sourceRoots = mutableListOf<Path>()

        // From KMP project modules
        kmpProject?.modules?.forEach { module ->
            sourceRoots.addAll(module.sourceRoots)
        }

        // Fallback: scan common source directories
        if (sourceRoots.isEmpty()) {
            listOf("src/main/kotlin", "src/main/java", "src/commonMain/kotlin", "src")
                .map { root.resolve(it) }
                .filter { java.nio.file.Files.exists(it) }
                .forEach { sourceRoots.add(it) }
        }

        if (sourceRoots.isNotEmpty()) {
            indexManager?.buildIndex(sourceRoots)
        } else {
            logger.info("No source roots found for indexing")
        }
    }

    /**
     * Find symbols by simple name in the index.
     * by Claude
     */
    fun findSymbolsByName(name: String): List<SymbolLocation> {
        return indexManager?.findByName(name) ?: emptyList()
    }

    /**
     * Find a symbol by its fully qualified name.
     * by Claude
     */
    fun findSymbolByQualifiedName(fqName: String): SymbolLocation? {
        return indexManager?.findByQualifiedName(fqName)
    }

    /**
     * Update the index for a changed file.
     * by Claude
     */
    fun updateIndex(uri: String, content: String) {
        indexManager?.updateFile(uri, content)
    }

    /**
     * Get the IndexManager for direct access.
     * by Claude
     */
    fun getIndexManager(): IndexManager? = indexManager

    private fun initializeCompilerEnvironment() {
        logger.info("Initializing Kotlin compiler environment...")

        disposable = Disposer.newDisposable("SessionManager")

        val moduleName = kmpProject?.name ?: "lsp-module"

        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, diagnosticsCollector)
            put(JVMConfigurationKeys.JVM_TARGET, JvmTarget.JVM_17)
            put(CommonConfigurationKeys.MODULE_NAME, moduleName)

            // Collect all classpath roots
            val classpathRoots = mutableListOf<File>()

            // Add kotlin-stdlib to classpath for resolution
            findKotlinStdlib()?.let { stdlibPath ->
                logger.info("Found kotlin-stdlib at: $stdlibPath")
                classpathRoots.add(File(stdlibPath))
            }

            // Add dependencies from imported project
            kmpProject?.modules?.forEach { module ->
                module.dependencies.filter { !it.isKlib }.forEach { dep ->
                    val file = dep.path.toFile()
                    if (file.exists()) {
                        classpathRoots.add(file)
                        logger.debug("Added dependency to classpath: ${dep.name}")
                    }
                }
            }

            if (classpathRoots.isNotEmpty()) {
                addJvmClasspathRoots(classpathRoots)
                logger.info("Added ${classpathRoots.size} classpath roots")
            }
        }

        environment = KotlinCoreEnvironment.createForProduction(
            projectDisposable = disposable!!,
            configuration = configuration,
            configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES
        )

        psiFactory = KtPsiFactory(environment!!.project)

        logger.info("Kotlin compiler environment initialized")
    }

    /**
     * Finds the kotlin-stdlib JAR in the classpath.
     */
    private fun findKotlinStdlib(): String? {
        val classPath = System.getProperty("java.class.path") ?: return null
        return classPath.split(File.pathSeparator)
            .find { it.contains("kotlin-stdlib") && it.endsWith(".jar") && !it.contains("jdk") }
    }

    fun updateDocument(uri: String, content: String) {
        documentContents[uri] = content
        // Invalidate caches for this document
        ktFileCache.remove(uri)
        analysisCache.remove(uri)
        logger.debug("Document updated: $uri (${content.length} chars)")
    }

    fun closeDocument(uri: String) {
        documentContents.remove(uri)
        ktFileCache.remove(uri)
        analysisCache.remove(uri)
        logger.debug("Document closed: $uri")
    }

    fun getDocumentContent(uri: String): String? {
        return documentContents[uri]
    }

    /**
     * Get a KtFile for the given URI.
     * Creates and caches the KtFile from the document content.
     */
    fun getKtFile(uri: String): KtFile? {
        // Return cached file if available
        ktFileCache[uri]?.let { return it }

        // Get document content
        val content = documentContents[uri] ?: return null
        val factory = psiFactory ?: return null

        // Create KtFile from content
        val fileName = uri.substringAfterLast("/")
        val ktFile = factory.createFile(fileName, content)

        // Cache and return
        ktFileCache[uri] = ktFile
        logger.debug("Created KtFile for: $uri")
        return ktFile
    }

    /**
     * Get the Kotlin project from the compiler environment.
     */
    fun getProject() = environment?.project

    /**
     * Analyze a KtFile and return the BindingContext for type resolution.
     * The result is cached for performance.
     */
    fun analyzeFile(uri: String): BindingContext? {
        // Return cached analysis if available
        analysisCache[uri]?.let { return it }

        val ktFile = getKtFile(uri) ?: return null
        val env = environment ?: return null

        return try {
            diagnosticsCollector.clear()

            val result = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                project = env.project,
                files = listOf(ktFile),
                trace = org.jetbrains.kotlin.resolve.BindingTraceContext(env.project),
                configuration = env.configuration,
                packagePartProvider = env::createPackagePartProvider
            )

            val bindingContext = result.bindingContext
            analysisCache[uri] = bindingContext
            logger.debug("Analyzed file: $uri")
            bindingContext
        } catch (e: Exception) {
            logger.error("Analysis failed for $uri: ${e.message}", e)
            null
        }
    }

    /**
     * Get collected diagnostics from the last analysis.
     */
    fun getDiagnostics(): List<DiagnosticInfo> {
        return diagnosticsCollector.getDiagnostics()
    }

    fun invalidateSession() {
        ktFileCache.clear()
        analysisCache.clear()
        logger.debug("Session cache cleared")
    }

    fun dispose() {
        ktFileCache.clear()
        analysisCache.clear()
        documentContents.clear()

        disposable?.let {
            Disposer.dispose(it)
        }
        disposable = null
        environment = null
        psiFactory = null

        logger.info("SessionManager disposed")
    }
}

/**
 * Data class for diagnostic information.
 */
data class DiagnosticInfo(
    val severity: CompilerMessageSeverity,
    val message: String,
    val location: CompilerMessageSourceLocation?
)

/**
 * Collects compiler diagnostics during analysis.
 */
class DiagnosticsCollector : MessageCollector {
    private val diagnostics = mutableListOf<DiagnosticInfo>()

    override fun clear() {
        diagnostics.clear()
    }

    override fun hasErrors(): Boolean {
        return diagnostics.any { it.severity.isError }
    }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
        diagnostics.add(DiagnosticInfo(severity, message, location))
    }

    fun getDiagnostics(): List<DiagnosticInfo> = diagnostics.toList()
}
