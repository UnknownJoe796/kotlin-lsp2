// by Claude
import * as path from 'path';
import * as fs from 'fs';
import { workspace, ExtensionContext, window } from 'vscode';

import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: ExtensionContext) {
    // Get server JAR path from configuration or default to extension directory
    const config = workspace.getConfiguration('kmpKotlin');
    let serverPath = config.get<string>('serverPath');

    if (!serverPath) {
        // Look for server JAR in extension directory
        serverPath = context.asAbsolutePath(path.join('server', 'kmp-lsp-0.1.0-all.jar'));
    }

    if (!fs.existsSync(serverPath)) {
        window.showErrorMessage(`Kotlin LSP server not found at: ${serverPath}`);
        return;
    }

    // Find Java executable
    const javaHome = config.get<string>('javaHome') || process.env.JAVA_HOME;
    const javaExecutable = javaHome
        ? path.join(javaHome, 'bin', 'java')
        : 'java';

    // Server options - run the JAR with Java
    const serverOptions: ServerOptions = {
        run: {
            command: javaExecutable,
            args: ['-jar', serverPath],
        },
        debug: {
            command: javaExecutable,
            args: [
                '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005',
                '-jar',
                serverPath
            ],
        }
    };

    // Client options
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'kotlin' }
        ],
        synchronize: {
            fileEvents: workspace.createFileSystemWatcher('**/*.{kt,kts}')
        }
    };

    // Create and start the language client
    client = new LanguageClient(
        'kmpKotlin',
        'Kotlin Multiplatform LSP',
        serverOptions,
        clientOptions
    );

    // Start the client
    client.start();
    console.log('Kotlin Multiplatform LSP extension activated');
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
