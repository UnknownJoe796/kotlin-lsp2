// by Claude - Tests for diagnostics reporting via Analysis API
package org.kotlinlsp

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests that diagnostics (errors, warnings) are reported correctly when
 * source files are properly registered with the Analysis API session.
 */
class DiagnosticsIntegrationTest : AnalysisApiTestBase() {

    @BeforeEach
    fun setup() {
        setupProject(mapOf(
            "Errors.kt" to """
                package test

                fun typeMismatch(): Int {
                    return "not an int"
                }

                fun unresolvedReference() {
                    val x = unknownFunction()
                }

                fun unusedVariable() {
                    val unused = 42
                    println("hello")
                }
            """.trimIndent()
        ))
    }

    @AfterEach
    fun teardown() {
        cleanup()
    }

    @Test
    @OptIn(KaExperimentalApi::class)
    fun `reports type mismatch error`() {
        val errorsFile = sourceFiles.values.first { it.name.endsWith("Errors.kt") }
        analyzeFile(errorsFile.name) { ktFile ->
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)

            val messages = diagnostics.map { it.defaultMessage }

            // Type mismatch: returning String when Int is expected
            assertTrue(
                messages.any {
                    it.contains("type", ignoreCase = true) &&
                    (it.contains("mismatch", ignoreCase = true) || it.contains("expected", ignoreCase = true))
                },
                "Should report type mismatch error. Got: ${messages.take(5)}"
            )
        }
    }

    @Test
    @OptIn(KaExperimentalApi::class)
    fun `reports unresolved reference`() {
        val errorsFile = sourceFiles.values.first { it.name.endsWith("Errors.kt") }
        analyzeFile(errorsFile.name) { ktFile ->
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)

            val messages = diagnostics.map { it.defaultMessage }

            // Unresolved reference: unknownFunction
            assertTrue(
                messages.any {
                    it.contains("unresolved", ignoreCase = true) ||
                    it.contains("not found", ignoreCase = true)
                },
                "Should report unresolved reference. Got: ${messages.take(5)}"
            )
        }
    }

    @Test
    @OptIn(KaExperimentalApi::class)
    fun `collects multiple diagnostics`() {
        val errorsFile = sourceFiles.values.first { it.name.endsWith("Errors.kt") }
        analyzeFile(errorsFile.name) { ktFile ->
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)

            // Should have at least 2 errors (type mismatch + unresolved reference)
            assertTrue(
                diagnostics.size >= 2,
                "Should have at least 2 diagnostics, got: ${diagnostics.size}"
            )
        }
    }

    @Test
    @OptIn(KaExperimentalApi::class)
    fun `clean code has no errors`() {
        // Clean up existing project
        cleanup()

        // Setup with clean code
        setupProject(mapOf(
            "Clean.kt" to """
                package test

                fun greet(name: String): String {
                    return "Hello, ${'$'}name"
                }
            """.trimIndent()
        ))

        val cleanFile = sourceFiles.values.first { it.name.endsWith("Clean.kt") }
        analyzeFile(cleanFile.name) { ktFile ->
            val diagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)

            // Filter to only errors (not warnings)
            val errors = diagnostics.filter {
                it.severity == org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity.ERROR
            }

            assertTrue(
                errors.isEmpty(),
                "Clean code should have no errors, got: ${errors.map { it.defaultMessage }}"
            )
        }
    }
}
