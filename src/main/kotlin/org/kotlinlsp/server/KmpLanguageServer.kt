// by Claude
package org.kotlinlsp.server

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import org.kotlinlsp.project.SessionManager
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import kotlin.system.exitProcess

/**
 * Main LSP server implementation for Kotlin Multiplatform.
 * Handles LSP lifecycle and delegates document operations to KmpTextDocumentService.
 */
class KmpLanguageServer : LanguageServer, LanguageClientAware {

    private val logger = LoggerFactory.getLogger(KmpLanguageServer::class.java)

    private lateinit var client: LanguageClient
    private val sessionManager = SessionManager()
    private val textDocumentService = KmpTextDocumentService(this, sessionManager)
    private val workspaceService = KmpWorkspaceService(this, sessionManager)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing KMP Language Server...")
        logger.info("Client info: ${params.clientInfo?.name} ${params.clientInfo?.version}")
        logger.info("Root URI: ${params.rootUri}")

        // Initialize the session manager with workspace root
        params.rootUri?.let { uri ->
            sessionManager.initializeWorkspace(uri)
        }

        val capabilities = ServerCapabilities().apply {
            // Text document sync - we handle full document sync for now
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

            // Completion support
            completionProvider = CompletionOptions().apply {
                resolveProvider = false
                triggerCharacters = listOf(".", ":")
            }

            // Hover support
            hoverProvider = Either.forLeft(true)

            // Go to definition
            definitionProvider = Either.forLeft(true)

            // Diagnostics are pushed via publishDiagnostics, no pull support needed
        }

        val serverInfo = ServerInfo("KMP Kotlin LSP", "0.1.0")
        val result = InitializeResult(capabilities, serverInfo)

        logger.info("Server capabilities configured")
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized(params: InitializedParams) {
        logger.info("Client initialized, server is ready")
    }

    override fun shutdown(): CompletableFuture<Any> {
        logger.info("Shutdown requested")
        sessionManager.dispose()
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        logger.info("Exit requested")
        exitProcess(0)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
        logger.info("Connected to language client")
    }

    fun getClient(): LanguageClient = client

    /**
     * Publish diagnostics to the client for a specific document.
     */
    fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        val params = PublishDiagnosticsParams(uri, diagnostics)
        client.publishDiagnostics(params)
    }
}
