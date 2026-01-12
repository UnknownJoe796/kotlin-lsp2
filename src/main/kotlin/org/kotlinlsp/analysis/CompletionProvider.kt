// by Claude - Migrated to Kotlin Analysis API
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides code completion suggestions using Kotlin Analysis API.
 */
class CompletionProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(CompletionProvider::class.java)

    fun getCompletions(uri: String, position: Position): List<CompletionItem> {
        logger.debug("Completion requested at $uri:${position.line}:${position.character}")

        val completions = mutableListOf<CompletionItem>()

        // Always add Kotlin keywords
        completions.addAll(KOTLIN_KEYWORDS.map { createKeywordCompletion(it) })

        // Get KtFile and perform Analysis API-based completions
        val ktFile = analysisSession.getKtFile(uri)
        if (ktFile != null) {
            val analysisCompletions = getAnalysisApiCompletions(ktFile, position)
            completions.addAll(analysisCompletions)
        }

        return completions.distinctBy { it.label }
    }

    /**
     * Get completions using Analysis API with proper scope-based resolution.
     * Uses scopeContext to get all visible symbols at the cursor position,
     * including members from implicit receivers (extension functions, with/apply blocks).
     */
    @OptIn(KaExperimentalApi::class)
    private fun getAnalysisApiCompletions(ktFile: KtFile, position: Position): List<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()

        val offset = positionToOffset(ktFile.text, position)
        if (offset < 0) return completions

        // Find a KtElement at position for scope context
        val psiElement = ktFile.findElementAt(offset)
        val ktElement = psiElement?.let { findContainingKtElement(it) } ?: ktFile

        analysisSession.withAnalysis(ktFile) {
            try {
                // Use scopeContext to get all visible symbols at this position
                // This includes locals, parameters, class members, imports, stdlib, etc.
                val scopeContext = ktFile.scopeContext(ktElement)

                // Iterate through all scopes (innermost to outermost)
                for (scopeWithKind in scopeContext.scopes) {
                    // Get all declarations from this scope
                    scopeWithKind.scope.declarations.forEach { symbol ->
                        createCompletionFromSymbol(symbol)?.let { completions.add(it) }
                    }
                }

                // Add members from implicit receivers (extension functions, with/apply blocks)
                // This allows completions like `length` inside `fun String.myExt() { }`
                addImplicitReceiverCompletions(scopeContext, completions)

            } catch (e: Exception) {
                logger.warn("Error getting scope-based completions: ${e.message}", e)

                // Fallback: add file-level declarations if scope resolution fails
                addFileLevelDeclarations(ktFile, completions)
            }
        }

        return completions
    }

    /**
     * Add completions from implicit receivers (extension function receivers, with/apply receivers).
     */
    @OptIn(KaExperimentalApi::class)
    private fun KaSession.addImplicitReceiverCompletions(
        scopeContext: org.jetbrains.kotlin.analysis.api.components.KaScopeContext,
        completions: MutableList<CompletionItem>
    ) {
        for (implicitReceiver in scopeContext.implicitReceivers) {
            val receiverType = implicitReceiver.type
            val typeLabel = renderType(receiverType)

            try {
                // Get the class symbol for the receiver type to access its members
                val classSymbol = receiverType.expandedSymbol
                if (classSymbol != null) {
                    // Get member scope of the class
                    val memberScope = classSymbol.memberScope

                    // Add all declarations from the member scope
                    for (symbol in memberScope.declarations) {
                        createCompletionFromSymbol(symbol)?.let { item ->
                            // Mark as coming from implicit receiver for better UX
                            item.detail = "${item.detail ?: ""} (from $typeLabel)".trim()
                            completions.add(item)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.debug("Error getting implicit receiver completions for $typeLabel: ${e.message}")
            }
        }
    }

    /**
     * Find the containing KtElement for a PsiElement.
     */
    private fun findContainingKtElement(element: com.intellij.psi.PsiElement): KtElement {
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            if (current is KtElement) return current
            current = current.parent
        }
        return element as? KtElement ?: throw IllegalStateException("No KtElement found")
    }

    /**
     * Fallback: add file-level declarations when scope context fails.
     */
    private fun KaSession.addFileLevelDeclarations(ktFile: KtFile, completions: MutableList<CompletionItem>) {
        ktFile.declarations.forEach { declaration ->
            when (declaration) {
                is KtNamedFunction -> declaration.symbol.let { symbol ->
                    createCompletionFromSymbol(symbol)?.let { completions.add(it) }
                }
                is KtProperty -> declaration.symbol.let { symbol ->
                    createCompletionFromSymbol(symbol)?.let { completions.add(it) }
                }
                is KtClass -> {
                    declaration.classSymbol?.let { symbol ->
                        createCompletionFromSymbol(symbol)?.let { completions.add(it) }
                    }
                }
                is KtObjectDeclaration -> declaration.symbol.let { symbol ->
                    createCompletionFromSymbol(symbol)?.let { completions.add(it) }
                }
            }
        }

        // Add imports
        ktFile.importDirectives.forEach { import ->
            import.importedFqName?.shortName()?.asString()?.let { name ->
                completions.add(CompletionItem(name).apply {
                    kind = CompletionItemKind.Reference
                    detail = "import"
                })
            }
        }
    }

    /**
     * Create a completion item from a KaSymbol.
     */
    private fun KaSession.createCompletionFromSymbol(symbol: KaSymbol): CompletionItem? {
        val name = when (symbol) {
            is KaDeclarationSymbol -> symbol.name?.asString()
            else -> null
        } ?: return null

        // Skip special names
        if (name.startsWith("<") || name.isEmpty()) return null

        return CompletionItem(name).apply {
            when (symbol) {
                is KaFunctionSymbol -> {
                    kind = CompletionItemKind.Function
                    detail = buildFunctionSignature(symbol)
                    insertText = buildFunctionInsertText(symbol)
                    insertTextFormat = InsertTextFormat.Snippet
                }
                is KaPropertySymbol -> {
                    kind = if (symbol.isVal) CompletionItemKind.Constant else CompletionItemKind.Variable
                    detail = renderType(symbol.returnType)
                }
                is KaClassSymbol -> {
                    kind = when (symbol.classKind) {
                        KaClassKind.INTERFACE -> CompletionItemKind.Interface
                        KaClassKind.ENUM_CLASS -> CompletionItemKind.Enum
                        KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT -> CompletionItemKind.Module
                        KaClassKind.ANNOTATION_CLASS -> CompletionItemKind.Interface
                        else -> CompletionItemKind.Class
                    }
                    detail = symbol.classKind.name.lowercase().replace("_", " ")
                }
                is KaVariableSymbol -> {
                    kind = CompletionItemKind.Variable
                    detail = renderType(symbol.returnType)
                }
                is KaTypeAliasSymbol -> {
                    kind = CompletionItemKind.TypeParameter
                    detail = "typealias"
                }
                else -> {
                    kind = CompletionItemKind.Value
                }
            }
        }
    }

    /**
     * Build function signature for display.
     */
    private fun KaSession.buildFunctionSignature(function: KaFunctionSymbol): String {
        val params = function.valueParameters.joinToString(", ") { param ->
            "${param.name.asString()}: ${renderType(param.returnType)}"
        }
        val returnType = renderType(function.returnType)
        return "($params) -> $returnType"
    }

    /**
     * Build function insert text with snippet placeholders.
     */
    private fun KaSession.buildFunctionInsertText(function: KaFunctionSymbol): String {
        val name = function.name?.asString() ?: return ""
        val paramCount = function.valueParameters.size

        return if (paramCount == 0) {
            "$name()"
        } else {
            val params = function.valueParameters.mapIndexed { i, param ->
                "\${${i + 1}:${param.name.asString()}}"
            }.joinToString(", ")
            "$name($params)"
        }
    }

    /**
     * Render a type to string.
     */
    private fun KaSession.renderType(type: KaType): String {
        return type.toString()
    }

    private fun createKeywordCompletion(keyword: String): CompletionItem {
        return CompletionItem(keyword).apply {
            kind = CompletionItemKind.Keyword
            detail = "Kotlin keyword"
        }
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

    companion object {
        private val KOTLIN_KEYWORDS = listOf(
            "fun", "val", "var", "class", "interface", "object",
            "if", "else", "when", "for", "while", "do",
            "return", "break", "continue",
            "package", "import",
            "public", "private", "protected", "internal",
            "open", "final", "abstract", "sealed",
            "override", "suspend", "inline", "external",
            "data", "enum", "annotation", "companion",
            "null", "true", "false", "this", "super",
            "is", "as", "in", "out", "by", "where",
            "try", "catch", "finally", "throw",
            "typealias", "typeof",
            "expect", "actual"
        )
    }
}
