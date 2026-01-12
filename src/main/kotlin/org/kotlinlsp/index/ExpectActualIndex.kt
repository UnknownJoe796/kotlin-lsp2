// by Claude - Index for expect/actual declarations
package org.kotlinlsp.index

import org.slf4j.LoggerFactory

/**
 * Represents an expect or actual declaration entry in the index.
 */
data class ExpectActualEntry(
    val name: String,
    val kind: DeclarationKind,
    val fileUri: String,
    val offset: Int,
    val signature: String? = null  // For disambiguation (e.g., function parameters)
)

enum class DeclarationKind {
    FUNCTION, PROPERTY, CLASS
}

/**
 * Index for fast expect/actual declaration lookup.
 *
 * Instead of scanning all files on every expect→actual navigation,
 * we build this index once at initialization and update it incrementally.
 */
class ExpectActualIndex {

    private val logger = LoggerFactory.getLogger(ExpectActualIndex::class.java)

    // Maps declaration name to list of expect entries
    private val expects = mutableMapOf<String, MutableList<ExpectActualEntry>>()

    // Maps declaration name to list of actual entries
    private val actuals = mutableMapOf<String, MutableList<ExpectActualEntry>>()

    // Track indexed files for incremental updates
    private val indexedFiles = mutableSetOf<String>()

    /**
     * Add an expect declaration to the index.
     */
    fun addExpect(entry: ExpectActualEntry) {
        expects.getOrPut(entry.name) { mutableListOf() }.add(entry)
    }

    /**
     * Add an actual declaration to the index.
     */
    fun addActual(entry: ExpectActualEntry) {
        actuals.getOrPut(entry.name) { mutableListOf() }.add(entry)
    }

    /**
     * Get all actual implementations for a given expect name.
     */
    fun getActualsFor(expectName: String): List<ExpectActualEntry> {
        return actuals[expectName] ?: emptyList()
    }

    /**
     * Get all expect declarations for a given actual name.
     */
    fun getExpectsFor(actualName: String): List<ExpectActualEntry> {
        return expects[actualName] ?: emptyList()
    }

    /**
     * Remove all entries for a specific file (for incremental updates).
     */
    fun removeEntriesForFile(fileUri: String) {
        expects.values.forEach { entries ->
            entries.removeAll { it.fileUri == fileUri }
        }
        actuals.values.forEach { entries ->
            entries.removeAll { it.fileUri == fileUri }
        }
        // Clean up empty entries
        expects.entries.removeAll { it.value.isEmpty() }
        actuals.entries.removeAll { it.value.isEmpty() }
        indexedFiles.remove(fileUri)
    }

    /**
     * Mark a file as indexed.
     */
    fun markFileIndexed(fileUri: String) {
        indexedFiles.add(fileUri)
    }

    /**
     * Check if a file has been indexed.
     */
    fun isFileIndexed(fileUri: String): Boolean {
        return fileUri in indexedFiles
    }

    /**
     * Clear the entire index.
     */
    fun clear() {
        expects.clear()
        actuals.clear()
        indexedFiles.clear()
    }

    /**
     * Get index statistics for logging.
     */
    fun stats(): String {
        val expectCount = expects.values.sumOf { it.size }
        val actualCount = actuals.values.sumOf { it.size }
        return "ExpectActualIndex: $expectCount expects, $actualCount actuals in ${indexedFiles.size} files"
    }
}
