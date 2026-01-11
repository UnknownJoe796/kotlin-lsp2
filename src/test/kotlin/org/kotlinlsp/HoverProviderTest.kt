// by Claude
package org.kotlinlsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kotlinlsp.analysis.HoverProvider
import org.kotlinlsp.project.SessionManager
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for HoverProvider functionality.
 */
class HoverProviderTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var hoverProvider: HoverProvider

    @BeforeEach
    fun setUp() {
        sessionManager = SessionManager()
        // Initialize with a dummy workspace
        sessionManager.initializeWorkspace("file:///tmp/test-workspace")
        hoverProvider = HoverProvider(sessionManager)
    }

    @AfterEach
    fun tearDown() {
        sessionManager.dispose()
    }

    @Test
    fun `shows function signature on hover`() {
        val uri = "file:///test.kt"
        val content = """
            fun greet(name: String): String {
                return "Hello, ${'$'}name"
            }
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Hover over the function name
        val hover = hoverProvider.getHover(uri, Position(0, 4))

        assertNotNull(hover, "Hover should not be null")
        val hoverText = hover.contents.right.value
        assertTrue(hoverText.contains("fun"), "Hover should contain 'fun' keyword")
        assertTrue(hoverText.contains("greet"), "Hover should contain function name")
        assertTrue(hoverText.contains("name: String"), "Hover should contain parameter")
    }

    @Test
    fun `shows property on hover`() {
        val uri = "file:///test.kt"
        val content = """
            val message: String = "Hello"
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Hover over the property name
        val hover = hoverProvider.getHover(uri, Position(0, 4))

        assertNotNull(hover, "Hover should not be null")
        val hoverText = hover.contents.right.value
        assertTrue(hoverText.contains("val"), "Hover should contain 'val' keyword")
        assertTrue(hoverText.contains("message"), "Hover should contain property name")
    }

    @Test
    fun `shows class declaration on hover`() {
        val uri = "file:///test.kt"
        val content = """
            data class User(val name: String, val age: Int)
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Hover over the class name
        val hover = hoverProvider.getHover(uri, Position(0, 11))

        assertNotNull(hover, "Hover should not be null")
        val hoverText = hover.contents.right.value
        assertTrue(hoverText.contains("data class") || hoverText.contains("class"), "Hover should contain class declaration")
        assertTrue(hoverText.contains("User"), "Hover should contain class name")
    }
}
