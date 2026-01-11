// by Claude - Tests for cross-file symbol resolution via Analysis API
package org.kotlinlsp

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that cross-file symbol resolution works correctly when
 * multiple source files are registered in the same module.
 */
class CrossFileResolutionTest : AnalysisApiTestBase() {

    @BeforeEach
    fun setup() {
        setupProject(mapOf(
            "Utils.kt" to """
                package test

                fun helperFunction(): String = "helper"

                class Helper {
                    fun assist() = "assisting"
                }
            """.trimIndent(),
            "Main.kt" to """
                package test

                fun main() {
                    val result = helperFunction()
                    val helper = Helper()
                }
            """.trimIndent()
        ))
    }

    @AfterEach
    fun teardown() {
        cleanup()
    }

    @Test
    fun `resolves function from another file`() {
        val mainFile = sourceFiles.values.first { it.name.endsWith("Main.kt") }
        analyzeFile(mainFile.name) { ktFile ->
            // Find the call to helperFunction()
            val mainFn = ktFile.declarations.firstOrNull { it is KtNamedFunction } as? KtNamedFunction
                ?: error("Expected main function")
            val body = mainFn.bodyBlockExpression ?: error("Expected body block")

            // Find reference to helperFunction call
            val callExpr = body.findDescendantOfType<KtCallExpression>()
            assertNotNull(callExpr, "Should find a call expression")

            val calleeExpr = callExpr.calleeExpression as? KtNameReferenceExpression
            assertNotNull(calleeExpr, "Should find callee expression")

            // Try to resolve the reference
            val resolved = calleeExpr.references.firstNotNullOfOrNull { ref ->
                try { ref.resolve() } catch (e: Exception) { null }
            }

            assertNotNull(resolved, "Should resolve helperFunction reference")
            val containingFileName = resolved.containingFile?.name ?: ""
            assertTrue(
                containingFileName.endsWith("Utils.kt"),
                "Should resolve to Utils.kt, got: $containingFileName"
            )
        }
    }

    @Test
    fun `resolves class from another file`() {
        val mainFile = sourceFiles.values.first { it.name.endsWith("Main.kt") }
        analyzeFile(mainFile.name) { ktFile ->
            val mainFn = ktFile.declarations.firstOrNull { it is KtNamedFunction } as? KtNamedFunction
                ?: error("Expected main function")
            val body = mainFn.bodyBlockExpression ?: error("Expected body block")

            // Find reference to Helper class in "val helper = Helper()"
            val helperRef = body.findDescendantOfType<KtNameReferenceExpression> {
                it.getReferencedName() == "Helper"
            }
            assertNotNull(helperRef, "Should find Helper reference")

            val resolved = helperRef.references.firstNotNullOfOrNull { ref ->
                try { ref.resolve() } catch (e: Exception) { null }
            }

            assertNotNull(resolved, "Should resolve Helper class")
            assertTrue(resolved is KtClass, "Resolved element should be a class")
        }
    }

    @Test
    fun `utils file contains expected declarations`() {
        val utilsFile = sourceFiles.values.first { it.name.endsWith("Utils.kt") }
        analyzeFile(utilsFile.name) { ktFile ->
            val declarations = ktFile.declarations

            val helperFn = declarations.firstOrNull { it is KtNamedFunction && it.name == "helperFunction" }
            assertNotNull(helperFn, "Should have helperFunction")

            val helperClass = declarations.firstOrNull { it is KtClass && it.name == "Helper" }
            assertNotNull(helperClass, "Should have Helper class")
        }
    }
}
