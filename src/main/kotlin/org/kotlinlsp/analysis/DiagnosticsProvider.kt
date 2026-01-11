// by Claude
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.kotlinlsp.project.SessionManager
import org.slf4j.LoggerFactory

/**
 * Provides diagnostics (errors, warnings) using the Kotlin compiler.
 */
class DiagnosticsProvider(private val sessionManager: SessionManager) {

    private val logger = LoggerFactory.getLogger(DiagnosticsProvider::class.java)

    fun getDiagnostics(uri: String): List<Diagnostic> {
        logger.debug("Diagnostics requested for $uri")

        // Trigger analysis to collect diagnostics
        sessionManager.analyzeFile(uri) ?: return emptyList()

        // Get collected diagnostics from the session manager
        val diagnosticInfos = sessionManager.getDiagnostics()

        return diagnosticInfos.mapNotNull { info ->
            try {
                // Filter to only diagnostics with location info
                val location = info.location ?: return@mapNotNull null

                // Get the document to convert offsets to positions
                val content = sessionManager.getDocumentContent(uri) ?: return@mapNotNull null

                // Create LSP diagnostic
                Diagnostic().apply {
                    severity = mapSeverity(info.severity)
                    message = info.message
                    source = "kotlin"
                    range = Range(
                        Position(location.line - 1, location.column - 1),
                        Position(location.line - 1, location.column + (location.lineContent?.length ?: 1))
                    )
                }
            } catch (e: Exception) {
                logger.warn("Error creating diagnostic: ${e.message}")
                null
            }
        }
    }

    private fun mapSeverity(severity: CompilerMessageSeverity): DiagnosticSeverity {
        return when {
            severity.isError -> DiagnosticSeverity.Error
            severity.isWarning -> DiagnosticSeverity.Warning
            else -> DiagnosticSeverity.Information
        }
    }
}
