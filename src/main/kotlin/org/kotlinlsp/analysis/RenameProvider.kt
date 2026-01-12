// by Claude - Rename Refactoring provider
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.*
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Provides rename refactoring functionality.
 * Renames a symbol and all its references across the workspace.
 */
class RenameProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(RenameProvider::class.java)

    /**
     * Prepare rename - validates that rename is possible and returns the current name.
     */
    fun prepareRename(uri: String, position: Position): PrepareRenameResult? {
        logger.debug("Prepare rename at $uri:${position.line}:${position.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return null
        val text = ktFile.text
        val offset = positionToOffset(text, position)
        if (offset < 0) return null

        val element = ktFile.findElementAt(offset) ?: return null
        val targetInfo = findRenameTarget(element) ?: return null

        val range = Range(
            offsetToPosition(targetInfo.nameRange.first, text),
            offsetToPosition(targetInfo.nameRange.second, text)
        )

        return PrepareRenameResult(range, targetInfo.name)
    }

    /**
     * Perform the rename operation.
     */
    fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        logger.debug("Rename to '$newName' at $uri:${position.line}:${position.character}")

        // Validate new name
        if (!isValidIdentifier(newName)) {
            logger.warn("Invalid identifier: $newName")
            return null
        }

        val ktFile = analysisSession.getKtFile(uri) ?: return null
        val text = ktFile.text
        val offset = positionToOffset(text, position)
        if (offset < 0) return null

        val element = ktFile.findElementAt(offset) ?: return null
        val targetInfo = findRenameTarget(element) ?: return null

        // Collect all locations to rename
        val edits = mutableMapOf<String, MutableList<TextEdit>>()

        // Add the declaration rename
        val declRange = Range(
            offsetToPosition(targetInfo.nameRange.first, text),
            offsetToPosition(targetInfo.nameRange.second, text)
        )
        edits.getOrPut(uri) { mutableListOf() }.add(TextEdit(declRange, newName))

        // Find and rename all references
        val project = analysisSession.getKmpProject()
        if (project != null) {
            for (module in project.modules) {
                for (sourceRoot in module.sourceRoots) {
                    scanSourceRootForReferences(sourceRoot, targetInfo, newName, edits)
                }
            }
        }

        // Sort edits in reverse order by position (so we don't mess up offsets)
        edits.forEach { (_, editList) ->
            editList.sortWith(compareByDescending<TextEdit> { it.range.start.line }
                .thenByDescending { it.range.start.character })
        }

        return WorkspaceEdit(edits)
    }

    private fun findRenameTarget(element: com.intellij.psi.PsiElement): RenameTargetInfo? {
        var current: com.intellij.psi.PsiElement? = element

        while (current != null) {
            when (current) {
                is KtNamedDeclaration -> {
                    val nameId = current.nameIdentifier ?: return null
                    val name = current.name ?: return null

                    return RenameTargetInfo(
                        name = name,
                        kind = getDeclarationKind(current),
                        nameRange = nameId.textRange.startOffset to nameId.textRange.endOffset,
                        declarationFile = current.containingFile?.virtualFile?.path
                    )
                }

                is KtNameReferenceExpression -> {
                    val name = current.getReferencedName()

                    // Try to resolve to find the declaration
                    val resolvedPsi = current.references.mapNotNull { ref ->
                        try { ref.resolve() } catch (e: Exception) { null }
                    }.filterIsInstance<KtNamedDeclaration>().firstOrNull()

                    return RenameTargetInfo(
                        name = name,
                        kind = getDeclarationKind(resolvedPsi),
                        nameRange = current.textRange.startOffset to current.textRange.endOffset,
                        declarationFile = resolvedPsi?.containingFile?.virtualFile?.path
                    )
                }

                is KtSimpleNameExpression -> {
                    val name = current.getReferencedName()

                    val resolvedPsi = current.references.mapNotNull { ref ->
                        try { ref.resolve() } catch (e: Exception) { null }
                    }.filterIsInstance<KtNamedDeclaration>().firstOrNull()

                    return RenameTargetInfo(
                        name = name,
                        kind = getDeclarationKind(resolvedPsi),
                        nameRange = current.textRange.startOffset to current.textRange.endOffset,
                        declarationFile = resolvedPsi?.containingFile?.virtualFile?.path
                    )
                }
            }

            current = current.parent
        }

        return null
    }

    private fun scanSourceRootForReferences(
        sourceRoot: Path,
        targetInfo: RenameTargetInfo,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val sourceDir = sourceRoot.toFile()
        if (!sourceDir.exists()) return

        sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                try {
                    scanFileForReferences(file, targetInfo, newName, edits)
                } catch (e: Exception) {
                    logger.debug("Error scanning file ${file.path}: ${e.message}")
                }
            }
    }

    private fun scanFileForReferences(
        file: File,
        targetInfo: RenameTargetInfo,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
    ) {
        val content = file.readText()

        // Quick filter: skip files that don't contain the name
        if (!content.contains(targetInfo.name)) {
            return
        }

        val uri = file.toURI().toString()
        analysisSession.updateDocument(uri, content)
        val ktFile = analysisSession.getKtFile(uri) ?: return

        findReferencesInFile(ktFile, targetInfo, uri, content, newName, edits)
    }

    private fun findReferencesInFile(
        ktFile: KtFile,
        targetInfo: RenameTargetInfo,
        uri: String,
        text: String,
        newName: String,
        edits: MutableMap<String, MutableList<TextEdit>>
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
                    targetInfo.declarationFile != null && resolvedPsi != null -> {
                        resolvedPsi.containingFile?.virtualFile?.path == targetInfo.declarationFile
                    }
                    else -> {
                        val resolvedKind = getDeclarationKind(resolvedPsi as? KtNamedDeclaration)
                        resolvedKind == targetInfo.kind || targetInfo.kind == DeclarationKind.UNKNOWN
                    }
                }

                if (isMatch) {
                    val range = Range(
                        offsetToPosition(expression.textRange.startOffset, text),
                        offsetToPosition(expression.textRange.endOffset, text)
                    )
                    edits.getOrPut(uri) { mutableListOf() }.add(TextEdit(range, newName))
                }
            }

            // Also handle declarations (for same-file renames)
            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                super.visitNamedDeclaration(declaration)

                val name = declaration.name ?: return
                if (name != targetInfo.name) return

                val declFile = declaration.containingFile?.virtualFile?.path
                if (declFile == targetInfo.declarationFile) {
                    val nameId = declaration.nameIdentifier ?: return
                    val range = Range(
                        offsetToPosition(nameId.textRange.startOffset, text),
                        offsetToPosition(nameId.textRange.endOffset, text)
                    )
                    edits.getOrPut(uri) { mutableListOf() }.add(TextEdit(range, newName))
                }
            }
        })
    }

    private fun getDeclarationKind(decl: KtNamedDeclaration?): DeclarationKind {
        return when (decl) {
            is KtClass -> DeclarationKind.CLASS
            is KtObjectDeclaration -> DeclarationKind.OBJECT
            is KtNamedFunction -> DeclarationKind.FUNCTION
            is KtProperty -> DeclarationKind.PROPERTY
            is KtParameter -> DeclarationKind.PARAMETER
            is KtTypeAlias -> DeclarationKind.TYPE_ALIAS
            is KtTypeParameter -> DeclarationKind.TYPE_PARAMETER
            null -> DeclarationKind.UNKNOWN
            else -> DeclarationKind.UNKNOWN
        }
    }

    private fun isValidIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false

        // Check if it starts with a letter or underscore
        val first = name.first()
        if (!first.isLetter() && first != '_') {
            // Allow backtick-escaped identifiers
            if (name.startsWith('`') && name.endsWith('`') && name.length > 2) {
                return true
            }
            return false
        }

        // Check remaining characters
        return name.drop(1).all { it.isLetterOrDigit() || it == '_' }
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

    private data class RenameTargetInfo(
        val name: String,
        val kind: DeclarationKind,
        val nameRange: Pair<Int, Int>,
        val declarationFile: String?
    )

    private enum class DeclarationKind {
        CLASS, OBJECT, FUNCTION, PROPERTY, PARAMETER, TYPE_ALIAS, TYPE_PARAMETER, UNKNOWN
    }
}
