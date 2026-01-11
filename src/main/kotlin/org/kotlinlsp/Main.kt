// by Claude
package org.kotlinlsp

import org.eclipse.lsp4j.launch.LSPLauncher
import org.kotlinlsp.server.KmpLanguageServer
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("org.kotlinlsp.Main")

/**
 * Entry point for the KMP Kotlin Language Server.
 * Connects to a language client via stdin/stdout using JSON-RPC.
 */
fun main(args: Array<String>) {
    logger.info("Starting KMP Kotlin Language Server...")

    val server = KmpLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)

    val client = launcher.remoteProxy
    server.connect(client)

    logger.info("LSP server initialized, listening for requests...")
    launcher.startListening().get()
}
