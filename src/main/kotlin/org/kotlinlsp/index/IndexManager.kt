// by Claude
package org.kotlinlsp.index

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

/**
 * Manages the symbol index lifecycle: building, updating, and querying.
 * Scans Kotlin source files and extracts declarations for cross-file navigation.
 */
class IndexManager(
    private val psiFileProvider: (String, String) -> KtFile?
) {
    private val logger = LoggerFactory.getLogger(IndexManager::class.java)
    private val index = SymbolIndex()

    /**
     * Build index for all Kotlin files in the given source roots.
     */
    fun buildIndex(sourceRoots: List<Path>) {
        logger.info("Building symbol index from ${sourceRoots.size} source roots...")
        val startTime = System.currentTimeMillis()

        var fileCount = 0
        var symbolCount = 0

        sourceRoots.forEach { root ->
            if (Files.exists(root) && Files.isDirectory(root)) {
                val kotlinFiles = findKotlinFiles(root)
                kotlinFiles.forEach { file ->
                    val uri = file.toUri().toString()
                    val content = Files.readString(file)
                    val count = indexFile(uri, content)
                    fileCount++
                    symbolCount += count
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.info("Index built: $symbolCount symbols from $fileCount files in ${elapsed}ms")
    }

    /**
     * Find all Kotlin files in a directory recursively.
     */
    private fun findKotlinFiles(root: Path): List<Path> {
        return Files.walk(root)
            .filter { it.isRegularFile() && (it.extension == "kt" || it.extension == "kts") }
            .toList()
    }

    /**
     * Index a single file. Returns the number of symbols indexed.
     */
    fun indexFile(uri: String, content: String): Int {
        // Remove old symbols from this file
        index.removeFile(uri)

        // Parse the file
        val ktFile = psiFileProvider(uri, content) ?: run {
            logger.warn("Failed to parse file for indexing: $uri")
            return 0
        }

        // Extract symbols
        val symbols = extractSymbols(uri, ktFile)
        index.addSymbols(symbols)

        logger.debug("Indexed $uri: ${symbols.size} symbols")
        return symbols.size
    }

    /**
     * Re-index a file that has changed.
     */
    fun updateFile(uri: String, content: String) {
        indexFile(uri, content)
    }

    /**
     * Remove a file from the index.
     */
    fun removeFile(uri: String) {
        index.removeFile(uri)
        logger.debug("Removed from index: $uri")
    }

    /**
     * Extract symbols from a KtFile.
     */
    private fun extractSymbols(uri: String, ktFile: KtFile): List<SymbolLocation> {
        val symbols = mutableListOf<SymbolLocation>()
        val packageName = ktFile.packageFqName.asString().takeIf { it.isNotEmpty() }
        val fileText = ktFile.text

        ktFile.declarations.forEach { declaration ->
            extractFromDeclaration(declaration, uri, packageName, null, fileText, symbols)
        }

        return symbols
    }

    /**
     * Recursively extract symbols from a declaration.
     */
    private fun extractFromDeclaration(
        declaration: KtDeclaration,
        uri: String,
        packageName: String?,
        containerName: String?,
        fileText: String,
        symbols: MutableList<SymbolLocation>
    ) {
        when (declaration) {
            is KtClass -> {
                val name = declaration.name ?: return
                val kind = when {
                    declaration.isInterface() -> SymbolKind.INTERFACE
                    declaration.isEnum() -> SymbolKind.ENUM
                    else -> SymbolKind.CLASS
                }
                symbols.add(createSymbolLocation(uri, name, kind, declaration, packageName, containerName, fileText))

                // Index nested declarations
                val newContainerName = if (containerName != null) "$containerName.$name" else name
                declaration.declarations.forEach { nested ->
                    extractFromDeclaration(nested, uri, packageName, newContainerName, fileText, symbols)
                }

                // Index enum entries
                if (declaration.isEnum()) {
                    declaration.declarations.filterIsInstance<KtEnumEntry>().forEach { entry ->
                        entry.name?.let { entryName ->
                            symbols.add(createSymbolLocation(uri, entryName, SymbolKind.ENUM_ENTRY, entry, packageName, newContainerName, fileText))
                        }
                    }
                }

                // Index primary constructor
                declaration.primaryConstructor?.let { ctor ->
                    symbols.add(createSymbolLocation(uri, name, SymbolKind.CONSTRUCTOR, ctor, packageName, newContainerName, fileText))
                }
            }

            is KtObjectDeclaration -> {
                val name = declaration.name ?: return
                symbols.add(createSymbolLocation(uri, name, SymbolKind.OBJECT, declaration, packageName, containerName, fileText))

                // Index nested declarations
                val newContainerName = if (containerName != null) "$containerName.$name" else name
                declaration.declarations.forEach { nested ->
                    extractFromDeclaration(nested, uri, packageName, newContainerName, fileText, symbols)
                }
            }

            is KtNamedFunction -> {
                val name = declaration.name ?: return
                symbols.add(createSymbolLocation(uri, name, SymbolKind.FUNCTION, declaration, packageName, containerName, fileText))
            }

            is KtProperty -> {
                val name = declaration.name ?: return
                symbols.add(createSymbolLocation(uri, name, SymbolKind.PROPERTY, declaration, packageName, containerName, fileText))
            }

            is KtTypeAlias -> {
                val name = declaration.name ?: return
                symbols.add(createSymbolLocation(uri, name, SymbolKind.TYPE_ALIAS, declaration, packageName, containerName, fileText))
            }

            is KtSecondaryConstructor -> {
                containerName?.let { container ->
                    val name = container.substringAfterLast(".")
                    symbols.add(createSymbolLocation(uri, name, SymbolKind.CONSTRUCTOR, declaration, packageName, container, fileText))
                }
            }
        }
    }

    private fun createSymbolLocation(
        uri: String,
        name: String,
        kind: SymbolKind,
        element: KtElement,
        packageName: String?,
        containerName: String?,
        fileText: String
    ): SymbolLocation {
        val range = elementToRange(element, fileText)
        return SymbolLocation(
            uri = uri,
            name = name,
            kind = kind,
            range = range,
            containerName = containerName,
            packageName = packageName
        )
    }

    /**
     * Convert a PSI element's text range to an LSP Range.
     */
    private fun elementToRange(element: KtElement, text: String): Range {
        val startOffset = element.textRange.startOffset
        val endOffset = element.textRange.endOffset
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

    /**
     * Find symbols by simple name.
     */
    fun findByName(name: String): List<SymbolLocation> {
        return index.findByName(name)
    }

    /**
     * Find a symbol by qualified name.
     */
    fun findByQualifiedName(fqName: String): SymbolLocation? {
        return index.findByQualifiedName(fqName)
    }

    /**
     * Find symbols by name within a package.
     */
    fun findByNameInPackage(name: String, packageName: String): List<SymbolLocation> {
        return index.findByNameInPackage(name, packageName)
    }

    /**
     * Get the underlying index for direct access.
     */
    fun getIndex(): SymbolIndex = index

    /**
     * Get statistics about the index.
     */
    fun getStats(): IndexStats {
        return IndexStats(
            totalSymbols = index.size(),
            indexedFiles = index.getIndexedFiles().size
        )
    }
}

/**
 * Statistics about the index.
 */
data class IndexStats(
    val totalSymbols: Int,
    val indexedFiles: Int
)
