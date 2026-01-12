// by Claude - Find References provider
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Provides find references functionality.
 * Searches for all usages of a symbol across the workspace.
 */
class ReferencesProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(ReferencesProvider::class.java)

    fun getReferences(uri: String, position: Position, includeDeclaration: Boolean): List<Location> {
        logger.debug("References requested at $uri:${position.line}:${position.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()
        val text = ktFile.text
        val offset = positionToOffset(text, position)
        if (offset < 0) return emptyList()

        // Find element at position and get the symbol name
        val element = ktFile.findElementAt(offset) ?: return emptyList()
        val targetInfo = findTargetInfo(element, ktFile) ?: return emptyList()

        val results = mutableListOf<Location>()

        // Include declaration if requested
        if (includeDeclaration && targetInfo.declarationLocation != null) {
            results.add(targetInfo.declarationLocation)
        }

        // Search for references across all files
        searchReferences(targetInfo, results)

        logger.debug("Found ${results.size} references for '${targetInfo.name}'")
        return results
    }

    /**
     * Find the target symbol information from the element at cursor.
     */
    private fun findTargetInfo(element: com.intellij.psi.PsiElement, ktFile: KtFile): TargetInfo? {
        // Walk up to find a named element
        var current: com.intellij.psi.PsiElement? = element

        while (current != null) {
            when (current) {
                is KtNamedDeclaration -> {
                    val name = current.name ?: return null
                    val location = createLocation(ktFile, current)
                    return TargetInfo(
                        name = name,
                        kind = getDeclarationKind(current),
                        declarationLocation = location,
                        declarationFile = ktFile.virtualFile?.path
                    )
                }

                is KtNameReferenceExpression -> {
                    val name = current.getReferencedName()
                    // Try to resolve the reference to find the declaration
                    val resolvedPsi = current.references.mapNotNull { ref ->
                        try { ref.resolve() } catch (e: Exception) { null }
                    }.firstOrNull()

                    val declLocation = (resolvedPsi as? KtNamedDeclaration)?.let {
                        createLocationFromPsi(it)
                    }

                    return TargetInfo(
                        name = name,
                        kind = getKindFromResolvedPsi(resolvedPsi),
                        declarationLocation = declLocation,
                        declarationFile = resolvedPsi?.containingFile?.virtualFile?.path
                    )
                }

                is KtSimpleNameExpression -> {
                    val name = current.getReferencedName()
                    val resolvedPsi = current.references.mapNotNull { ref ->
                        try { ref.resolve() } catch (e: Exception) { null }
                    }.firstOrNull()

                    val declLocation = (resolvedPsi as? KtNamedDeclaration)?.let {
                        createLocationFromPsi(it)
                    }

                    return TargetInfo(
                        name = name,
                        kind = getKindFromResolvedPsi(resolvedPsi),
                        declarationLocation = declLocation,
                        declarationFile = resolvedPsi?.containingFile?.virtualFile?.path
                    )
                }
            }
            current = current.parent
        }

        return null
    }

    /**
     * Search for references to the target across all files.
     */
    private fun searchReferences(targetInfo: TargetInfo, results: MutableList<Location>) {
        val project = analysisSession.getKmpProject()

        if (project != null) {
            // Scan all modules' source roots
            for (module in project.modules) {
                for (sourceRoot in module.sourceRoots) {
                    scanSourceRootForReferences(sourceRoot, targetInfo, results)
                }
            }
        }
    }

    private fun scanSourceRootForReferences(
        sourceRoot: Path,
        targetInfo: TargetInfo,
        results: MutableList<Location>
    ) {
        val sourceDir = sourceRoot.toFile()
        if (!sourceDir.exists()) return

        sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                try {
                    scanFileForReferences(file, targetInfo, results)
                } catch (e: Exception) {
                    logger.debug("Error scanning file ${file.path}: ${e.message}")
                }
            }
    }

    private fun scanFileForReferences(
        file: File,
        targetInfo: TargetInfo,
        results: MutableList<Location>
    ) {
        val content = file.readText()

        // Quick filter: skip files that don't contain the name at all
        if (!content.contains(targetInfo.name)) {
            return
        }

        val uri = file.toURI().toString()
        analysisSession.updateDocument(uri, content)
        val ktFile = analysisSession.getKtFile(uri) ?: return

        // Walk the PSI tree looking for references
        findReferencesInFile(ktFile, targetInfo, uri, content, results)
    }

    private fun findReferencesInFile(
        ktFile: KtFile,
        targetInfo: TargetInfo,
        uri: String,
        text: String,
        results: MutableList<Location>
    ) {
        ktFile.accept(object : KtTreeVisitorVoid() {
            override fun visitReferenceExpression(expression: KtReferenceExpression) {
                super.visitReferenceExpression(expression)

                val refName = when (expression) {
                    is KtNameReferenceExpression -> expression.getReferencedName()
                    is KtSimpleNameExpression -> expression.getReferencedName()
                    else -> return
                }

                if (refName != targetInfo.name) return

                // Try to resolve to verify it's the same symbol
                val resolvedPsi = expression.references.mapNotNull { ref ->
                    try { ref.resolve() } catch (e: Exception) { null }
                }.firstOrNull()

                // Check if this reference points to our target
                val isMatch = when {
                    // If we have a declaration file, check if resolved PSI is in that file
                    targetInfo.declarationFile != null && resolvedPsi != null -> {
                        resolvedPsi.containingFile?.virtualFile?.path == targetInfo.declarationFile
                    }
                    // Otherwise just match by name and kind
                    else -> {
                        val resolvedKind = getKindFromResolvedPsi(resolvedPsi)
                        resolvedKind == targetInfo.kind || targetInfo.kind == DeclarationKind.UNKNOWN
                    }
                }

                if (isMatch) {
                    val range = Range(
                        offsetToPosition(expression.textRange.startOffset, text),
                        offsetToPosition(expression.textRange.endOffset, text)
                    )
                    results.add(Location(uri, range))
                }
            }
        })
    }

    private fun getDeclarationKind(decl: KtNamedDeclaration): DeclarationKind {
        return when (decl) {
            is KtClass -> DeclarationKind.CLASS
            is KtObjectDeclaration -> DeclarationKind.OBJECT
            is KtNamedFunction -> DeclarationKind.FUNCTION
            is KtProperty -> DeclarationKind.PROPERTY
            is KtParameter -> DeclarationKind.PARAMETER
            is KtTypeAlias -> DeclarationKind.TYPE_ALIAS
            else -> DeclarationKind.UNKNOWN
        }
    }

    private fun getKindFromResolvedPsi(psi: com.intellij.psi.PsiElement?): DeclarationKind {
        return when (psi) {
            is KtClass -> DeclarationKind.CLASS
            is KtObjectDeclaration -> DeclarationKind.OBJECT
            is KtNamedFunction -> DeclarationKind.FUNCTION
            is KtProperty -> DeclarationKind.PROPERTY
            is KtParameter -> DeclarationKind.PARAMETER
            is KtTypeAlias -> DeclarationKind.TYPE_ALIAS
            else -> DeclarationKind.UNKNOWN
        }
    }

    private fun createLocation(ktFile: KtFile, declaration: KtNamedDeclaration): Location {
        val text = ktFile.text
        val virtualFile = ktFile.virtualFile

        val fileUri = virtualFile?.url?.let {
            if (it.startsWith("file://")) it else "file://$it"
        } ?: "file://${ktFile.name}"

        val range = Range(
            offsetToPosition(declaration.textRange.startOffset, text),
            offsetToPosition(declaration.textRange.endOffset, text)
        )

        return Location(fileUri, range)
    }

    private fun createLocationFromPsi(decl: KtNamedDeclaration): Location? {
        val containingFile = decl.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val text = containingFile.text

        val fileUri = virtualFile.url.let {
            if (it.startsWith("file://")) it else "file://$it"
        }

        val range = Range(
            offsetToPosition(decl.textRange.startOffset, text),
            offsetToPosition(decl.textRange.endOffset, text)
        )

        return Location(fileUri, range)
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

    private data class TargetInfo(
        val name: String,
        val kind: DeclarationKind,
        val declarationLocation: Location?,
        val declarationFile: String?
    )

    private enum class DeclarationKind {
        CLASS, OBJECT, FUNCTION, PROPERTY, PARAMETER, TYPE_ALIAS, UNKNOWN
    }
}
