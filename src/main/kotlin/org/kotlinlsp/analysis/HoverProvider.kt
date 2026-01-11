// by Claude - Migrated to Kotlin Analysis API
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.kotlinlsp.project.KmpPlatform
import org.slf4j.LoggerFactory

/**
 * Provides hover information using Kotlin Analysis API.
 */
class HoverProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)

    fun getHover(uri: String, position: Position): Hover? {
        logger.debug("Hover requested at $uri:${position.line}:${position.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return null

        // Convert position to offset
        val offset = positionToOffset(ktFile.text, position)
        if (offset < 0) return null

        // Find element at position
        val element = ktFile.findElementAt(offset) ?: return null

        // Find the containing declaration or reference
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            val hover = analysisSession.withAnalysis(ktFile) {
                getHoverForElement(current!!, uri)
            }
            if (hover != null) return hover
            current = current.parent
        }

        return null
    }

    private fun KaSession.getHoverForElement(element: com.intellij.psi.PsiElement, uri: String): Hover? {
        val markdown = when (element) {
            is KtNamedFunction -> buildFunctionHover(element, uri)
            is KtProperty -> buildPropertyHover(element)
            is KtClass -> buildClassHover(element, uri)
            is KtObjectDeclaration -> buildObjectHover(element)
            is KtParameter -> buildParameterHover(element)
            is KtTypeReference -> buildTypeReferenceHover(element)
            is KtReferenceExpression -> buildReferenceHover(element)
            else -> null
        } ?: return null

        return Hover().apply {
            contents = Either.forRight(MarkupContent().apply {
                kind = MarkupKind.MARKDOWN
                value = markdown
            })
        }
    }

    private fun KaSession.buildFunctionHover(function: KtNamedFunction, uri: String): String {
        val symbol = function.symbol

        return buildString {
            append("```kotlin\n")
            function.modifierList?.text?.let { append("$it ") }
            append("fun ")
            function.name?.let { append(it) }

            // Type parameters
            function.typeParameterList?.text?.let { append(it) }

            // Parameters with resolved types
            append("(")
            append(symbol.valueParameters.joinToString(", ") { param ->
                "${param.name.asString()}: ${param.returnType}"
            })
            append(")")

            // Return type
            append(": ${symbol.returnType}")
            append("\n```")

            // KDoc if available
            function.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }

            // Add expect/actual info
            function.name?.let { name ->
                appendExpectActualInfo(this, function, name, uri)
            }
        }
    }

    private fun KaSession.buildPropertyHover(property: KtProperty): String {
        val symbol = property.symbol

        return buildString {
            append("```kotlin\n")
            property.modifierList?.text?.let { append("$it ") }
            append(if (property.isVar) "var " else "val ")
            property.name?.let { append(it) }
            append(": ${symbol.returnType}")
            append("\n```")

            property.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }
        }
    }

    private fun KaSession.buildClassHover(ktClass: KtClass, uri: String): String {
        val symbol = ktClass.classSymbol

        return buildString {
            append("```kotlin\n")
            ktClass.modifierList?.text?.let { append("$it ") }

            when {
                ktClass.isInterface() -> append("interface ")
                ktClass.isEnum() -> append("enum class ")
                ktClass.isData() -> append("data class ")
                ktClass.isSealed() -> append("sealed class ")
                else -> append("class ")
            }

            ktClass.name?.let { append(it) }
            ktClass.typeParameterList?.text?.let { append(it) }

            // Primary constructor
            ktClass.primaryConstructor?.let { ctor ->
                append("(")
                append(ctor.valueParameters.joinToString(", ") { param ->
                    "${param.valOrVarKeyword?.text ?: ""} ${param.name}: ${param.typeReference?.text ?: "Any"}".trim()
                })
                append(")")
            }

            // Supertypes
            ktClass.getSuperTypeList()?.let { superTypes ->
                if (superTypes.entries.isNotEmpty()) {
                    append(" : ")
                    append(superTypes.entries.joinToString(", ") { it.text })
                }
            }

            append("\n```")

            ktClass.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }

            // Add expect/actual info
            ktClass.name?.let { name ->
                appendExpectActualInfo(this, ktClass, name, uri)
            }
        }
    }

    private fun KaSession.buildObjectHover(obj: KtObjectDeclaration): String {
        return buildString {
            append("```kotlin\n")
            obj.modifierList?.text?.let { append("$it ") }
            append(if (obj.isCompanion()) "companion object " else "object ")
            obj.name?.let { append(it) }
            append("\n```")

            obj.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }
        }
    }

    private fun KaSession.buildParameterHover(param: KtParameter): String {
        return buildString {
            append("```kotlin\n")
            param.valOrVarKeyword?.text?.let { append("$it ") }
            append("${param.name}: ${param.typeReference?.text ?: "Any"}")
            param.defaultValue?.let { append(" = ${it.text}") }
            append("\n```")
        }
    }

    private fun KaSession.buildTypeReferenceHover(typeRef: KtTypeReference): String {
        return buildString {
            append("```kotlin\n")
            append("type: ${typeRef.text}")
            append("\n```")
        }
    }

    private fun KaSession.buildReferenceHover(ref: KtReferenceExpression): String {
        // Try to resolve the reference using references collection
        val resolvedPsi = ref.references.mapNotNull { r ->
            try { r.resolve() } catch (e: Exception) { null }
        }.firstOrNull()

        // Get symbol from resolved PSI
        val symbol = when (resolvedPsi) {
            is KtNamedFunction -> resolvedPsi.symbol
            is KtProperty -> resolvedPsi.symbol
            is KtClass -> resolvedPsi.classSymbol
            is KtParameter -> resolvedPsi.symbol
            else -> null
        }

        return buildString {
            append("```kotlin\n")
            when (symbol) {
                is KaFunctionSymbol -> {
                    append("fun ${symbol.name}")
                    append("(")
                    append(symbol.valueParameters.joinToString(", ") {
                        "${it.name.asString()}: ${it.returnType}"
                    })
                    append("): ${symbol.returnType}")
                }
                is KaPropertySymbol -> {
                    append(if (symbol.isVal) "val " else "var ")
                    append("${symbol.name}: ${symbol.returnType}")
                }
                is KaVariableSymbol -> {
                    append("val ${symbol.name}: ${symbol.returnType}")
                }
                is KaClassSymbol -> {
                    append("${symbol.classKind.name.lowercase()} ${symbol.name}")
                }
                else -> {
                    append(ref.text)
                }
            }
            append("\n```")

            // Add containing class info if available
            symbol?.containingDeclaration?.let { container ->
                if (container is KaClassSymbol) {
                    append("\n\n*Declared in: ${container.name}*")
                }
            }
        }
    }

    /**
     * Append expect/actual information to hover.
     */
    private fun appendExpectActualInfo(builder: StringBuilder, element: KtModifierListOwner, name: String, uri: String) {
        val isExpect = element.hasModifier(KtTokens.EXPECT_KEYWORD)
        val isActual = element.hasModifier(KtTokens.ACTUAL_KEYWORD)

        if (!isExpect && !isActual) return

        builder.append("\n\n---\n")

        if (isExpect) {
            builder.append("**expect declaration**\n\n")
            builder.append("*Platform implementations available*")
        }

        if (isActual) {
            builder.append("**actual implementation**\n\n")
            val platform = analysisSession.getPlatformForUri(uri)
            builder.append("*Platform: ${formatPlatform(platform)}*")
        }
    }

    private fun formatPlatform(platform: KmpPlatform): String {
        return when (platform) {
            KmpPlatform.COMMON -> "common"
            KmpPlatform.JVM -> "JVM"
            KmpPlatform.JS -> "JS"
            KmpPlatform.NATIVE_IOS -> "iOS"
            KmpPlatform.NATIVE_MACOS -> "macOS"
            KmpPlatform.NATIVE_LINUX -> "Linux"
            KmpPlatform.NATIVE_WINDOWS -> "Windows"
            KmpPlatform.NATIVE_OTHER -> "Native"
        }
    }

    private fun formatKDoc(kdoc: String): String {
        return kdoc
            .removePrefix("/**")
            .removeSuffix("*/")
            .lines()
            .map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
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
}
