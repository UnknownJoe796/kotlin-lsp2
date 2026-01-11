// by Claude
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.HierarchicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.kotlinlsp.project.SessionManager
import org.slf4j.LoggerFactory

/**
 * Provides code completion suggestions using PSI analysis and BindingContext.
 */
class CompletionProvider(private val sessionManager: SessionManager) {

    private val logger = LoggerFactory.getLogger(CompletionProvider::class.java)

    fun getCompletions(uri: String, position: Position): List<CompletionItem> {
        logger.debug("Completion requested at $uri:${position.line}:${position.character}")

        val completions = mutableListOf<CompletionItem>()

        // Always add Kotlin keywords
        completions.addAll(KOTLIN_KEYWORDS.map { createKeywordCompletion(it) })

        // Try to get PSI-based completions
        val ktFile = sessionManager.getKtFile(uri)
        if (ktFile != null) {
            completions.addAll(getPsiCompletions(ktFile, position))

            // Try to get completions from BindingContext (includes stdlib)
            val bindingContext = sessionManager.analyzeFile(uri)
            if (bindingContext != null) {
                completions.addAll(getBindingContextCompletions(ktFile, position, bindingContext))
            }
        }

        return completions.distinctBy { it.label }
    }

    private fun getPsiCompletions(file: KtFile, position: Position): List<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()

        // Get file-level declarations
        file.declarations.forEach { declaration ->
            when (declaration) {
                is KtNamedFunction -> {
                    completions.add(createFunctionCompletion(declaration))
                }
                is KtProperty -> {
                    completions.add(createPropertyCompletion(declaration))
                }
                is KtClass -> {
                    completions.add(createClassCompletion(declaration))
                    // Add members from the class
                    declaration.declarations.forEach { member ->
                        when (member) {
                            is KtNamedFunction -> completions.add(createFunctionCompletion(member))
                            is KtProperty -> completions.add(createPropertyCompletion(member))
                        }
                    }
                }
                is KtObjectDeclaration -> {
                    declaration.name?.let { name ->
                        completions.add(CompletionItem(name).apply {
                            kind = CompletionItemKind.Module
                            detail = "object"
                        })
                    }
                }
            }
        }

        // Get imports for types
        file.importDirectives.forEach { import ->
            import.importedFqName?.shortName()?.asString()?.let { name ->
                completions.add(CompletionItem(name).apply {
                    kind = CompletionItemKind.Reference
                    detail = "import"
                })
            }
        }

