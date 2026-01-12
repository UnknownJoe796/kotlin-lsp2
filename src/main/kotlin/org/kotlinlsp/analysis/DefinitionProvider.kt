// by Claude - Migrated to Kotlin Analysis API
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides go-to-definition functionality using Kotlin Analysis API.
 */
class DefinitionProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(DefinitionProvider::class.java)

    fun getDefinition(uri: String, position: Position): List<Location> {
        logger.debug("Definition requested at $uri:${position.line}:${position.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()

        // Convert position to offset
        val offset = positionToOffset(ktFile.text, position)
        if (offset < 0) return emptyList()

        // Find element at position
        val element = ktFile.findElementAt(offset) ?: return emptyList()

        // Check if we're on an expect/actual declaration
        val expectActualResult = findExpectActualDefinitions(element, ktFile, uri)
        if (expectActualResult.isNotEmpty()) {
            return expectActualResult
        }

        // Try to find a reference at this position
        val reference = findReference(element)
        if (reference != null) {
            // First try same-file lookup
            val declaration = findDeclaration(ktFile, reference)
            if (declaration != null) {
                return listOf(createLocation(uri, declaration))
            }

            // Try Analysis API resolution for cross-file
            val crossFileLocation = analysisSession.withAnalysis(ktFile) {
                resolveReference(element, ktFile)
            }
            if (crossFileLocation != null) {
                return listOf(crossFileLocation)
            }
        }

        return emptyList()
    }

    /**
     * Resolve a reference using Analysis API.
     */
    private fun KaSession.resolveReference(element: com.intellij.psi.PsiElement, ktFile: KtFile): Location? {
        // Find the reference expression
        var current = element
        while (current !is KtReferenceExpression && current.parent != null) {
            current = current.parent
        }

        if (current !is KtReferenceExpression) return null

        // Resolve the reference using references collection
        val references = current.references
        val resolvedPsi = references.mapNotNull { ref ->
            try {
                ref.resolve()
            } catch (e: Exception) {
                null
            }
        }.firstOrNull() ?: return null

        val containingFile = resolvedPsi.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null

        val fileUri = virtualFile.url.let {
            if (it.startsWith("file://")) it else "file://$it"
        }

        val textRange = resolvedPsi.textRange
        val text = containingFile.text

        return Location(fileUri, Range(
            offsetToPosition(textRange.startOffset, text),
            offsetToPosition(textRange.endOffset, text)
        ))
    }

    /**
     * Find expect/actual counterparts for declarations.
     */
    private fun findExpectActualDefinitions(element: com.intellij.psi.PsiElement, ktFile: KtFile, currentUri: String): List<Location> {
        // Find the containing declaration
        var current: com.intellij.psi.PsiElement? = element
        var declaration: KtNamedDeclaration? = null

        while (current != null) {
            if (current is KtNamedDeclaration) {
                declaration = current
                break
            }
            current = current.parent
        }

        if (declaration == null) return emptyList()

        val modifierOwner = declaration as? KtModifierListOwner ?: return emptyList()
        val name = declaration.name ?: return emptyList()

        // Check if this is an expect declaration - navigate to actuals
        if (modifierOwner.hasModifier(KtTokens.EXPECT_KEYWORD)) {
            logger.debug("On expect declaration: $name, finding actuals")
            return analysisSession.withAnalysis(ktFile) {
                findActualsForExpect(declaration)
            } ?: emptyList()
        }

        // Check if this is an actual declaration - navigate to expect
        if (modifierOwner.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
            logger.debug("On actual declaration: $name, finding expect")
            return analysisSession.withAnalysis(ktFile) {
                findExpectForActual(declaration)
            } ?: emptyList()
        }

        return emptyList()
    }

    /**
     * Find all actual implementations for an expect declaration.
     *
     * Uses the ExpectActualIndex for O(1) lookup instead of scanning all files.
     */
    @OptIn(KaExperimentalApi::class)
    private fun KaSession.findActualsForExpect(declaration: KtNamedDeclaration): List<Location> {
        val expectName = declaration.name ?: return emptyList()

        // Get the expect symbol to verify it's really an expect declaration
        val expectSymbol = when (declaration) {
            is KtNamedFunction -> declaration.symbol
            is KtProperty -> declaration.symbol
            is KtClass -> declaration.classSymbol
            else -> null
        } ?: return emptyList()

        if (!expectSymbol.isExpect) {
            return emptyList()
        }

        // Use the index for fast lookup
        val index = analysisSession.getExpectActualIndex()
        val actualEntries = index.getActualsFor(expectName)

        if (actualEntries.isEmpty()) {
            logger.debug("No actuals found in index for expect: $expectName")
            return emptyList()
        }

        val locations = mutableListOf<Location>()

        for (entry in actualEntries) {
            try {
                // Get the file content (may need to read from disk if not open)
                val content = analysisSession.getDocumentContent(entry.fileUri)
                    ?: java.io.File(java.net.URI(entry.fileUri)).readText()

                analysisSession.updateDocument(entry.fileUri, content)
                val ktFile = analysisSession.getKtFile(entry.fileUri) ?: continue

                // Find the actual declaration at the indexed offset
                val element = ktFile.findElementAt(entry.offset)
                val actualDecl = element?.let { findContainingDeclaration(it) } as? KtNamedDeclaration
                    ?: continue

                if (actualDecl.name == expectName && actualDecl.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
                    // Verify it matches our expect (by kind)
                    val matchesKind = when {
                        declaration is KtNamedFunction && actualDecl is KtNamedFunction -> true
                        declaration is KtProperty && actualDecl is KtProperty -> true
                        declaration is KtClass && actualDecl is KtClass -> true
                        else -> false
                    }

                    if (matchesKind) {
                        locations.add(createLocation(entry.fileUri, actualDecl))
                    }
                }
            } catch (e: Exception) {
                logger.debug("Error looking up actual from index: ${e.message}")
            }
        }

        logger.debug("Found ${locations.size} actual implementations for expect: $expectName (via index)")
        return locations
    }

    /**
     * Find the containing named declaration for an element.
     */
    private fun findContainingDeclaration(element: com.intellij.psi.PsiElement): KtNamedDeclaration? {
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            if (current is KtNamedDeclaration) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * Find an actual declaration in a file that matches the expect symbol.
     */
    @OptIn(KaExperimentalApi::class)
    private fun KaSession.findActualInFile(
        ktFile: KtFile,
        expectName: String,
        expectSymbol: KaDeclarationSymbol
    ): Location? {
        ktFile.declarations.forEach { decl ->
            if (decl is KtNamedDeclaration && decl.name == expectName) {
                if (decl.hasModifier(KtTokens.ACTUAL_KEYWORD)) {
                    val actualSymbol = when (decl) {
                        is KtNamedFunction -> decl.symbol
                        is KtProperty -> decl.symbol
                        is KtClass -> decl.classSymbol
                        else -> null
                    }

                    // Verify this actual corresponds to our expect
                    if (actualSymbol != null) {
                        val expects = actualSymbol.getExpectsForActual()
                        // Check if any of the expects match our expect symbol
                        // (Compare by name and type since symbols from different sessions aren't equal)
                        val matchesExpect = expects.any { exp ->
                            exp.name == expectSymbol.name
                        }

                        if (matchesExpect) {
                            val textRange = decl.textRange
                            val text = ktFile.text
                            val virtualFile = ktFile.virtualFile

                            val fileUri = virtualFile?.url?.let {
                                if (it.startsWith("file://")) it else "file://$it"
                            } ?: return@forEach

                            return Location(fileUri, Range(
                                offsetToPosition(textRange.startOffset, text),
                                offsetToPosition(textRange.endOffset, text)
                            ))
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Find the expect declaration for an actual implementation.
     * Uses getExpectsForActual() from the Analysis API.
     */
    @OptIn(KaExperimentalApi::class)
    private fun KaSession.findExpectForActual(declaration: KtNamedDeclaration): List<Location> {
        val symbol = when (declaration) {
            is KtNamedFunction -> declaration.symbol
            is KtProperty -> declaration.symbol
            is KtClass -> declaration.classSymbol
            else -> null
        } ?: return emptyList()

        // Use the Analysis API to get expect symbols for this actual
        val expectSymbols = try {
            symbol.getExpectsForActual()
        } catch (e: Exception) {
            logger.warn("Error getting expects for actual: ${e.message}")
            return emptyList()
        }

        return expectSymbols.mapNotNull { expectSymbol ->
            val psi = expectSymbol.psi ?: return@mapNotNull null
            val containingFile = psi.containingFile ?: return@mapNotNull null
            val virtualFile = containingFile.virtualFile ?: return@mapNotNull null

            val fileUri = virtualFile.url.let {
                if (it.startsWith("file://")) it else "file://$it"
            }

            val textRange = psi.textRange
            val text = containingFile.text

            Location(fileUri, Range(
                offsetToPosition(textRange.startOffset, text),
                offsetToPosition(textRange.endOffset, text)
            ))
        }.also {
            logger.debug("Found ${it.size} expect declarations for actual: ${declaration.name}")
        }
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
