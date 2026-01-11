// by Claude - Updated for Analysis API
package org.kotlinlsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.analysis.CompletionProvider
import kotlin.test.assertTrue

/**
 * Tests for CompletionProvider functionality.
 * Note: Full Analysis API tests require IntelliJ Platform setup.
 * These tests verify keyword completions which don't need full analysis.
 */
class CompletionProviderTest {

    @Test
    fun `returns kotlin keywords`() {
        // Create a minimal analysis session for testing
        val analysisSession = AnalysisSession()
        val completionProvider = CompletionProvider(analysisSession)

        // Keywords are returned even without a valid file
        val uri = "file:///test.kt"
        analysisSession.updateDocument(uri, "fun main() { }")

        try {
            val completions = completionProvider.getCompletions(uri, Position(0, 13))

            assertTrue(completions.any { it.label == "fun" }, "Should include 'fun' keyword")
            assertTrue(completions.any { it.label == "val" }, "Should include 'val' keyword")
            assertTrue(completions.any { it.label == "var" }, "Should include 'var' keyword")
            assertTrue(completions.any { it.label == "class" }, "Should include 'class' keyword")
        } finally {
            analysisSession.dispose()
        }
    }

    @Test
    fun `includes expect and actual keywords`() {
        val analysisSession = AnalysisSession()
        val completionProvider = CompletionProvider(analysisSession)

        val uri = "file:///test.kt"
        analysisSession.updateDocument(uri, "")

        try {
            val completions = completionProvider.getCompletions(uri, Position(0, 0))

            assertTrue(completions.any { it.label == "expect" }, "Should include 'expect' keyword")
            assertTrue(completions.any { it.label == "actual" }, "Should include 'actual' keyword")
        } finally {
            analysisSession.dispose()
        }
    }
}
