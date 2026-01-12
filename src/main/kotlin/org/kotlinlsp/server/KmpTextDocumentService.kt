// by Claude - Migrated to use AnalysisSession
package org.kotlinlsp.server

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.analysis.CompletionProvider
import org.kotlinlsp.analysis.DiagnosticsProvider
import org.kotlinlsp.analysis.DefinitionProvider
import org.kotlinlsp.analysis.HoverProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Handles text document operations: open, change, save, completion, hover, etc.
 */
class KmpTextDocumentService(
    private val server: KmpLanguageServer,
    private val analysisSession: AnalysisSession
) : TextDocumentService {

    private val logger = LoggerFactory.getLogger(KmpTextDocumentService::class.java)

    private val completionProvider = CompletionProvider(analysisSession)
    private val hoverProvider = HoverProvider(analysisSession)
    private val definitionProvider = DefinitionProvider(analysisSession)
    private val diagnosticsProvider = DiagnosticsProvider(analysisSession)

    // Track open documents
    private val openDocuments = mutableMapOf<String, String>()

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val text = params.textDocument.text
        logger.info("Document opened: $uri")

        openDocuments[uri] = text
        analysisSession.updateDocument(uri, text)

        // Trigger diagnostics
        publishDiagnostics(uri)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        // For Full sync, we get the entire document content
        val text = params.contentChanges.lastOrNull()?.text ?: return
        logger.debug("Document changed: $uri")

        openDocuments[uri] = text
        analysisSession.updateDocument(uri, text)

        // Trigger diagnostics on change
        publishDiagnostics(uri)
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        logger.info("Document closed: $uri")

        openDocuments.remove(uri)
        analysisSession.closeDocument(uri)

        // Clear diagnostics for closed document
        server.publishDiagnostics(uri, emptyList())
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        logger.info("Document saved: $uri")

        // Force session rebuild on save to ensure full module context
        analysisSession.forceRebuild()

        // Re-analyze on save
        publishDiagnostics(uri)
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            logger.debug("Completion requested at $uri:${position.line}:${position.character}")

            try {
                val items = completionProvider.getCompletions(uri, position)
                Either.forLeft(items)
            } catch (e: Exception) {
                logger.error("Completion failed", e)
                Either.forLeft(emptyList())
            }
        }
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            logger.debug("Hover requested at $uri:${position.line}:${position.character}")

            try {
                hoverProvider.getHover(uri, position)
            } catch (e: Exception) {
                logger.error("Hover failed", e)
                null
            }
        }
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            logger.debug("Definition requested at $uri:${position.line}:${position.character}")

            try {
                val locations = definitionProvider.getDefinition(uri, position)
                Either.forLeft(locations)
            } catch (e: Exception) {
                logger.error("Definition lookup failed", e)
                Either.forLeft(emptyList())
            }
        }
    }

    private fun publishDiagnostics(uri: String) {
        CompletableFuture.runAsync {
            try {
                val diagnostics = diagnosticsProvider.getDiagnostics(uri)
                server.publishDiagnostics(uri, diagnostics)
            } catch (e: Exception) {
                logger.error("Diagnostics failed for $uri", e)
                server.publishDiagnostics(uri, emptyList())
            }
        }
    }
}
