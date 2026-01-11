// by Claude - Migrated to Kotlin Analysis API
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaDiagnosticWithPsi
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory

/**
 * Provides diagnostics (errors, warnings) using Kotlin Analysis API.
 */
class DiagnosticsProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(DiagnosticsProvider::class.java)

    fun getDiagnostics(uri: String): List<Diagnostic> {
        logger.debug("Diagnostics requested for $uri")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()

        return analysisSession.withAnalysis(ktFile) {
            collectDiagnostics(ktFile)
        } ?: emptyList()
    }

    /**
     * Collect diagnostics from the Analysis API.
     */
    @OptIn(KaExperimentalApi::class)
    private fun KaSession.collectDiagnostics(ktFile: KtFile): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val text = ktFile.text

        try {
            // Collect all diagnostics for the file
            val kaDiagnostics = ktFile.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS)

            for (kaDiagnostic in kaDiagnostics) {
                if (kaDiagnostic is KaDiagnosticWithPsi<*>) {
                    val psi = kaDiagnostic.psi
                    val textRange = psi.textRange

                    diagnostics.add(Diagnostic().apply {
                        range = Range(
                            offsetToPosition(textRange.startOffset, text),
                            offsetToPosition(textRange.endOffset, text)
                        )
                        severity = mapSeverity(kaDiagnostic.severity)
                        source = "kotlin"
                        message = kaDiagnostic.defaultMessage
                    })
                }
            }
        } catch (e: Exception) {
            logger.warn("Error collecting diagnostics: ${e.message}", e)
        }

        logger.debug("Found ${diagnostics.size} diagnostics for $ktFile")
        return diagnostics
    }

    private fun mapSeverity(severity: KaSeverity): DiagnosticSeverity {
        return when (severity) {
            KaSeverity.ERROR -> DiagnosticSeverity.Error
            KaSeverity.WARNING -> DiagnosticSeverity.Warning
            else -> DiagnosticSeverity.Information
        }
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
