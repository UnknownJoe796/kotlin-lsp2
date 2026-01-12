// by Claude - Document Symbols for outline view
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides document symbols (outline) for Kotlin files.
 * Returns a hierarchical list of symbols representing the structure of the file.
 */
class DocumentSymbolProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(DocumentSymbolProvider::class.java)

    fun getDocumentSymbols(uri: String): List<DocumentSymbol> {
        logger.debug("Document symbols requested for: $uri")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()
        val text = ktFile.text

        val symbols = mutableListOf<DocumentSymbol>()

        // Process top-level declarations
        ktFile.declarations.forEach { decl ->
            buildSymbol(decl, text)?.let { symbols.add(it) }
        }

        return symbols
    }

    private fun buildSymbol(element: KtDeclaration, text: String): DocumentSymbol? {
        return when (element) {
            is KtClass -> buildClassSymbol(element, text)
            is KtObjectDeclaration -> buildObjectSymbol(element, text)
            is KtNamedFunction -> buildFunctionSymbol(element, text)
            is KtProperty -> buildPropertySymbol(element, text)
            is KtTypeAlias -> buildTypeAliasSymbol(element, text)
            else -> null
        }
    }

    private fun buildClassSymbol(ktClass: KtClass, text: String): DocumentSymbol? {
        val name = ktClass.name ?: return null
        val range = textRangeToRange(ktClass.textRange.startOffset, ktClass.textRange.endOffset, text)
        val selectionRange = ktClass.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val kind = when {
            ktClass.isInterface() -> SymbolKind.Interface
            ktClass.isEnum() -> SymbolKind.Enum
            else -> SymbolKind.Class
        }

        val symbol = DocumentSymbol(name, kind, range, selectionRange)
        symbol.detail = buildClassDetail(ktClass)

        // Add children
        val children = mutableListOf<DocumentSymbol>()

        // Primary constructor parameters (val/var)
        ktClass.primaryConstructor?.valueParameters?.forEach { param ->
            if (param.hasValOrVar()) {
                buildParameterPropertySymbol(param, text)?.let { children.add(it) }
            }
        }

        // Class body declarations
        ktClass.declarations.forEach { decl ->
            buildSymbol(decl, text)?.let { children.add(it) }
        }

        // Enum entries
        if (ktClass.isEnum()) {
            ktClass.declarations.filterIsInstance<KtEnumEntry>().forEach { entry ->
                buildEnumEntrySymbol(entry, text)?.let { children.add(it) }
            }
        }

        if (children.isNotEmpty()) {
            symbol.children = children
        }

        return symbol
    }

    private fun buildObjectSymbol(obj: KtObjectDeclaration, text: String): DocumentSymbol? {
        val name = obj.name ?: if (obj.isCompanion()) "Companion" else return null
        val range = textRangeToRange(obj.textRange.startOffset, obj.textRange.endOffset, text)
        val selectionRange = obj.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val symbol = DocumentSymbol(name, SymbolKind.Object, range, selectionRange)
        symbol.detail = if (obj.isCompanion()) "companion object" else "object"

        // Add children
        val children = mutableListOf<DocumentSymbol>()
        obj.declarations.forEach { decl ->
            buildSymbol(decl, text)?.let { children.add(it) }
        }

        if (children.isNotEmpty()) {
            symbol.children = children
        }

        return symbol
    }

    private fun buildFunctionSymbol(function: KtNamedFunction, text: String): DocumentSymbol? {
        val name = function.name ?: return null
        val range = textRangeToRange(function.textRange.startOffset, function.textRange.endOffset, text)
        val selectionRange = function.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val symbol = DocumentSymbol(name, SymbolKind.Function, range, selectionRange)
        symbol.detail = buildFunctionDetail(function)

        // Add nested declarations (local functions, classes)
        val children = mutableListOf<DocumentSymbol>()
        function.bodyBlockExpression?.statements?.forEach { stmt ->
            when (stmt) {
                is KtNamedFunction -> buildFunctionSymbol(stmt, text)?.let { children.add(it) }
                is KtClass -> buildClassSymbol(stmt, text)?.let { children.add(it) }
            }
        }

        if (children.isNotEmpty()) {
            symbol.children = children
        }

        return symbol
    }

    private fun buildPropertySymbol(property: KtProperty, text: String): DocumentSymbol? {
        val name = property.name ?: return null
        val range = textRangeToRange(property.textRange.startOffset, property.textRange.endOffset, text)
        val selectionRange = property.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val kind = if (property.isVar) SymbolKind.Variable else SymbolKind.Constant

        val symbol = DocumentSymbol(name, kind, range, selectionRange)
        symbol.detail = buildPropertyDetail(property)

        return symbol
    }

    private fun buildParameterPropertySymbol(param: KtParameter, text: String): DocumentSymbol? {
        val name = param.name ?: return null
        val range = textRangeToRange(param.textRange.startOffset, param.textRange.endOffset, text)
        val selectionRange = param.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val kind = if (param.isMutable) SymbolKind.Variable else SymbolKind.Constant

        val symbol = DocumentSymbol(name, kind, range, selectionRange)
        symbol.detail = param.typeReference?.text ?: ""

        return symbol
    }

    private fun buildTypeAliasSymbol(typeAlias: KtTypeAlias, text: String): DocumentSymbol? {
        val name = typeAlias.name ?: return null
        val range = textRangeToRange(typeAlias.textRange.startOffset, typeAlias.textRange.endOffset, text)
        val selectionRange = typeAlias.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val symbol = DocumentSymbol(name, SymbolKind.TypeParameter, range, selectionRange)
        symbol.detail = typeAlias.getTypeReference()?.text?.let { "= $it" } ?: ""

        return symbol
    }

    private fun buildEnumEntrySymbol(entry: KtEnumEntry, text: String): DocumentSymbol? {
        val name = entry.name ?: return null
        val range = textRangeToRange(entry.textRange.startOffset, entry.textRange.endOffset, text)
        val selectionRange = entry.nameIdentifier?.let {
            textRangeToRange(it.textRange.startOffset, it.textRange.endOffset, text)
        } ?: range

        val symbol = DocumentSymbol(name, SymbolKind.EnumMember, range, selectionRange)

        return symbol
    }

    private fun buildClassDetail(ktClass: KtClass): String {
        return buildString {
            when {
                ktClass.isInterface() -> append("interface")
                ktClass.isEnum() -> append("enum class")
                ktClass.isData() -> append("data class")
                ktClass.isSealed() -> append("sealed class")
                else -> append("class")
            }
            ktClass.typeParameterList?.text?.let { append(it) }
        }
    }

    private fun buildFunctionDetail(function: KtNamedFunction): String {
        return buildString {
            append("(")
            append(function.valueParameters.joinToString(", ") { param ->
                "${param.name ?: "_"}: ${param.typeReference?.text ?: "Any"}"
            })
            append(")")
            function.typeReference?.text?.let { append(": $it") }
        }
    }

    private fun buildPropertyDetail(property: KtProperty): String {
        return buildString {
            append(if (property.isVar) "var" else "val")
            property.typeReference?.text?.let { append(": $it") }
        }
    }

    private fun textRangeToRange(startOffset: Int, endOffset: Int, text: String): Range {
        return Range(
            offsetToPosition(startOffset, text),
            offsetToPosition(endOffset, text)
        )
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
