// by Claude - Semantic Tokens for syntax highlighting
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides semantic tokens for enhanced syntax highlighting.
 * Tokens are returned in a delta-encoded format as per LSP specification.
 */
class SemanticTokensProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(SemanticTokensProvider::class.java)

    companion object {
        // Token types - these must match the legend
        val TOKEN_TYPES = listOf(
            "namespace",      // 0 - package names
            "type",           // 1 - types (classes, interfaces, type aliases)
            "class",          // 2 - class names
            "enum",           // 3 - enum names
            "interface",      // 4 - interface names
            "struct",         // 5 - data class
            "typeParameter",  // 6 - generic type parameters
            "parameter",      // 7 - function parameters
            "variable",       // 8 - local variables
            "property",       // 9 - properties
            "enumMember",     // 10 - enum entries
            "function",       // 11 - functions/methods
            "method",         // 12 - methods
            "keyword",        // 13 - keywords
            "modifier",       // 14 - modifiers (public, private, etc.)
            "comment",        // 15 - comments
            "string",         // 16 - string literals
            "number",         // 17 - numeric literals
            "operator",       // 18 - operators
            "decorator"       // 19 - annotations
        )

        // Token modifiers
        val TOKEN_MODIFIERS = listOf(
            "declaration",    // 0 - declaring the symbol
            "definition",     // 1 - defining the symbol
            "readonly",       // 2 - val vs var
            "static",         // 3 - companion object members
            "deprecated",     // 4 - @Deprecated
            "abstract",       // 5 - abstract members
            "async",          // 6 - suspend functions
            "modification",   // 7 - being modified
            "documentation",  // 8 - KDoc
            "defaultLibrary"  // 9 - stdlib types
        )

        fun getLegend(): SemanticTokensLegend {
            return SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS)
        }
    }

    fun getSemanticTokensFull(uri: String): SemanticTokens? {
        logger.debug("Semantic tokens requested for: $uri")

        val ktFile = analysisSession.getKtFile(uri) ?: return null
        val text = ktFile.text

        val tokens = mutableListOf<TokenData>()

        // Collect tokens from PSI tree
        collectTokens(ktFile, text, tokens)

        // Sort tokens by position (required for delta encoding)
        tokens.sortWith(compareBy({ it.line }, { it.column }))

        // Encode as delta format
        val encoded = encodeDelta(tokens)

        return SemanticTokens(encoded)
    }

    private fun collectTokens(ktFile: KtFile, text: String, tokens: MutableList<TokenData>) {
        // Use Analysis API for symbol resolution where possible
        analysisSession.withAnalysis(ktFile) {
            ktFile.accept(object : KtTreeVisitorVoid() {
                override fun visitPackageDirective(directive: KtPackageDirective) {
                    super.visitPackageDirective(directive)
                    directive.packageNames.forEach { name ->
                        addToken(name, TokenType.NAMESPACE, setOf(), text, tokens)
                    }
                }

                override fun visitImportDirective(directive: KtImportDirective) {
                    super.visitImportDirective(directive)
                    directive.importedFqName?.pathSegments()?.forEach { segment ->
                        // Import path segments
                    }
                }

                override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
                    super.visitAnnotationEntry(annotationEntry)
                    annotationEntry.calleeExpression?.let { callee ->
                        addToken(callee, TokenType.DECORATOR, setOf(TokenModifier.DECLARATION), text, tokens)
                    }
                }

                override fun visitClass(klass: KtClass) {
                    super.visitClass(klass)
                    klass.nameIdentifier?.let { nameId ->
                        val type = when {
                            klass.isInterface() -> TokenType.INTERFACE
                            klass.isEnum() -> TokenType.ENUM
                            klass.isData() -> TokenType.STRUCT
                            else -> TokenType.CLASS
                        }
                        val modifiers = mutableSetOf(TokenModifier.DECLARATION)
                        if (klass.hasModifier(KtTokens.ABSTRACT_KEYWORD)) modifiers.add(TokenModifier.ABSTRACT)
                        addToken(nameId, type, modifiers, text, tokens)
                    }
                }

                override fun visitObjectDeclaration(declaration: KtObjectDeclaration) {
                    super.visitObjectDeclaration(declaration)
                    declaration.nameIdentifier?.let { nameId ->
                        val modifiers = mutableSetOf(TokenModifier.DECLARATION, TokenModifier.STATIC)
                        addToken(nameId, TokenType.CLASS, modifiers, text, tokens)
                    }
                }

                override fun visitTypeParameter(parameter: KtTypeParameter) {
                    super.visitTypeParameter(parameter)
                    parameter.nameIdentifier?.let { nameId ->
                        addToken(nameId, TokenType.TYPE_PARAMETER, setOf(TokenModifier.DECLARATION), text, tokens)
                    }
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    super.visitNamedFunction(function)
                    function.nameIdentifier?.let { nameId ->
                        val isMethod = function.parent is KtClassBody
                        val type = if (isMethod) TokenType.METHOD else TokenType.FUNCTION
                        val modifiers = mutableSetOf(TokenModifier.DECLARATION)
                        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) modifiers.add(TokenModifier.ASYNC)
                        if (function.hasModifier(KtTokens.ABSTRACT_KEYWORD)) modifiers.add(TokenModifier.ABSTRACT)
                        addToken(nameId, type, modifiers, text, tokens)
                    }
                }

                override fun visitProperty(property: KtProperty) {
                    super.visitProperty(property)
                    property.nameIdentifier?.let { nameId ->
                        val modifiers = mutableSetOf(TokenModifier.DECLARATION)
                        if (!property.isVar) modifiers.add(TokenModifier.READONLY)
                        addToken(nameId, TokenType.PROPERTY, modifiers, text, tokens)
                    }
                }

                override fun visitParameter(parameter: KtParameter) {
                    super.visitParameter(parameter)
                    parameter.nameIdentifier?.let { nameId ->
                        val modifiers = mutableSetOf(TokenModifier.DECLARATION)
                        if (parameter.hasValOrVar() && !parameter.isMutable) modifiers.add(TokenModifier.READONLY)
                        val type = if (parameter.hasValOrVar()) TokenType.PROPERTY else TokenType.PARAMETER
                        addToken(nameId, type, modifiers, text, tokens)
                    }
                }

                override fun visitEnumEntry(enumEntry: KtEnumEntry) {
                    super.visitEnumEntry(enumEntry)
                    enumEntry.nameIdentifier?.let { nameId ->
                        addToken(nameId, TokenType.ENUM_MEMBER, setOf(TokenModifier.DECLARATION, TokenModifier.READONLY), text, tokens)
                    }
                }

                override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                    super.visitTypeAlias(typeAlias)
                    typeAlias.nameIdentifier?.let { nameId ->
                        addToken(nameId, TokenType.TYPE, setOf(TokenModifier.DECLARATION), text, tokens)
                    }
                }

                override fun visitReferenceExpression(expression: KtReferenceExpression) {
                    super.visitReferenceExpression(expression)

                    // Skip if this is part of a declaration we already handled
                    if (expression.parent is KtNamedDeclaration) return

                    when (expression) {
                        is KtNameReferenceExpression -> {
                            handleNameReference(expression, text, tokens)
                        }
                    }
                }

                override fun visitTypeReference(typeReference: KtTypeReference) {
                    super.visitTypeReference(typeReference)
                    // Type references are handled by visiting their user type
                }

                override fun visitUserType(userType: KtUserType) {
                    super.visitUserType(userType)
                    userType.referenceExpression?.let { ref ->
                        // Resolve to determine token type
                        val resolvedPsi = ref.references.mapNotNull { r ->
                            try { r.resolve() } catch (e: Exception) { null }
                        }.firstOrNull()

                        val type = when (resolvedPsi) {
                            is KtTypeParameter -> TokenType.TYPE_PARAMETER
                            is KtClass -> when {
                                resolvedPsi.isInterface() -> TokenType.INTERFACE
                                resolvedPsi.isEnum() -> TokenType.ENUM
                                else -> TokenType.CLASS
                            }
                            is KtTypeAlias -> TokenType.TYPE
                            else -> TokenType.TYPE
                        }

                        addToken(ref, type, setOf(), text, tokens)
                    }
                }
            })
        }
    }

    private fun KaSession.handleNameReference(
        expression: KtNameReferenceExpression,
        text: String,
        tokens: MutableList<TokenData>
    ) {
        // Try to resolve the reference
        val resolvedPsi = expression.references.mapNotNull { ref ->
            try { ref.resolve() } catch (e: Exception) { null }
        }.firstOrNull()

        val (type, modifiers) = when (resolvedPsi) {
            is KtNamedFunction -> {
                val isMethod = resolvedPsi.parent is KtClassBody
                val mods = mutableSetOf<TokenModifier>()
                if (resolvedPsi.hasModifier(KtTokens.SUSPEND_KEYWORD)) mods.add(TokenModifier.ASYNC)
                (if (isMethod) TokenType.METHOD else TokenType.FUNCTION) to mods
            }

            is KtProperty -> {
                val mods = mutableSetOf<TokenModifier>()
                if (!resolvedPsi.isVar) mods.add(TokenModifier.READONLY)
                TokenType.PROPERTY to mods
            }

            is KtParameter -> {
                val mods = mutableSetOf<TokenModifier>()
                if (resolvedPsi.hasValOrVar()) {
                    if (!resolvedPsi.isMutable) mods.add(TokenModifier.READONLY)
                    TokenType.PROPERTY to mods
                } else {
                    TokenType.PARAMETER to mods
                }
            }

            is KtClass -> {
                val type = when {
                    resolvedPsi.isInterface() -> TokenType.INTERFACE
                    resolvedPsi.isEnum() -> TokenType.ENUM
                    else -> TokenType.CLASS
                }
                type to setOf()
            }

            is KtObjectDeclaration -> {
                TokenType.CLASS to setOf(TokenModifier.STATIC)
            }

            is KtEnumEntry -> {
                TokenType.ENUM_MEMBER to setOf(TokenModifier.READONLY)
            }

            is KtTypeParameter -> {
                TokenType.TYPE_PARAMETER to setOf()
            }

            else -> {
                // Check if it's a local variable by looking at context
                val parent = expression.parent
                when {
                    parent is KtDotQualifiedExpression && parent.selectorExpression == expression -> {
                        // Could be a method call or property access
                        if (parent.parent is KtCallExpression || expression.parent?.parent is KtCallExpression) {
                            TokenType.METHOD to setOf()
                        } else {
                            TokenType.PROPERTY to setOf()
                        }
                    }
                    else -> TokenType.VARIABLE to setOf()
                }
            }
        }

        addToken(expression, type, modifiers, text, tokens)
    }

    private fun addToken(
        element: com.intellij.psi.PsiElement,
        type: TokenType,
        modifiers: Set<TokenModifier>,
        text: String,
        tokens: MutableList<TokenData>
    ) {
        val startOffset = element.textRange.startOffset
        val endOffset = element.textRange.endOffset
        val length = endOffset - startOffset

        if (length <= 0) return

        val pos = offsetToPosition(startOffset, text)
        val modifierBits = modifiers.fold(0) { acc, mod -> acc or (1 shl mod.ordinal) }

        tokens.add(TokenData(pos.line, pos.character, length, type.ordinal, modifierBits))
    }

    private fun encodeDelta(tokens: List<TokenData>): List<Int> {
        val result = mutableListOf<Int>()

        var prevLine = 0
        var prevChar = 0

        for (token in tokens) {
            val deltaLine = token.line - prevLine
            val deltaChar = if (deltaLine == 0) token.column - prevChar else token.column

            result.add(deltaLine)
            result.add(deltaChar)
            result.add(token.length)
            result.add(token.tokenType)
            result.add(token.tokenModifiers)

            prevLine = token.line
            prevChar = token.column
        }

        return result
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

    private data class TokenData(
        val line: Int,
        val column: Int,
        val length: Int,
        val tokenType: Int,
        val tokenModifiers: Int
    )

    private enum class TokenType {
        NAMESPACE,
        TYPE,
        CLASS,
        ENUM,
        INTERFACE,
        STRUCT,
        TYPE_PARAMETER,
        PARAMETER,
        VARIABLE,
        PROPERTY,
        ENUM_MEMBER,
        FUNCTION,
        METHOD,
        KEYWORD,
        MODIFIER,
        COMMENT,
        STRING,
        NUMBER,
        OPERATOR,
        DECORATOR
    }

    private enum class TokenModifier {
        DECLARATION,
        DEFINITION,
        READONLY,
        STATIC,
        DEPRECATED,
        ABSTRACT,
        ASYNC,
        MODIFICATION,
        DOCUMENTATION,
        DEFAULT_LIBRARY
    }
}
