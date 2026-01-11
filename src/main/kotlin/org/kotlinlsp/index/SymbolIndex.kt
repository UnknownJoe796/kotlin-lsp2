// by Claude
package org.kotlinlsp.index

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import java.util.concurrent.ConcurrentHashMap

/**
 * Symbol kinds that can be indexed.
 */
enum class SymbolKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    FUNCTION,
    PROPERTY,
    TYPE_ALIAS,
    CONSTRUCTOR,
    ENUM_ENTRY
}

/**
 * Represents the location of a symbol in the codebase.
 */
data class SymbolLocation(
    val uri: String,
    val name: String,
    val kind: SymbolKind,
    val range: Range,
    val containerName: String? = null,
    val packageName: String? = null
) {
    /**
     * Returns the fully qualified name if package is available.
     */
    val qualifiedName: String
        get() = when {
            packageName != null && containerName != null -> "$packageName.$containerName.$name"
            packageName != null -> "$packageName.$name"
            containerName != null -> "$containerName.$name"
            else -> name
        }
}

/**
 * Thread-safe symbol index for the project.
 * Maintains mappings from symbol names to their locations.
 */
class SymbolIndex {
    // Maps simple name -> list of locations
    private val symbolsByName = ConcurrentHashMap<String, MutableList<SymbolLocation>>()

    // Maps URI -> list of symbols in that file (for removal on file change)
    private val symbolsByFile = ConcurrentHashMap<String, MutableList<SymbolLocation>>()

    // Maps qualified name -> location (for exact lookups)
    private val symbolsByQualifiedName = ConcurrentHashMap<String, SymbolLocation>()

    /**
     * Add a symbol to the index.
     */
    fun addSymbol(symbol: SymbolLocation) {
        // Add to name index
        symbolsByName.computeIfAbsent(symbol.name) { mutableListOf() }.add(symbol)

        // Add to file index
        symbolsByFile.computeIfAbsent(symbol.uri) { mutableListOf() }.add(symbol)

        // Add to qualified name index
        symbolsByQualifiedName[symbol.qualifiedName] = symbol
    }

    /**
     * Add multiple symbols at once.
     */
    fun addSymbols(symbols: List<SymbolLocation>) {
        symbols.forEach { addSymbol(it) }
    }

    /**
     * Remove all symbols from a specific file.
     * Call this before re-indexing a file.
     */
    fun removeFile(uri: String) {
        val symbols = symbolsByFile.remove(uri) ?: return

        symbols.forEach { symbol ->
            // Remove from name index
            symbolsByName[symbol.name]?.remove(symbol)
            if (symbolsByName[symbol.name]?.isEmpty() == true) {
                symbolsByName.remove(symbol.name)
            }

            // Remove from qualified name index
            symbolsByQualifiedName.remove(symbol.qualifiedName)
        }
    }

    /**
     * Find all symbols with the given simple name.
     */
    fun findByName(name: String): List<SymbolLocation> {
        return symbolsByName[name]?.toList() ?: emptyList()
    }

    /**
     * Find a symbol by its fully qualified name.
     */
    fun findByQualifiedName(fqName: String): SymbolLocation? {
        return symbolsByQualifiedName[fqName]
    }

    /**
     * Find symbols that match a name within a specific package.
     */
    fun findByNameInPackage(name: String, packageName: String): List<SymbolLocation> {
        return findByName(name).filter { it.packageName == packageName }
    }

    /**
     * Get all symbols in a file.
     */
    fun getSymbolsInFile(uri: String): List<SymbolLocation> {
        return symbolsByFile[uri]?.toList() ?: emptyList()
    }

    /**
     * Get all indexed files.
     */
    fun getIndexedFiles(): Set<String> {
        return symbolsByFile.keys.toSet()
    }

    /**
     * Get total number of indexed symbols.
     */
    fun size(): Int {
        return symbolsByFile.values.sumOf { it.size }
    }

    /**
     * Clear the entire index.
     */
    fun clear() {
        symbolsByName.clear()
        symbolsByFile.clear()
        symbolsByQualifiedName.clear()
    }

    /**
     * Check if a file is indexed.
     */
    fun isFileIndexed(uri: String): Boolean {
        return symbolsByFile.containsKey(uri)
    }
}
