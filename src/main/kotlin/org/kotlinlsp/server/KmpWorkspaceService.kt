// by Claude - Migrated to use AnalysisSession
package org.kotlinlsp.server

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.WorkspaceService
import org.kotlinlsp.analysis.AnalysisSession
import org.slf4j.LoggerFactory

/**
 * Handles workspace-level operations: configuration changes, watched files, etc.
 */
class KmpWorkspaceService(
    private val server: KmpLanguageServer,
    private val analysisSession: AnalysisSession
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(KmpWorkspaceService::class.java)

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.info("Configuration changed: ${params.settings}")
        // TODO: Handle configuration changes
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.info("Watched files changed: ${params.changes.size} changes")

        for (change in params.changes) {
            when (change.type) {
                FileChangeType.Created -> {
                    logger.debug("File created: ${change.uri}")
                }
                FileChangeType.Changed -> {
                    logger.debug("File changed: ${change.uri}")
                }
                FileChangeType.Deleted -> {
                    logger.debug("File deleted: ${change.uri}")
                }
                else -> {}
            }
        }

        // Invalidate session when project files change
        analysisSession.invalidateSession()
    }
}
