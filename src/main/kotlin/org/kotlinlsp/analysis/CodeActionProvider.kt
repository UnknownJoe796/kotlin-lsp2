// by Claude - Code Actions (Quick Fixes) provider
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides code actions (quick fixes and refactorings).
 */
class CodeActionProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(CodeActionProvider::class.java)

    fun getCodeActions(uri: String, range: Range, context: CodeActionContext): List<Either<Command, CodeAction>> {
        logger.debug("Code actions requested for $uri at ${range.start.line}:${range.start.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()
        val text = ktFile.text

        val results = mutableListOf<Either<Command, CodeAction>>()

        // Generate fixes based on diagnostics
        context.diagnostics.forEach { diagnostic ->
            generateDiagnosticFixes(uri, diagnostic, ktFile, text, results)
        }

        // Generate contextual actions based on cursor position
        val startOffset = positionToOffset(text, range.start)
        if (startOffset >= 0) {
            val element = ktFile.findElementAt(startOffset)
            if (element != null) {
                generateContextualActions(uri, element, ktFile, text, results)
            }
        }

        return results
    }

    private fun generateDiagnosticFixes(
        uri: String,
        diagnostic: Diagnostic,
        ktFile: KtFile,
        text: String,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        val message = diagnostic.message ?: return

        when {
            // Unused import
            message.contains("Unused import directive") -> {
                results.add(createRemoveUnusedImportAction(uri, diagnostic))
            }

            // Unresolved reference - suggest imports
            message.contains("Unresolved reference") -> {
                val unresolvedName = extractUnresolvedName(message)
                if (unresolvedName != null) {
                    generateImportSuggestions(uri, unresolvedName, diagnostic, ktFile, results)
                }
            }

            // Missing return type
            message.contains("has implicit return type") -> {
                generateAddReturnTypeAction(uri, diagnostic, ktFile, text, results)
            }

            // Unnecessary safe call
            message.contains("Unnecessary safe call") -> {
                results.add(createRemoveSafeCallAction(uri, diagnostic))
            }

            // Redundant explicit type
            message.contains("Explicitly given type is redundant") -> {
                results.add(createRemoveExplicitTypeAction(uri, diagnostic, text))
            }
        }
    }

    private fun generateContextualActions(
        uri: String,
        element: com.intellij.psi.PsiElement,
        ktFile: KtFile,
        text: String,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        // Walk up to find meaningful context
        var current: com.intellij.psi.PsiElement? = element

        while (current != null) {
            when (current) {
                is KtProperty -> {
                    // Convert between val and var
                    if (current.isVar) {
                        results.add(createConvertToValAction(uri, current, text))
                    } else {
                        results.add(createConvertToVarAction(uri, current, text))
                    }

                    // Add explicit type if missing
                    if (current.typeReference == null && current.initializer != null) {
                        generateAddTypeAnnotationAction(uri, current, ktFile, text, results)
                    }
                    break
                }

                is KtNamedFunction -> {
                    // Add return type if missing
                    if (current.typeReference == null && !current.hasBlockBody()) {
                        generateAddReturnTypeForFunctionAction(uri, current, ktFile, text, results)
                    }

                    // Convert expression body to block body
                    if (!current.hasBlockBody() && current.bodyExpression != null) {
                        results.add(createConvertToBlockBodyAction(uri, current, text))
                    }

                    // Convert block body to expression body (if single return statement)
                    if (current.hasBlockBody()) {
                        val block = current.bodyBlockExpression
                        val statements = block?.statements
                        if (statements?.size == 1 && statements[0] is KtReturnExpression) {
                            results.add(createConvertToExpressionBodyAction(uri, current, text))
                        }
                    }
                    break
                }

                is KtIfExpression -> {
                    // Convert if-else to when
                    if (current.`else` != null) {
                        results.add(createConvertIfToWhenAction(uri, current, text))
                    }
                    break
                }

                is KtStringTemplateExpression -> {
                    // Convert string concatenation to template
                    break
                }

                is KtClass -> {
                    // Generate equals/hashCode
                    if (!current.isData() && !current.isInterface()) {
                        results.add(createGenerateEqualsHashCodeAction(uri, current))
                    }
                    break
                }
            }

            current = current.parent
        }
    }

    private fun createRemoveUnusedImportAction(uri: String, diagnostic: Diagnostic): Either<Command, CodeAction> {
        val edit = WorkspaceEdit()
        val textEdit = TextEdit(diagnostic.range, "")
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Remove unused import")
        action.kind = CodeActionKind.QuickFix
        action.edit = edit
        action.diagnostics = listOf(diagnostic)
        action.isPreferred = true

        return Either.forRight(action)
    }

    private fun extractUnresolvedName(message: String): String? {
        val regex = Regex("Unresolved reference[: ]*['\"]?([\\w.]+)['\"]?")
        return regex.find(message)?.groupValues?.getOrNull(1)
    }

    private fun generateImportSuggestions(
        uri: String,
        name: String,
        diagnostic: Diagnostic,
        ktFile: KtFile,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        // Find potential imports from known packages
        val suggestions = findPotentialImports(name, ktFile)

        suggestions.forEach { fqName ->
            val importStatement = "import $fqName\n"

            // Find the right position for the import
            val importList = ktFile.importList
            val insertPosition = if (importList != null && importList.imports.isNotEmpty()) {
                // After existing imports
                val lastImport = importList.imports.last()
                Position(
                    offsetToPosition(lastImport.textRange.endOffset, ktFile.text).line + 1,
                    0
                )
            } else if (ktFile.packageDirective != null) {
                // After package declaration
                Position(
                    offsetToPosition(ktFile.packageDirective!!.textRange.endOffset, ktFile.text).line + 1,
                    0
                )
            } else {
                Position(0, 0)
            }

            val edit = WorkspaceEdit()
            val textEdit = TextEdit(Range(insertPosition, insertPosition), importStatement)
            edit.changes = mapOf(uri to listOf(textEdit))

            val action = CodeAction("Import '$fqName'")
            action.kind = CodeActionKind.QuickFix
            action.edit = edit
            action.diagnostics = listOf(diagnostic)

            results.add(Either.forRight(action))
        }
    }

    private fun findPotentialImports(name: String, ktFile: KtFile): List<String> {
        val suggestions = mutableListOf<String>()

        // Common Kotlin stdlib packages
        val commonPackages = listOf(
            "kotlin",
            "kotlin.collections",
            "kotlin.io",
            "kotlin.text",
            "kotlin.sequences",
            "kotlin.ranges",
            "kotlinx.coroutines",
            "kotlinx.coroutines.flow",
            "java.util",
            "java.io"
        )

        // This is a simplified version - in a real implementation,
        // we would scan the classpath and workspace for matching symbols
        commonPackages.forEach { pkg ->
            suggestions.add("$pkg.$name")
        }

        return suggestions.take(5) // Limit suggestions
    }

    private fun generateAddReturnTypeAction(
        uri: String,
        diagnostic: Diagnostic,
        ktFile: KtFile,
        text: String,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        // Find the function at the diagnostic location
        val offset = positionToOffset(text, diagnostic.range.start)
        if (offset < 0) return

        val element = ktFile.findElementAt(offset)
        var current = element
        while (current != null && current !is KtNamedFunction) {
            current = current.parent
        }

        val function = current as? KtNamedFunction ?: return

        // Try to infer the type using Analysis API
        analysisSession.withAnalysis(ktFile) {
            val symbol = function.symbol
            val returnType = symbol.returnType.toString()

            if (returnType != "Unit" && returnType != "Nothing") {
                // Find where to insert the type (after parameter list, before body)
                val paramList = function.valueParameterList
                val insertOffset = paramList?.textRange?.endOffset ?: return@withAnalysis

                val insertPos = offsetToPosition(insertOffset, text)
                val edit = WorkspaceEdit()
                val textEdit = TextEdit(Range(insertPos, insertPos), ": $returnType")
                edit.changes = mapOf(uri to listOf(textEdit))

                val action = CodeAction("Add explicit return type '$returnType'")
                action.kind = CodeActionKind.QuickFix
                action.edit = edit
                action.diagnostics = listOf(diagnostic)

                results.add(Either.forRight(action))
            }
        }
    }

    private fun createRemoveSafeCallAction(uri: String, diagnostic: Diagnostic): Either<Command, CodeAction> {
        // Replace ?. with .
        val edit = WorkspaceEdit()
        val textEdit = TextEdit(diagnostic.range, ".")
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Remove unnecessary safe call")
        action.kind = CodeActionKind.QuickFix
        action.edit = edit
        action.diagnostics = listOf(diagnostic)

        return Either.forRight(action)
    }

    private fun createRemoveExplicitTypeAction(
        uri: String,
        diagnostic: Diagnostic,
        text: String
    ): Either<Command, CodeAction> {
        val action = CodeAction("Remove redundant explicit type")
        action.kind = CodeActionKind.QuickFix
        action.diagnostics = listOf(diagnostic)
        // Note: Full implementation would need to find and remove the type annotation

        return Either.forRight(action)
    }

    private fun createConvertToValAction(uri: String, property: KtProperty, text: String): Either<Command, CodeAction> {
        val varKeyword = property.valOrVarKeyword ?: return Either.forRight(CodeAction("Convert to val"))

        val edit = WorkspaceEdit()
        val range = Range(
            offsetToPosition(varKeyword.textRange.startOffset, text),
            offsetToPosition(varKeyword.textRange.endOffset, text)
        )
        val textEdit = TextEdit(range, "val")
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Convert to val")
        action.kind = CodeActionKind.RefactorRewrite
        action.edit = edit

        return Either.forRight(action)
    }

    private fun createConvertToVarAction(uri: String, property: KtProperty, text: String): Either<Command, CodeAction> {
        val valKeyword = property.valOrVarKeyword ?: return Either.forRight(CodeAction("Convert to var"))

        val edit = WorkspaceEdit()
        val range = Range(
            offsetToPosition(valKeyword.textRange.startOffset, text),
            offsetToPosition(valKeyword.textRange.endOffset, text)
        )
        val textEdit = TextEdit(range, "var")
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Convert to var")
        action.kind = CodeActionKind.RefactorRewrite
        action.edit = edit

        return Either.forRight(action)
    }

    private fun generateAddTypeAnnotationAction(
        uri: String,
        property: KtProperty,
        ktFile: KtFile,
        text: String,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        analysisSession.withAnalysis(ktFile) {
            val symbol = property.symbol
            val type = symbol.returnType.toString()

            if (type != "Nothing" && type != "ERROR") {
                val nameId = property.nameIdentifier ?: return@withAnalysis
                val insertPos = offsetToPosition(nameId.textRange.endOffset, text)

                val edit = WorkspaceEdit()
                val textEdit = TextEdit(Range(insertPos, insertPos), ": $type")
                edit.changes = mapOf(uri to listOf(textEdit))

                val action = CodeAction("Add explicit type '$type'")
                action.kind = CodeActionKind.RefactorRewrite
                action.edit = edit

                results.add(Either.forRight(action))
            }
        }
    }

    private fun generateAddReturnTypeForFunctionAction(
        uri: String,
        function: KtNamedFunction,
        ktFile: KtFile,
        text: String,
        results: MutableList<Either<Command, CodeAction>>
    ) {
        analysisSession.withAnalysis(ktFile) {
            val symbol = function.symbol
            val type = symbol.returnType.toString()

            if (type != "Unit" && type != "Nothing" && type != "ERROR") {
                val paramList = function.valueParameterList ?: return@withAnalysis
                val insertPos = offsetToPosition(paramList.textRange.endOffset, text)

                val edit = WorkspaceEdit()
                val textEdit = TextEdit(Range(insertPos, insertPos), ": $type")
                edit.changes = mapOf(uri to listOf(textEdit))

                val action = CodeAction("Add explicit return type '$type'")
                action.kind = CodeActionKind.RefactorRewrite
                action.edit = edit

                results.add(Either.forRight(action))
            }
        }
    }

    private fun createConvertToBlockBodyAction(
        uri: String,
        function: KtNamedFunction,
        text: String
    ): Either<Command, CodeAction> {
        val bodyExpr = function.bodyExpression ?: return Either.forRight(CodeAction("Convert to block body"))
        val equalsToken = function.equalsToken ?: return Either.forRight(CodeAction("Convert to block body"))

        val returnType = function.typeReference?.text
        val bodyText = bodyExpr.text

        val newBody = if (returnType == null || returnType == "Unit") {
            "{\n    $bodyText\n}"
        } else {
            "{\n    return $bodyText\n}"
        }

        val edit = WorkspaceEdit()
        val range = Range(
            offsetToPosition(equalsToken.textRange.startOffset, text),
            offsetToPosition(bodyExpr.textRange.endOffset, text)
        )
        val textEdit = TextEdit(range, newBody)
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Convert to block body")
        action.kind = CodeActionKind.RefactorRewrite
        action.edit = edit

        return Either.forRight(action)
    }

    private fun createConvertToExpressionBodyAction(
        uri: String,
        function: KtNamedFunction,
        text: String
    ): Either<Command, CodeAction> {
        val block = function.bodyBlockExpression ?: return Either.forRight(CodeAction("Convert to expression body"))
        val returnStmt = block.statements.firstOrNull() as? KtReturnExpression
            ?: return Either.forRight(CodeAction("Convert to expression body"))
        val returnedExpr = returnStmt.returnedExpression
            ?: return Either.forRight(CodeAction("Convert to expression body"))

        val newBody = "= ${returnedExpr.text}"

        val edit = WorkspaceEdit()
        val range = Range(
            offsetToPosition(block.textRange.startOffset, text),
            offsetToPosition(block.textRange.endOffset, text)
        )
        val textEdit = TextEdit(range, newBody)
        edit.changes = mapOf(uri to listOf(textEdit))

        val action = CodeAction("Convert to expression body")
        action.kind = CodeActionKind.RefactorRewrite
        action.edit = edit

        return Either.forRight(action)
    }

    private fun createConvertIfToWhenAction(
        uri: String,
        ifExpr: KtIfExpression,
        text: String
    ): Either<Command, CodeAction> {
        // This is a placeholder - full implementation would transform the if-else chain to when
        val action = CodeAction("Convert if-else to when")
        action.kind = CodeActionKind.RefactorRewrite
        action.disabled = CodeActionDisabled("Not yet implemented")

        return Either.forRight(action)
    }

    private fun createGenerateEqualsHashCodeAction(uri: String, klass: KtClass): Either<Command, CodeAction> {
        // This is a placeholder - full implementation would generate equals/hashCode methods
        val action = CodeAction("Generate equals() and hashCode()")
        action.kind = CodeActionKind.RefactorRewrite
        action.disabled = CodeActionDisabled("Not yet implemented")

        return Either.forRight(action)
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
