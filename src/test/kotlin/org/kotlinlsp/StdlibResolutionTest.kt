// by Claude - Tests for stdlib type resolution via Analysis API
package org.kotlinlsp

import org.jetbrains.kotlin.psi.KtNamedFunction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests that stdlib types and functions resolve correctly when
 * source files are properly registered with the Analysis API session.
 */
class StdlibResolutionTest : AnalysisApiTestBase() {

    @BeforeEach
    fun setup() {
        setupProject(mapOf(
            "Test.kt" to """
                package test

                fun process(items: List<String>): Int {
                    return items.size
                }
            """.trimIndent()
        ))
    }

    @AfterEach
    fun teardown() {
        cleanup()
    }

    @Test
    fun `List type resolves from stdlib`() {
        val testFile = sourceFiles.values.first { it.name.endsWith("Test.kt") }
        analyzeFile(testFile.name) { ktFile ->
            val function = ktFile.declarations.firstOrNull { it is KtNamedFunction } as? KtNamedFunction
                ?: error("Expected function declaration")
            val param = function.valueParameters.first()

            // Get the type of the parameter
            val paramSymbol = param.symbol
            val paramType = paramSymbol.returnType

            // Verify it's List<String>
            val typeString = paramType.toString()
            assertTrue(
                typeString.contains("List"),
                "Parameter type should contain 'List', got: $typeString"
            )
        }
    }

    @Test
    fun `scope includes declarations`() {
        val testFile = sourceFiles.values.first { it.name.endsWith("Test.kt") }
        analyzeFile(testFile.name) { ktFile ->
            val function = ktFile.declarations.firstOrNull { it is KtNamedFunction } as? KtNamedFunction
                ?: error("Expected function declaration")
            val body = function.bodyBlockExpression ?: error("Expected body block")

            // Get scope inside the function body
            val scopeContext = ktFile.scopeContext(body)

            // Count symbols from scopes
            var symbolCount = 0
            for (scopeWithKind in scopeContext.scopes) {
                for (symbol in scopeWithKind.scope.declarations) {
                    symbolCount++
                }
            }

            // Should have symbols in scope (params, locals, imports, etc.)
            assertTrue(
                symbolCount > 0,
                "Should have symbols in scope, found: $symbolCount"
            )
        }
    }

    @Test
    fun `can resolve function return type`() {
        val testFile = sourceFiles.values.first { it.name.endsWith("Test.kt") }
        analyzeFile(testFile.name) { ktFile ->
            val function = ktFile.declarations.firstOrNull { it is KtNamedFunction } as? KtNamedFunction
                ?: error("Expected function declaration")

            val functionSymbol = function.symbol
            val returnType = functionSymbol.returnType

            val typeString = returnType.toString()
            assertTrue(
                typeString.contains("Int"),
                "Return type should be Int, got: $typeString"
            )
        }
    }
}
