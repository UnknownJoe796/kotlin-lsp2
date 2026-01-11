# Kotlin Multiplatform LSP Extension

A VS Code extension for Kotlin with Kotlin Multiplatform (KMP) support.

## Features

- **Code Completion**: Kotlin keywords, file declarations, and scope-based completions
- **Hover Information**: Function signatures, property types, and KDoc documentation
- **Go to Definition**: Navigate to symbol definitions within the same file
- **Diagnostics**: Compiler errors and warnings

## Requirements

- Java 17 or higher
- The `kmp-lsp-0.1.0-all.jar` server JAR

## Installation

1. Build the LSP server JAR:
   ```bash
   cd /path/to/kotlin-lsp2
   ./gradlew fatJar
   ```

2. Copy the JAR to the extension:
   ```bash
   mkdir -p vscode-extension/server
   cp build/libs/kmp-lsp-0.1.0-all.jar vscode-extension/server/
   ```

3. Build the extension:
   ```bash
   cd vscode-extension
   npm install
   npm run compile
   ```

4. Package and install:
   ```bash
   npx @vscode/vsce package
   code --install-extension kmp-kotlin-0.1.0.vsix
   ```

## Configuration

- `kmpKotlin.serverPath`: Path to the LSP server JAR (optional, defaults to `server/kmp-lsp-0.1.0-all.jar` in extension directory)
- `kmpKotlin.javaHome`: Path to Java installation (optional, defaults to `JAVA_HOME` environment variable)
- `kmpKotlin.trace.server`: Trace communication between VS Code and the server (`off`, `messages`, `verbose`)

## Development

```bash
# Install dependencies
npm install

# Compile
npm run compile

# Watch mode
npm run watch
```

## Limitations

- Cross-file go-to-definition is not yet supported
- KMP KLIB dependencies are not fully processed
- Native platform targets have limited support

## License

MIT
