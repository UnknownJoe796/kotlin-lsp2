// by Claude
package org.kotlinlsp.project

import java.nio.file.Path

/**
 * Represents a Kotlin Multiplatform project structure.
 */
data class KmpProject(
    val name: String,
    val rootPath: Path,
    val modules: List<KmpModule>
) {
    /**
     * Find a module by name.
     */
    fun findModule(name: String): KmpModule? = modules.find { it.name == name }

    /**
     * Get all source modules (not library dependencies).
     */
    fun sourceModules(): List<KmpModule> = modules.filter { it.isSource }
}

/**
 * Represents a source set / module in a KMP project.
 */
data class KmpModule(
    val name: String,
    val platform: KmpPlatform,
    val sourceRoots: List<Path>,
    val dependencies: List<LibraryDependency>,
    val dependsOn: List<String>,  // Names of modules this depends on (for expect/actual)
    val isSource: Boolean = true
)

/**
 * Represents a library dependency (JAR or KLIB).
 */
data class LibraryDependency(
    val name: String,
    val path: Path,
    val isKlib: Boolean = false
) {
    companion object {
        fun fromPath(path: Path): LibraryDependency {
            val fileName = path.fileName.toString()
            return LibraryDependency(
                name = fileName.substringBeforeLast("."),
                path = path,
                isKlib = fileName.endsWith(".klib")
            )
        }
    }
}

/**
 * Represents target platforms in KMP.
 */
enum class KmpPlatform {
    COMMON,
    JVM,
    JS,
    NATIVE_IOS,
    NATIVE_MACOS,
    NATIVE_LINUX,
    NATIVE_WINDOWS,
    NATIVE_OTHER;

    companion object {
        /**
         * Parse platform from source set name (e.g., "commonMain", "iosMain", "jvmMain").
         */
        fun fromSourceSetName(name: String): KmpPlatform {
            val lowerName = name.lowercase()
            return when {
                lowerName.contains("common") -> COMMON
                lowerName.contains("jvm") -> JVM
                lowerName.contains("js") -> JS
                lowerName.contains("ios") -> NATIVE_IOS
                lowerName.contains("macos") -> NATIVE_MACOS
                lowerName.contains("linux") -> NATIVE_LINUX
                lowerName.contains("mingw") || lowerName.contains("windows") -> NATIVE_WINDOWS
                lowerName.contains("native") -> NATIVE_OTHER
                else -> COMMON  // Default to common for unknown
            }
        }
    }
}
