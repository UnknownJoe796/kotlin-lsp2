// by Claude
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.project.SessionManager
import org.slf4j.LoggerFactory

/**
 * Provides go-to-definition functionality using PSI analysis.
 *
 * Note: This is a limited implementation that only finds definitions
 * within the same file. Full cross-file resolution requires the Analysis API.
 */
class DefinitionProvider(private val sessionManager: SessionManager) {

    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)

    fun getDefinition(uri: String, position: Position): List<Location> {
        logger.debug("Definition requested at $uri:${position.line}:${position.character}")

        val ktFile = sessionManager.getKtFile(uri) ?: return emptyList()

        // Convert position to offset
        val offset = positionToOffset(ktFile.text, position)
        if (offset < 0) return emptyList()

        // Find element at position
        val element = ktFile.findElementAt(offset) ?: return emptyList()

        // Try to find a reference at this position
        val reference = findReference(element)
        if (reference != null) {
            // Try to find the declaration this reference points to
            val declaration = findDeclaration(ktFile, reference)
            if (declaration != null) {
                return listOf(createLocation(uri, declaration))
            }
        }

        return emptyList()
    }

    private fun findReference(element: com.intellij.psi.PsiElement): String? {
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            when (current) {
                is KtNameReferenceExpression -> return current.getReferencedName()
                is KtSimpleNameExpression -> return current.getReferencedName()
            }
            current = current.parent
        }
        return null
    }

    private fun findDeclaration(file: KtFile, name: String): KtNamedDeclaration? {
        // Search file-level declarations
        file.declarations.forEach { declaration ->
            val found = findDeclarationInElement(declaration, name)
            if (found != null) return found
        }
        return null
    }

    private fun findDeclarationInElement(element: KtDeclaration, name: String): KtNamedDeclaration? {
        // Check if this element matches
        if (element is KtNamedDeclaration && element.name == name) {
            return element
        }

        // Search in nested declarations
        when (element) {
            is KtClass -> {
                element.declarations.forEach { member ->
                    val found = findDeclarationInElement(member, name)
                    if (found != null) return found
                }
                // Check primary constructor parameters
                element.primaryConstructor?.valueParameters?.forEach { param ->
                    if (param.name == name) return param
                }
            }
            is KtObjectDeclaration -> {
                element.declarations.forEach { member ->
                    val found = findDeclarationInElement(member, name)
                    if (found != null) return found
                }
            }
            is KtNamedFunction -> {
                // Check function parameters
                element.valueParameters.forEach { param ->
                    if (param.name == name) return param
                }
            }
        }

        return null
    }

    private fun createLocation(uri: String, declaration: KtNamedDeclaration): Location {
        val textRange = declaration.textRange
        val text = declaration.containingFile.text

        return Location(uri, Range(
            offsetToPosition(textRange.startOffset, text),
            offsetToPosition(textRange.endOffset, text)
        ))
    }

    private fun positionToOffset(text: String, position: Position): Int {
        var line = 0
        var offset = 0

        for ((i, char) in text.withIndex()) {
            if (line == position.line) {
                val col = i - offset
                if (col >= position.character) {
                    return i
                }
            }
            if (char == '\n') {
                if (line == position.line) {
                    return i
                }
                line++
                offset = i + 1
            }
        }

        return if (line == position.line) text.length else -1
    }

    private fun offsetToPosition(offset: Int, text: String): Position {
        var line = 0
        var column = 0

        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') {
                line++
                column = 0
            } else {
                column++
            }
        }

        return Position(line, column)
    }
}
