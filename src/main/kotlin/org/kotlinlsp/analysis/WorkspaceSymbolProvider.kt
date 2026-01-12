// by Claude - Workspace Symbols for global search
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Provides workspace-wide symbol search functionality.
 * Searches all Kotlin files in the project for matching symbols.
 */
class WorkspaceSymbolProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(WorkspaceSymbolProvider::class.java)

    fun getWorkspaceSymbols(query: String): List<SymbolInformation> {
        logger.debug("Workspace symbols requested for query: $query")

        val results = mutableListOf<SymbolInformation>()
        val project = analysisSession.getKmpProject()
        val lowerQuery = query.lowercase()

        if (project != null) {
            // Scan all modules' source roots
            for (module in project.modules) {
                for (sourceRoot in module.sourceRoots) {
                    scanSourceRoot(sourceRoot, lowerQuery, results)
                }
            }
        } else {
            // Fallback: scan common source directories
            val workspacePath = java.nio.file.Paths.get(
                java.net.URI(analysisSession.getKmpProject()?.rootPath?.toString() ?: return emptyList())
            )
            listOf("src/main/kotlin", "src/commonMain/kotlin", "src")
                .map { workspacePath.resolve(it) }
                .filter { java.nio.file.Files.exists(it) }
                .forEach { sourceRoot ->
                    scanSourceRoot(sourceRoot, lowerQuery, results)
                }
        }

        logger.debug("Found ${results.size} workspace symbols matching '$query'")
        return results.take(100) // Limit results
    }

    private fun scanSourceRoot(sourceRoot: Path, query: String, results: MutableList<SymbolInformation>) {
        val sourceDir = sourceRoot.toFile()
        if (!sourceDir.exists()) return

        sourceDir.walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                try {
                    scanFile(file, query, results)
                } catch (e: Exception) {
                    logger.debug("Error scanning file ${file.path}: ${e.message}")
                }
            }
    }

    private fun scanFile(file: File, query: String, results: MutableList<SymbolInformation>) {
        val content = file.readText()
        val uri = file.toURI().toString()

        // Quick filter: skip files that don't contain the query at all
        if (query.isNotEmpty() && !content.lowercase().contains(query)) {
            return
        }

        val ktFile = analysisSession.run {
            updateDocument(uri, content)
            getKtFile(uri)
        } ?: return

        // Scan declarations
        ktFile.declarations.forEach { decl ->
            scanDeclaration(decl, query, uri, content, results, null)
        }
    }

    private fun scanDeclaration(
        decl: KtDeclaration,
        query: String,
        uri: String,
        text: String,
        results: MutableList<SymbolInformation>,
        containerName: String?
    ) {
        when (decl) {
            is KtClass -> {
                val name = decl.name ?: return
                if (matchesQuery(name, query)) {
                    val kind = when {
                        decl.isInterface() -> SymbolKind.Interface
                        decl.isEnum() -> SymbolKind.Enum
                        else -> SymbolKind.Class
                    }
                    results.add(createSymbolInfo(name, kind, uri, decl, text, containerName))
                }

                // Scan nested declarations
                val newContainer = if (containerName != null) "$containerName.$name" else name
                decl.declarations.forEach { nested ->
                    scanDeclaration(nested, query, uri, text, results, newContainer)
                }
            }

            is KtObjectDeclaration -> {
                val name = decl.name ?: if (decl.isCompanion()) "Companion" else return
                if (matchesQuery(name, query)) {
                    results.add(createSymbolInfo(name, SymbolKind.Object, uri, decl, text, containerName))
                }

                // Scan nested declarations
                val newContainer = if (containerName != null) "$containerName.$name" else name
                decl.declarations.forEach { nested ->
                    scanDeclaration(nested, query, uri, text, results, newContainer)
                }
            }

            is KtNamedFunction -> {
                val name = decl.name ?: return
                if (matchesQuery(name, query)) {
                    val kind = if (containerName != null) SymbolKind.Method else SymbolKind.Function
                    results.add(createSymbolInfo(name, kind, uri, decl, text, containerName))
                }
            }

            is KtProperty -> {
                val name = decl.name ?: return
                if (matchesQuery(name, query)) {
                    val kind = if (decl.isVar) SymbolKind.Variable else SymbolKind.Constant
                    results.add(createSymbolInfo(name, kind, uri, decl, text, containerName))
                }
            }

            is KtTypeAlias -> {
                val name = decl.name ?: return
                if (matchesQuery(name, query)) {
                    results.add(createSymbolInfo(name, SymbolKind.TypeParameter, uri, decl, text, containerName))
                }
            }
        }
    }

    private fun matchesQuery(name: String, query: String): Boolean {
        if (query.isEmpty()) return true

        val lowerName = name.lowercase()
        val lowerQuery = query.lowercase()

        // Exact prefix match
        if (lowerName.startsWith(lowerQuery)) return true

        // Contains match
        if (lowerName.contains(lowerQuery)) return true

        // Fuzzy match (all query chars appear in order)
        var queryIndex = 0
        for (char in lowerName) {
            if (queryIndex < lowerQuery.length && char == lowerQuery[queryIndex]) {
                queryIndex++
            }
        }
        if (queryIndex == lowerQuery.length) return true

        // CamelCase match (query letters match capital letters)
        val capitals = name.filter { it.isUpperCase() }.lowercase()
        if (capitals.startsWith(lowerQuery)) return true

        return false
    }

    private fun createSymbolInfo(
        name: String,
        kind: SymbolKind,
        uri: String,
        element: KtDeclaration,
        text: String,
        containerName: String?
    ): SymbolInformation {
        val range = Range(
            offsetToPosition(element.textRange.startOffset, text),
            offsetToPosition(element.textRange.endOffset, text)
        )
        val location = Location(uri, range)

        @Suppress("DEPRECATION")
        return SymbolInformation(name, kind, location, containerName)
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
