// by Claude - Migrated to use AnalysisSession
package org.kotlinlsp.server

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.TextDocumentService
import org.kotlinlsp.analysis.AnalysisSession
import org.kotlinlsp.analysis.CodeActionProvider
import org.kotlinlsp.analysis.CompletionProvider
import org.kotlinlsp.analysis.DiagnosticsProvider
import org.kotlinlsp.analysis.DefinitionProvider
import org.kotlinlsp.analysis.DocumentSymbolProvider
import org.kotlinlsp.analysis.FormattingProvider
import org.kotlinlsp.analysis.HoverProvider
import org.kotlinlsp.analysis.ReferencesProvider
import org.kotlinlsp.analysis.RenameProvider
import org.kotlinlsp.analysis.SemanticTokensProvider
import org.kotlinlsp.analysis.SignatureHelpProvider
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
    private val documentSymbolProvider = DocumentSymbolProvider(analysisSession)
    private val signatureHelpProvider = SignatureHelpProvider(analysisSession)
    private val referencesProvider = ReferencesProvider(analysisSession)
    private val semanticTokensProvider = SemanticTokensProvider(analysisSession)
    private val codeActionProvider = CodeActionProvider(analysisSession)
    private val renameProvider = RenameProvider(analysisSession)
    private val formattingProvider = FormattingProvider(analysisSession)

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

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val includeDeclaration = params.context?.isIncludeDeclaration ?: false
            logger.debug("References requested at $uri:${position.line}:${position.character}")

            try {
                referencesProvider.getReferences(uri, position, includeDeclaration)
            } catch (e: Exception) {
                logger.error("References lookup failed", e)
                emptyList()
            }
        }
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            logger.debug("Document symbols requested for $uri")

            try {
                val symbols = documentSymbolProvider.getDocumentSymbols(uri)
                symbols.map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }
            } catch (e: Exception) {
                logger.error("Document symbols failed", e)
                emptyList()
            }
        }
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            logger.debug("Signature help requested at $uri:${position.line}:${position.character}")

            try {
                signatureHelpProvider.getSignatureHelp(uri, position)
            } catch (e: Exception) {
                logger.error("Signature help failed", e)
                null
            }
        }
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val range = params.range
            val context = params.context
            logger.debug("Code action requested at $uri:${range.start.line}:${range.start.character}")

            try {
                codeActionProvider.getCodeActions(uri, range, context)
            } catch (e: Exception) {
                logger.error("Code action failed", e)
                emptyList()
            }
        }
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            val newName = params.newName
            logger.debug("Rename requested at $uri:${position.line}:${position.character} to '$newName'")

            try {
                renameProvider.rename(uri, position, newName)
            } catch (e: Exception) {
                logger.error("Rename failed", e)
                null
            }
        }
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val position = params.position
            logger.debug("Prepare rename requested at $uri:${position.line}:${position.character}")

            try {
                val result = renameProvider.prepareRename(uri, position)
                if (result != null) {
                    Either3.forSecond(result)
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.error("Prepare rename failed", e)
                null
            }
        }
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val options = params.options
            logger.debug("Formatting requested for $uri")

            try {
                formattingProvider.format(uri, options)
            } catch (e: Exception) {
                logger.error("Formatting failed", e)
                emptyList()
            }
        }
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit>> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            val range = params.range
            val options = params.options
            logger.debug("Range formatting requested for $uri")

            try {
                formattingProvider.formatRange(uri, range, options)
            } catch (e: Exception) {
                logger.error("Range formatting failed", e)
                emptyList()
            }
        }
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return CompletableFuture.supplyAsync {
            val uri = params.textDocument.uri
            logger.debug("Semantic tokens requested for $uri")

            try {
                semanticTokensProvider.getSemanticTokensFull(uri) ?: SemanticTokens(emptyList())
            } catch (e: Exception) {
                logger.error("Semantic tokens failed", e)
                SemanticTokens(emptyList())
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
