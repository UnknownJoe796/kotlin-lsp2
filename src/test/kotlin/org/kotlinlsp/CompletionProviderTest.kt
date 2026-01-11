// by Claude
package org.kotlinlsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kotlinlsp.analysis.CompletionProvider
import org.kotlinlsp.project.SessionManager
import kotlin.test.assertTrue

/**
 * Tests for CompletionProvider functionality.
 */
class CompletionProviderTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var completionProvider: CompletionProvider

    @BeforeEach
    fun setUp() {
        sessionManager = SessionManager()
        // Initialize with a dummy workspace
        sessionManager.initializeWorkspace("file:///tmp/test-workspace")
        completionProvider = CompletionProvider(sessionManager)
    }

    @AfterEach
    fun tearDown() {
        sessionManager.dispose()
    }

    @Test
    fun `returns kotlin keywords`() {
        val uri = "file:///test.kt"
        sessionManager.updateDocument(uri, "fun main() { }")

        val completions = completionProvider.getCompletions(uri, Position(0, 13))

        assertTrue(completions.any { it.label == "fun" }, "Should include 'fun' keyword")
        assertTrue(completions.any { it.label == "val" }, "Should include 'val' keyword")
        assertTrue(completions.any { it.label == "var" }, "Should include 'var' keyword")
        assertTrue(completions.any { it.label == "class" }, "Should include 'class' keyword")
    }

    @Test
    fun `returns file-level declarations`() {
        val uri = "file:///test.kt"
        val content = """
            fun myFunction(): Int = 42
            val myProperty = "hello"
            class MyClass
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        val completions = completionProvider.getCompletions(uri, Position(0, 0))

        assertTrue(completions.any { it.label == "myFunction" }, "Should include 'myFunction'")
        assertTrue(completions.any { it.label == "myProperty" }, "Should include 'myProperty'")
        assertTrue(completions.any { it.label == "MyClass" }, "Should include 'MyClass'")
    }

    @Test
    fun `returns class members`() {
        val uri = "file:///test.kt"
        val content = """
            class Example {
                fun memberFunction() {}
                val memberProperty = 1
            }
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        val completions = completionProvider.getCompletions(uri, Position(0, 0))

        assertTrue(completions.any { it.label == "memberFunction" }, "Should include 'memberFunction'")
        assertTrue(completions.any { it.label == "memberProperty" }, "Should include 'memberProperty'")
    }
}
