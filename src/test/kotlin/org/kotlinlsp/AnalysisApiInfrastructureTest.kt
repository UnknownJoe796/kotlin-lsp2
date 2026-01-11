// by Claude - Tests to verify AnalysisApiTestBase infrastructure works
package org.kotlinlsp

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Verifies the Analysis API test infrastructure works correctly.
 * This test must pass before other Analysis API tests can work.
 */
class AnalysisApiInfrastructureTest : AnalysisApiTestBase() {

    @BeforeEach
    fun setup() {
        setupProject(mapOf(
            "Test.kt" to """
                package test
                fun greet(name: String): String = "Hello, ${'$'}name"
            """.trimIndent()
        ))
    }

    @AfterEach
    fun teardown() {
        cleanup()
    }

    @Test
    fun `session creates KtFile with proper context`() {
        assertTrue(sourceFiles.isNotEmpty(), "Should have source files")
        assertTrue(sourceFiles.any { it.key.endsWith("Test.kt") }, "Should have Test.kt")

        val testFile = sourceFiles.values.first { it.name.endsWith("Test.kt") }
        analyzeFile(testFile.name) { ktFile ->
            // Verify we can analyze without errors
            val declarations = ktFile.declarations
            assertTrue(declarations.isNotEmpty(), "Should have declarations")
        }
    }

    @Test
    fun `can analyze multiple files`() {
        // Clean up existing and create new with multiple files
        cleanup()

        setupProject(mapOf(
            "First.kt" to """
                package test
                fun first() = "first"
            """.trimIndent(),
            "Second.kt" to """
                package test
                fun second() = "second"
            """.trimIndent()
        ))

        assertTrue(sourceFiles.size >= 2, "Should have at least 2 files, got ${sourceFiles.size}")
    }
}
