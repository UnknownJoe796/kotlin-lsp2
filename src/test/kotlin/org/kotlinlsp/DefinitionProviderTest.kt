// by Claude
package org.kotlinlsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kotlinlsp.analysis.DefinitionProvider
import org.kotlinlsp.project.SessionManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for DefinitionProvider functionality.
 */
class DefinitionProviderTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var definitionProvider: DefinitionProvider

    @BeforeEach
    fun setUp() {
        sessionManager = SessionManager()
        // Initialize with a dummy workspace
        sessionManager.initializeWorkspace("file:///tmp/test-workspace")
        definitionProvider = DefinitionProvider(sessionManager)
    }

    @AfterEach
    fun tearDown() {
        sessionManager.dispose()
    }

    @Test
    fun `finds local function definition`() {
        val uri = "file:///test.kt"
        val content = """
            fun greet() = println("Hello")

            fun main() {
                greet()
            }
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Click on "greet" in the main function (line 3, column 4)
        val definitions = definitionProvider.getDefinition(uri, Position(3, 4))

        assertTrue(definitions.isNotEmpty(), "Should find at least one definition")
        assertEquals(uri, definitions[0].uri, "Definition should be in the same file")
        assertEquals(0, definitions[0].range.start.line, "Definition should be on line 0")
    }

    @Test
    fun `finds local property definition`() {
        val uri = "file:///test.kt"
        val content = """
            val message = "Hello"

            fun main() {
                println(message)
            }
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Click on "message" in the main function (line 3, column 12)
        val definitions = definitionProvider.getDefinition(uri, Position(3, 12))

        assertTrue(definitions.isNotEmpty(), "Should find at least one definition")
        assertEquals(uri, definitions[0].uri, "Definition should be in the same file")
        assertEquals(0, definitions[0].range.start.line, "Definition should be on line 0")
    }

    @Test
    fun `finds function parameter definition`() {
        val uri = "file:///test.kt"
        val content = """
            fun process(input: String) {
                println(input)
            }
        """.trimIndent()
        sessionManager.updateDocument(uri, content)

        // Click on "input" in println (line 1, column 12)
        val definitions = definitionProvider.getDefinition(uri, Position(1, 12))

        assertTrue(definitions.isNotEmpty(), "Should find at least one definition")
        assertEquals(uri, definitions[0].uri, "Definition should be in the same file")
        assertEquals(0, definitions[0].range.start.line, "Definition should be on the function line")
    }
}
