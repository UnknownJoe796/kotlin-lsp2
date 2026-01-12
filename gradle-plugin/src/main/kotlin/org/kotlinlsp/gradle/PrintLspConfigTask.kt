// by Claude - Task for printing LSP config to stdout
package org.kotlinlsp.gradle

import com.google.gson.GsonBuilder
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Task that prints the LSP configuration to stdout.
 * Useful for debugging or piping to other tools.
 */
abstract class PrintLspConfigTask : DefaultTask() {

    @TaskAction
    fun print() {
        val config = buildConfig()
        val gson = GsonBuilder().setPrettyPrinting().create()
        println(gson.toJson(config))
    }

    private fun buildConfig(): LspConfig {
        val modules = mutableListOf<ModuleConfig>()

        // Try to get Kotlin Multiplatform extension
        val kotlinExtension = project.extensions.findByName("kotlin")

        if (kotlinExtension != null) {
            modules.addAll(extractKmpModules(kotlinExtension))
        } else {
            modules.addAll(extractJvmModules())
        }

        return LspConfig(
            name = project.name,
            modules = modules,
            dependencies = emptyList(),
            exclude = listOf("**/build/**", "**/.gradle/**"),
            settings = LspSettings()
        )
    }

    private fun extractKmpModules(kotlinExtension: Any): List<ModuleConfig> {
        val modules = mutableListOf<ModuleConfig>()

        try {
            val sourceSetsMethod = kotlinExtension.javaClass.getMethod("getSourceSets")
            val sourceSets = sourceSetsMethod.invoke(kotlinExtension) as? Iterable<*> ?: return modules

            for (sourceSet in sourceSets) {
                if (sourceSet == null) continue

                val name = sourceSet.javaClass.getMethod("getName").invoke(sourceSet) as? String ?: continue
                val platform = detectPlatform(name)
                val sourceRoots = extractSourceRoots(sourceSet)
                val dependsOn = extractDependsOn(sourceSet)

                if (sourceRoots.isNotEmpty()) {
                    modules.add(ModuleConfig(
                        name = name,
                        platform = platform,
                        sourceRoots = sourceRoots,
                        dependencies = emptyList(),
                        dependsOn = dependsOn,
                        isSource = !name.contains("Test", ignoreCase = true)
                    ))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract KMP modules: ${e.message}")
        }

        return modules
    }

    private fun extractJvmModules(): List<ModuleConfig> {
        val modules = mutableListOf<ModuleConfig>()

        val sourceSets = project.extensions.findByName("sourceSets") as? org.gradle.api.tasks.SourceSetContainer
            ?: return modules

        for (sourceSet in sourceSets) {
            val sourceRoots = mutableListOf<String>()

            sourceSet.extensions.findByName("kotlin")?.let { kotlinSourceSet ->
                try {
                    val srcDirs = kotlinSourceSet.javaClass.getMethod("getSrcDirs").invoke(kotlinSourceSet) as? Set<*>
                    srcDirs?.filterIsInstance<java.io.File>()?.filter { it.exists() }?.forEach {
                        sourceRoots.add(project.relativePath(it))
                    }
                } catch (e: Exception) { }
            }

            if (sourceRoots.isEmpty()) {
                sourceSet.java.srcDirs.filter { it.exists() }.forEach {
                    sourceRoots.add(project.relativePath(it))
                }
            }

            if (sourceRoots.isNotEmpty()) {
                modules.add(ModuleConfig(
                    name = sourceSet.name,
                    platform = "jvm",
                    sourceRoots = sourceRoots,
                    dependencies = emptyList(),
                    dependsOn = emptyList(),
                    isSource = !sourceSet.name.contains("test", ignoreCase = true)
                ))
            }
        }

        return modules
    }

    private fun extractSourceRoots(sourceSet: Any): List<String> {
        val roots = mutableListOf<String>()

        try {
            val kotlinMethod = sourceSet.javaClass.getMethod("getKotlin")
            val kotlinSourceSet = kotlinMethod.invoke(sourceSet)
            val srcDirsMethod = kotlinSourceSet.javaClass.getMethod("getSrcDirs")
            val srcDirs = srcDirsMethod.invoke(kotlinSourceSet) as? Set<*>

            srcDirs?.filterIsInstance<java.io.File>()?.filter { it.exists() }?.forEach {
                roots.add(project.relativePath(it))
            }
        } catch (e: Exception) { }

        return roots
    }

    private fun extractDependsOn(sourceSet: Any): List<String> {
        val dependsOn = mutableListOf<String>()

        try {
            val dependsOnMethod = sourceSet.javaClass.getMethod("getDependsOn")
            val deps = dependsOnMethod.invoke(sourceSet) as? Set<*>

            deps?.forEach { dep ->
                val name = dep?.javaClass?.getMethod("getName")?.invoke(dep) as? String
                if (name != null) dependsOn.add(name)
            }
        } catch (e: Exception) { }

        return dependsOn
    }

    private fun detectPlatform(sourceSetName: String): String {
        val name = sourceSetName.lowercase()
        return when {
            name.contains("common") -> "common"
            name.contains("jvm") -> "jvm"
            name.contains("js") -> "js"
            name.contains("ios") -> "ios"
            name.contains("macos") -> "macos"
            name.contains("linux") -> "linux"
            name.contains("mingw") || name.contains("windows") -> "windows"
            name.contains("native") -> "native"
            else -> "jvm"
        }
    }
}
