# Kotlin LSP Server

A Language Server Protocol (LSP) implementation for Kotlin, with first-class support for Kotlin Multiplatform (KMP) projects.

**Note:** This entire project was vibe-coded and could be a complete lie.  Everything below here is written by AI and should be trusted about as far as it could be thrown until I can spend some time testing it myself.

## Features

- **Completions** - Context-aware code completions with auto-import
- **Hover** - Type information and documentation on hover
- **Go to Definition** - Navigate to symbol definitions across files
- **Find References** - Find all usages of a symbol across the workspace
- **Document Symbols** - Outline view of classes, functions, properties
- **Workspace Symbols** - Search for symbols across the entire project
- **Signature Help** - Parameter hints when calling functions
- **Diagnostics** - Real-time error and warning reporting
- **Code Actions** - Quick fixes (remove unused imports, add types, etc.)
- **Rename** - Rename symbols across all files
- **Formatting** - Basic code formatting
- **Semantic Tokens** - Enhanced syntax highlighting

## Building

```bash
./gradlew build
./gradlew shadowJar  # Creates fat JAR at build/libs/kotlin-lsp-1.0-SNAPSHOT-all.jar
```

## Usage

### Running the Server

```bash
java -jar build/libs/kotlin-lsp-1.0-SNAPSHOT-all.jar
```

The server communicates via stdin/stdout using the Language Server Protocol.

### Editor Integration

See [USAGE.md](USAGE.md) for detailed setup instructions for:
- VS Code
- Neovim
- Helix
- Emacs
- Sublime Text
- Claude Code

### Project Configuration

The LSP server can detect project structure automatically from Gradle, or you can provide an explicit `kmp-lsp.json` configuration file. See [kmp-lsp.example.json](kmp-lsp.example.json) for the format.

#### Gradle Plugin

For accurate project configuration, use the included Gradle plugin:

```kotlin
plugins {
    id("org.kotlinlsp.gradle") version "1.0.0"
}
```

Then run:
```bash
./gradlew generateLspConfig
```

This generates a `kmp-lsp.json` file with your project's actual source sets and dependencies.

## Architecture

```
src/main/kotlin/org/kotlinlsp/
├── Main.kt              # Entry point
├── analysis/            # Kotlin Analysis API integration
├── index/               # Symbol indexing
├── project/             # Project structure detection
└── server/              # LSP server implementation
    ├── KmpLanguageServer.kt
    ├── KmpTextDocumentService.kt
    ├── KmpWorkspaceService.kt
    └── providers/       # Feature implementations
```

Built on:
- [Kotlin Analysis API](https://kotlin.github.io/analysis-api/) (standalone mode)
- [LSP4J](https://github.com/eclipse-lsp4j/lsp4j) for LSP implementation

## Subprojects

- **gradle-plugin/** - Gradle plugin for generating `kmp-lsp.json`
- **claude-plugin/** - Claude Code LSP integration
- **vscode-extension/** - VS Code extension (basic)

## Documentation

- [USAGE.md](USAGE.md) - Detailed usage and editor setup
- [ANALYSIS_API_SETUP.md](ANALYSIS_API_SETUP.md) - Kotlin Analysis API notes
- [STATUS.md](STATUS.md) - Development status and known issues
- [PLAN.md](PLAN.md) - Original implementation plan

## License

MIT

---

*Built with vibes and Claude. No unit tests were harmed in the making of this LSP server (because there aren't any).*
