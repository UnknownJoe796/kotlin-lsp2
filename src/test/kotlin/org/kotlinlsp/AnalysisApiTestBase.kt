// by Claude - Base class for Analysis API tests with full context
package org.kotlinlsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Base class for tests that need full Analysis API context.
 * Creates a proper module structure with stdlib dependency.
 *
 * Unlike AnalysisSession.getKtFile() which creates detached PSI files via KtPsiFactory,
 * this base class registers source files as part of a KtSourceModule during session
 * creation. This enables:
 * - Stdlib type resolution (List, String, Int, etc.)
 * - Cross-file symbol resolution
 * - Accurate diagnostics
 */
abstract class AnalysisApiTestBase {

    protected lateinit var testProjectDir: Path
    protected lateinit var session: StandaloneAnalysisAPISession
    protected lateinit var sourceFiles: Map<String, KtFile>
    private lateinit var disposable: Disposable

    /**
     * Set up test environment with given source files.
     * @param sources Map of filename to content
     */
    protected fun setupProject(sources: Map<String, String>) {
        // Create temp directory in ./local to avoid permission issues
        val localDir = File("local").also { it.mkdirs() }
        testProjectDir = Files.createTempDirectory(localDir.toPath(), "kotlin-lsp-test")

        // Write source files
        sources.forEach { (name, content) ->
            Files.writeString(testProjectDir.resolve(name), content)
        }

        // Find kotlin-stdlib from classpath
        val stdlibPath = findKotlinStdlib()

        // Create disposable for session lifecycle
        disposable = Disposer.newDisposable("AnalysisApiTestBase")

        // Build Analysis API session with proper module structure
        session = buildStandaloneAnalysisAPISession(projectDisposable = disposable) {
            buildKtModuleProvider {
                // IMPORTANT: Must set platform on the builder before adding modules
                this.platform = JvmPlatforms.defaultJvmPlatform

                // Add stdlib as library module
                val stdlibModule = if (stdlibPath != null) {
                    addModule(buildKtLibraryModule {
                        this.platform = JvmPlatforms.defaultJvmPlatform
                        addBinaryRoot(java.nio.file.Paths.get(stdlibPath))
                        libraryName = "kotlin-stdlib"
                    })
                } else null

                // Add source module with test files
                addModule(buildKtSourceModule {
                    this.platform = JvmPlatforms.defaultJvmPlatform
                    moduleName = "test"
                    addSourceRoot(testProjectDir)
                    if (stdlibModule != null) {
                        addRegularDependency(stdlibModule)
                    }
                })
            }
        }

        // Collect KtFiles from the session
        sourceFiles = session.modulesWithFiles
            .flatMap { it.value }
            .filterIsInstance<KtFile>()
            .associateBy { it.name }
    }

    /**
     * Execute analysis on a file.
     */
    protected fun <R> analyzeFile(fileName: String, block: KaSession.(KtFile) -> R): R {
        val ktFile = sourceFiles[fileName]
            ?: error("File not found: $fileName. Available: ${sourceFiles.keys}")
        return analyze(ktFile) { block(ktFile) }
    }

    /**
     * Clean up test resources.
     */
    protected fun cleanup() {
        try {
            Disposer.dispose(disposable)
        } catch (e: Exception) {
            // Ignore disposal errors in tests
        }
        try {
            testProjectDir.toFile().deleteRecursively()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    private fun findKotlinStdlib(): String? {
        return System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .firstOrNull { it.contains("kotlin-stdlib") && it.endsWith(".jar") && !it.contains("jdk") }
    }
}
