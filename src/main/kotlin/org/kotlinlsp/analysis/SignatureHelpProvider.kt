// by Claude - Signature Help for function call parameter hints
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides signature help (parameter hints) when typing function calls.
 */
class SignatureHelpProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(SignatureHelpProvider::class.java)

    fun getSignatureHelp(uri: String, position: Position): SignatureHelp? {
        logger.debug("Signature help requested at $uri:${position.line}:${position.character}")

        val ktFile = analysisSession.getKtFile(uri) ?: return null
        val text = ktFile.text
        val offset = positionToOffset(text, position)
        if (offset < 0) return null

        // Find the call expression we're inside
        val element = ktFile.findElementAt(offset) ?: return null
        val callInfo = findContainingCall(element, offset, text) ?: return null

        return analysisSession.withAnalysis(ktFile) {
            buildSignatureHelp(callInfo, ktFile)
        }
    }

    /**
     * Find the call expression containing the cursor and determine which argument we're in.
     */
    private fun findContainingCall(element: com.intellij.psi.PsiElement, offset: Int, text: String): CallInfo? {
        var current: com.intellij.psi.PsiElement? = element

        while (current != null) {
            when (current) {
                is KtCallExpression -> {
                    val valueArgumentList = current.valueArgumentList ?: return null
                    val leftParen = valueArgumentList.leftParenthesis?.textRange?.startOffset ?: return null
                    val rightParen = valueArgumentList.rightParenthesis?.textRange?.endOffset ?: Int.MAX_VALUE

                    // Check if cursor is inside the parentheses
                    if (offset > leftParen && offset <= rightParen) {
                        val activeParameter = computeActiveParameter(valueArgumentList, offset)
                        return CallInfo(current, activeParameter)
                    }
                }

                is KtSuperTypeCallEntry -> {
                    val valueArgumentList = current.valueArgumentList ?: return null
                    val leftParen = valueArgumentList.leftParenthesis?.textRange?.startOffset ?: return null
                    val rightParen = valueArgumentList.rightParenthesis?.textRange?.endOffset ?: Int.MAX_VALUE

                    if (offset > leftParen && offset <= rightParen) {
                        val activeParameter = computeActiveParameter(valueArgumentList, offset)
                        return CallInfo(current, activeParameter)
                    }
                }

                is KtConstructorDelegationCall -> {
                    val valueArgumentList = current.valueArgumentList ?: return null
                    val leftParen = valueArgumentList.leftParenthesis?.textRange?.startOffset ?: return null
                    val rightParen = valueArgumentList.rightParenthesis?.textRange?.endOffset ?: Int.MAX_VALUE

                    if (offset > leftParen && offset <= rightParen) {
                        val activeParameter = computeActiveParameter(valueArgumentList, offset)
                        return CallInfo(current, activeParameter)
                    }
                }
            }

            current = current.parent
        }

        return null
    }

    /**
     * Determine which parameter index the cursor is at.
     */
    private fun computeActiveParameter(argList: KtValueArgumentList, offset: Int): Int {
        val arguments = argList.arguments
        if (arguments.isEmpty()) return 0

        // Check for named arguments
        for ((index, arg) in arguments.withIndex()) {
            if (arg.isNamed()) {
                // If cursor is in a named argument, return its index
                if (offset >= arg.textRange.startOffset && offset <= arg.textRange.endOffset) {
                    return index
                }
            } else {
                // Positional argument
                if (offset <= arg.textRange.endOffset) {
                    return index
                }
            }
        }

        // Cursor is after all arguments
        return arguments.size
    }

    private fun KaSession.buildSignatureHelp(callInfo: CallInfo, ktFile: KtFile): SignatureHelp? {
        val signatures = mutableListOf<SignatureInformation>()

        when (val callElement = callInfo.callElement) {
            is KtCallExpression -> {
                val calleeExpr = callElement.calleeExpression ?: return null

                // Resolve the function being called
                val resolvedPsi = calleeExpr.references.mapNotNull { ref ->
                    try { ref.resolve() } catch (e: Exception) { null }
                }.firstOrNull()

                when (resolvedPsi) {
                    is KtNamedFunction -> {
                        val symbol = resolvedPsi.symbol
                        signatures.add(buildSignatureFromFunction(symbol, resolvedPsi))
                    }

                    is KtClass -> {
                        // Constructor call
                        val classSymbol = resolvedPsi.classSymbol
                        resolvedPsi.primaryConstructor?.let { ctor ->
                            signatures.add(buildSignatureFromConstructor(classSymbol, ctor))
                        }
                        // Also add secondary constructors
                        resolvedPsi.secondaryConstructors.forEach { ctor ->
                            signatures.add(buildSignatureFromSecondaryConstructor(classSymbol, ctor))
                        }
                    }

                    is KtPrimaryConstructor -> {
                        val classSymbol = (resolvedPsi.parent as? KtClass)?.classSymbol
                        if (classSymbol != null) {
                            signatures.add(buildSignatureFromConstructor(classSymbol, resolvedPsi))
                        }
                    }
                }

                // If direct resolution failed, try to resolve through Analysis API
                if (signatures.isEmpty()) {
                    resolveCallWithAnalysisApi(calleeExpr, signatures)
                }
            }

            is KtSuperTypeCallEntry -> {
                val typeRef = callElement.calleeExpression?.typeReference
                val typeName = typeRef?.text ?: return null

                // Find the referenced class
                val resolvedClass = typeRef.references.mapNotNull { ref ->
                    try { ref.resolve() } catch (e: Exception) { null }
                }.filterIsInstance<KtClass>().firstOrNull()

                resolvedClass?.primaryConstructor?.let { ctor ->
                    val classSymbol = resolvedClass.classSymbol
                    signatures.add(buildSignatureFromConstructor(classSymbol, ctor))
                }
            }

            is KtConstructorDelegationCall -> {
                // this() or super() call
                var parent = callElement.parent
                while (parent != null && parent !is KtClass) {
                    parent = parent.parent
                }
                val containingClass = parent as? KtClass ?: return null
                val classSymbol = containingClass.classSymbol

                if (callElement.isCallToThis) {
                    // Calling another constructor in the same class
                    containingClass.primaryConstructor?.let { ctor ->
                        signatures.add(buildSignatureFromConstructor(classSymbol, ctor))
                    }
                    containingClass.secondaryConstructors.forEach { ctor ->
                        signatures.add(buildSignatureFromSecondaryConstructor(classSymbol, ctor))
                    }
                } else {
                    // Calling superclass constructor
                    containingClass.getSuperTypeList()?.entries?.firstOrNull()?.let { superEntry ->
                        val superClass = superEntry.typeReference?.references?.mapNotNull { ref ->
                            try { ref.resolve() } catch (e: Exception) { null }
                        }?.filterIsInstance<KtClass>()?.firstOrNull()

                        superClass?.primaryConstructor?.let { ctor ->
                            val superClassSymbol = superClass.classSymbol
                            signatures.add(buildSignatureFromConstructor(superClassSymbol, ctor))
                        }
                    }
                }
            }
        }

        if (signatures.isEmpty()) return null

        return SignatureHelp().apply {
            this.signatures = signatures
            this.activeSignature = 0
            this.activeParameter = callInfo.activeParameter
        }
    }

    private fun KaSession.resolveCallWithAnalysisApi(
        calleeExpr: KtExpression,
        signatures: MutableList<SignatureInformation>
    ) {
        // Try to resolve via scope context - simplified approach
        // The full implementation would use scope.callables()
        // For now, just return without adding signatures if direct resolution failed
    }

    private fun KaSession.buildSignatureFromFunction(
        symbol: KaFunctionSymbol,
        function: KtNamedFunction
    ): SignatureInformation {
        val label = buildFunctionLabel(symbol)
        val params = symbol.valueParameters.map { param ->
            ParameterInformation("${param.name.asString()}: ${param.returnType}")
        }

        return SignatureInformation(label).apply {
            parameters = params
            function.docComment?.text?.let { doc ->
                documentation = org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(formatKDoc(doc))
            }
        }
    }

    private fun KaSession.buildSignatureFromSymbol(symbol: KaFunctionSymbol): SignatureInformation {
        val label = buildFunctionLabel(symbol)
        val params = symbol.valueParameters.map { param ->
            ParameterInformation("${param.name.asString()}: ${param.returnType}")
        }

        return SignatureInformation(label).apply {
            parameters = params
        }
    }

    private fun KaSession.buildSignatureFromConstructor(
        classSymbol: KaClassSymbol?,
        constructor: KtPrimaryConstructor
    ): SignatureInformation {
        val className = classSymbol?.name?.asString() ?: "Unknown"
        val params = constructor.valueParameters.map { param ->
            val prefix = when {
                param.hasValOrVar() && param.isMutable -> "var "
                param.hasValOrVar() -> "val "
                else -> ""
            }
            ParameterInformation("$prefix${param.name}: ${param.typeReference?.text ?: "Any"}")
        }

        val label = "$className(${params.joinToString(", ") { it.label.left }})"

        return SignatureInformation(label).apply {
            parameters = params
        }
    }

    private fun KaSession.buildSignatureFromSecondaryConstructor(
        classSymbol: KaClassSymbol?,
        constructor: KtSecondaryConstructor
    ): SignatureInformation {
        val className = classSymbol?.name?.asString() ?: "Unknown"
        val params = constructor.valueParameters.map { param ->
            ParameterInformation("${param.name}: ${param.typeReference?.text ?: "Any"}")
        }

        val label = "$className(${params.joinToString(", ") { it.label.left }})"

        return SignatureInformation(label).apply {
            parameters = params
        }
    }

    private fun KaSession.buildFunctionLabel(symbol: KaFunctionSymbol): String {
        return buildString {
            append(symbol.name?.asString() ?: "unknown")
            append("(")
            append(symbol.valueParameters.joinToString(", ") { param ->
                "${param.name.asString()}: ${param.returnType}"
            })
            append(")")
            append(": ${symbol.returnType}")
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

    private data class CallInfo(
        val callElement: KtElement,
        val activeParameter: Int
    )
}
