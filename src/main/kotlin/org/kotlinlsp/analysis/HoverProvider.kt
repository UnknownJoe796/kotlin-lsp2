// by Claude
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.types.KotlinType
import org.kotlinlsp.project.SessionManager
import org.slf4j.LoggerFactory

/**
 * Provides hover information using PSI analysis and BindingContext for type resolution.
 */
class HoverProvider(private val sessionManager: SessionManager) {

    private val logger = LoggerFactory.getLogger(HoverProvider::class.java)

    fun getHover(uri: String, position: Position): Hover? {
        logger.debug("Hover requested at $uri:${position.line}:${position.character}")

        val ktFile = sessionManager.getKtFile(uri) ?: return null

        // Convert position to offset
        val offset = positionToOffset(ktFile.text, position)
        if (offset < 0) return null

        // Find element at position
        val element = ktFile.findElementAt(offset) ?: return null

        // Get the binding context for type resolution
        val bindingContext = sessionManager.analyzeFile(uri)

        // Find the containing declaration or reference
        var current: com.intellij.psi.PsiElement? = element
        while (current != null) {
            val hover = getHoverForElement(current, bindingContext)
            if (hover != null) return hover
            current = current.parent
        }

        return null
    }

    private fun getHoverForElement(element: com.intellij.psi.PsiElement, bindingContext: BindingContext?): Hover? {
        val markdown = when (element) {
            is KtNamedFunction -> buildFunctionHover(element, bindingContext)
            is KtProperty -> buildPropertyHover(element, bindingContext)
            is KtClass -> buildClassHover(element)
            is KtObjectDeclaration -> buildObjectHover(element)
            is KtParameter -> buildParameterHover(element, bindingContext)
            is KtTypeReference -> buildTypeReferenceHover(element, bindingContext)
            is KtReferenceExpression -> buildReferenceHover(element, bindingContext)
            else -> null
        } ?: return null

        return Hover().apply {
            contents = Either.forRight(MarkupContent().apply {
                kind = MarkupKind.MARKDOWN
                value = markdown
            })
        }
    }

    private fun buildFunctionHover(function: KtNamedFunction, bindingContext: BindingContext?): String {
        // Try to get resolved descriptor for inferred types
        val descriptor = bindingContext?.get(BindingContext.FUNCTION, function)

        return buildString {
            append("```kotlin\n")
            function.modifierList?.text?.let { append("$it ") }
            append("fun ")
            function.name?.let { append(it) }

            // Type parameters
            function.typeParameterList?.text?.let { append(it) }

            // Parameters
            append("(")
            append(function.valueParameters.mapIndexed { index, param ->
                buildString {
                    param.modifierList?.text?.let { append("$it ") }
                    val paramType = descriptor?.valueParameters?.getOrNull(index)?.type?.toString()
                        ?: param.typeReference?.text
                        ?: "Any"
                    append("${param.name}: $paramType")
                    param.defaultValue?.let { append(" = ${it.text}") }
                }
            }.joinToString(", "))
            append(")")

            // Return type - use inferred type if explicit type not provided
            val returnType = function.typeReference?.text
                ?: descriptor?.returnType?.toString()
                ?: "Unit"
            append(": $returnType")
            append("\n```")

            // KDoc if available
            function.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }
        }
    }

    private fun buildPropertyHover(property: KtProperty, bindingContext: BindingContext?): String {
        // Try to get resolved descriptor for inferred types
        val descriptor = bindingContext?.get(BindingContext.VARIABLE, property)

        return buildString {
            append("```kotlin\n")
            property.modifierList?.text?.let { append("$it ") }
            append(if (property.isVar) "var " else "val ")
            property.name?.let { append(it) }

            // Use inferred type if explicit type not provided
            val type = property.typeReference?.text
                ?: descriptor?.type?.toString()
                ?: "(inferred)"
            append(": $type")
            append("\n```")

            property.docComment?.text?.let { doc ->
                append("\n\n---\n")
                append(formatKDoc(doc))
            }
        }
    }

    private fun buildClassHover(ktClass: KtClass): String {
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
        }
    }

    private fun buildObjectHover(obj: KtObjectDeclaration): String {
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

    private fun buildParameterHover(param: KtParameter, bindingContext: BindingContext?): String {
        val descriptor = bindingContext?.get(BindingContext.VALUE_PARAMETER, param)

        return buildString {
            append("```kotlin\n")
            param.valOrVarKeyword?.text?.let { append("$it ") }
            val type = param.typeReference?.text
                ?: descriptor?.type?.toString()
                ?: "Any"
            append("${param.name}: $type")
            param.defaultValue?.let { append(" = ${it.text}") }
            append("\n```")
        }
    }

    private fun buildTypeReferenceHover(typeRef: KtTypeReference, bindingContext: BindingContext?): String {
        val type = bindingContext?.get(BindingContext.TYPE, typeRef)

        return buildString {
            append("```kotlin\n")
            append("type: ${type?.toString() ?: typeRef.text}")
            append("\n```")
        }
    }

    private fun buildReferenceHover(ref: KtReferenceExpression, bindingContext: BindingContext?): String {
        // Try to get the resolved descriptor
        val target = bindingContext?.get(BindingContext.REFERENCE_TARGET, ref)
        val expressionType = bindingContext?.getType(ref)

        return buildString {
            append("```kotlin\n")
            when (target) {
                is FunctionDescriptor -> {
                    append("fun ${target.name}")
                    append("(")
                    append(target.valueParameters.joinToString(", ") { "${it.name}: ${it.type}" })
                    append("): ${target.returnType}")
                }
                is PropertyDescriptor -> {
                    append(if (target.isVar) "var " else "val ")
                    append("${target.name}: ${target.type}")
                }
                is VariableDescriptor -> {
                    append("val ${target.name}: ${target.type}")
                }
                is ClassDescriptor -> {
                    append("${target.kind.name.lowercase()} ${target.name}")
                }
                else -> {
                    append(ref.text)
                    if (expressionType != null) {
                        append(": $expressionType")
                    }
                }
            }
            append("\n```")

            // Add containing class info if available
            target?.containingDeclaration?.let { container ->
                if (container is ClassDescriptor) {
                    append("\n\n*Declared in: ${container.name}*")
                }
            }
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