        return completions
    }

    /**
     * Get completions from the BindingContext, which includes stdlib and imported symbols.
     */
    private fun getBindingContextCompletions(
        file: KtFile,
        position: Position,
        bindingContext: BindingContext
    ): List<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()

        try {
            // Get the lexical scope at the position
            val offset = positionToOffset(file.text, position)
            val element = file.findElementAt(offset)
            if (element == null) {
                logger.debug("No element found at position")
                return completions
            }

            // Find the containing scope
            var current: com.intellij.psi.PsiElement? = element
            while (current != null && current !is KtElement) {
                current = current.parent
            }

            val ktElement = current as? KtElement
            if (ktElement != null) {
                val scope = bindingContext[BindingContext.LEXICAL_SCOPE, ktElement]
                if (scope != null) {
                    // Collect all descriptors from the scope chain
                    val descriptors = collectDescriptorsFromScope(scope)
                    completions.addAll(descriptors.mapNotNull { createDescriptorCompletion(it) })
                    logger.debug("Found ${descriptors.size} descriptors in scope")
                }
            }

        } catch (e: Exception) {
            logger.warn("Error getting binding context completions: ${e.message}")
        }

        return completions
    }

    /**
     * Collect all descriptors from a scope and its parent scopes.
     */
    private fun collectDescriptorsFromScope(scope: LexicalScope): List<DeclarationDescriptor> {
        val result = mutableListOf<DeclarationDescriptor>()
        var currentScope: HierarchicalScope? = scope

        while (currentScope != null) {
            try {
                result.addAll(currentScope.getContributedDescriptors())
            } catch (e: Exception) {
                // Some scopes may not support this operation
            }
            currentScope = currentScope.parent
        }

        return result
    }

    /**
     * Create a completion item from a descriptor.
     */
    private fun createDescriptorCompletion(descriptor: DeclarationDescriptor): CompletionItem? {
        val name = descriptor.name.asString()
        if (name.startsWith("<") || name.isEmpty()) return null  // Skip special names

        return CompletionItem(name).apply {
            when (descriptor) {
                is FunctionDescriptor -> {
                    kind = CompletionItemKind.Function
                    detail = buildDescriptorFunctionSignature(descriptor)
                    insertText = buildDescriptorInsertText(descriptor)
                }
                is PropertyDescriptor -> {
                    kind = if (descriptor.isVar) CompletionItemKind.Variable else CompletionItemKind.Constant
                    detail = descriptor.type.toString()
                }
                is ClassDescriptor -> {
                    kind = when {
                        descriptor.kind == ClassKind.INTERFACE -> CompletionItemKind.Interface
                        descriptor.kind == ClassKind.ENUM_CLASS -> CompletionItemKind.Enum
                        descriptor.kind == ClassKind.OBJECT -> CompletionItemKind.Module
                        else -> CompletionItemKind.Class
                    }
                    detail = descriptor.kind.name.lowercase()
                }
                is VariableDescriptor -> {
                    kind = CompletionItemKind.Variable
                    detail = descriptor.type.toString()
                }
                else -> {
                    kind = CompletionItemKind.Value
                    detail = descriptor.javaClass.simpleName
                }
            }
        }
    }

    private fun buildDescriptorFunctionSignature(function: FunctionDescriptor): String {
        val params = function.valueParameters.joinToString(", ") { param ->
            "${param.name}: ${param.type}"
        }
        val returnType = function.returnType?.toString() ?: "Unit"
        return "($params) -> $returnType"
    }

    private fun buildDescriptorInsertText(function: FunctionDescriptor): String {
        val name = function.name.asString()
        val paramCount = function.valueParameters.size
        return if (paramCount == 0) {
            "$name()"
        } else {
            val params = function.valueParameters.mapIndexed { i, param ->
                "\${${i + 1}:${param.name}}"
            }.joinToString(", ")
            "$name($params)"
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

    private fun createFunctionCompletion(function: KtNamedFunction): CompletionItem {
        val name = function.name ?: return CompletionItem("<anonymous>")

        return CompletionItem(name).apply {
            kind = CompletionItemKind.Function
            detail = buildFunctionSignature(function)
            insertText = buildFunctionInsertText(function)
        }
    }

    private fun buildFunctionSignature(function: KtNamedFunction): String {
        val params = function.valueParameters.joinToString(", ") { param ->
            "${param.name}: ${param.typeReference?.text ?: "Any"}"
        }
        val returnType = function.typeReference?.text ?: "Unit"
        return "($params) -> $returnType"
    }

    private fun buildFunctionInsertText(function: KtNamedFunction): String {
        val name = function.name ?: return ""
        val paramCount = function.valueParameters.size
        return if (paramCount == 0) {
            "$name()"
        } else {
            val params = function.valueParameters.mapIndexed { i, param ->
                "\${${i + 1}:${param.name}}"
            }.joinToString(", ")
            "$name($params)"
        }
    }

    private fun createPropertyCompletion(property: KtProperty): CompletionItem {
        val name = property.name ?: return CompletionItem("<anonymous>")

        return CompletionItem(name).apply {
            kind = if (property.isVar) CompletionItemKind.Variable else CompletionItemKind.Constant
            detail = property.typeReference?.text ?: "inferred"
        }
    }

    private fun createClassCompletion(ktClass: KtClass): CompletionItem {
        val name = ktClass.name ?: return CompletionItem("<anonymous>")

        return CompletionItem(name).apply {
            kind = when {
                ktClass.isInterface() -> CompletionItemKind.Interface
                ktClass.isEnum() -> CompletionItemKind.Enum
                ktClass.isData() -> CompletionItemKind.Struct
                else -> CompletionItemKind.Class
            }
            detail = when {
                ktClass.isInterface() -> "interface"
                ktClass.isEnum() -> "enum class"
                ktClass.isData() -> "data class"
                else -> "class"
            }
        }
    }

    private fun createKeywordCompletion(keyword: String): CompletionItem {
        return CompletionItem(keyword).apply {
            kind = CompletionItemKind.Keyword
            detail = "Kotlin keyword"
        }
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
            "typealias", "typeof"
        )
    }
}
