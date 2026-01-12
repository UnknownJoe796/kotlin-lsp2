# Kotlin LSP Server Usage Guide

A Kotlin Multiplatform Language Server providing IDE features for Kotlin code.

## Quick Start

### 1. Build the Server

```bash
./gradlew build
```

This creates a distribution in `build/distributions/` and scripts in `build/install/kmp-lsp/bin/`.

### 2. Run the Server

```bash
# Direct execution (recommended)
./build/install/kmp-lsp/bin/kmp-lsp

# Or from the built jar
java -jar build/libs/kmp-lsp-1.0-SNAPSHOT.jar
```

The server communicates via **stdin/stdout** using the LSP JSON-RPC protocol.

---

## Editor Configuration

### VS Code

Create `.vscode/settings.json` in your Kotlin project:

```json
{
  "kotlin.languageServer.enabled": true,
  "kotlin.languageServer.path": "/path/to/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp"
}
```

Or use a generic LSP extension like [vscode-languageclient](https://github.com/AverageMarcus/vscode-languageclient).

### Neovim (with nvim-lspconfig)

Add to your Neovim config:

```lua
local lspconfig = require('lspconfig')
local configs = require('lspconfig.configs')

-- Define the custom LSP
if not configs.kotlin_lsp then
  configs.kotlin_lsp = {
    default_config = {
      cmd = { '/path/to/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp' },
      filetypes = { 'kotlin' },
      root_dir = lspconfig.util.root_pattern('settings.gradle.kts', 'settings.gradle', 'build.gradle.kts', 'build.gradle', '.git'),
      settings = {},
    },
  }
end

lspconfig.kotlin_lsp.setup({
  on_attach = function(client, bufnr)
    -- Your keybindings here
  end,
})
```

### Helix

Add to `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "kotlin"
language-servers = ["kotlin-lsp"]

[language-server.kotlin-lsp]
command = "/path/to/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp"
```

### Emacs (with lsp-mode)

```elisp
(require 'lsp-mode)

(lsp-register-client
 (make-lsp-client
  :new-connection (lsp-stdio-connection '("/path/to/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp"))
  :major-modes '(kotlin-mode)
  :server-id 'kotlin-lsp))

(add-hook 'kotlin-mode-hook #'lsp)
```

### Sublime Text (with LSP package)

Add to LSP settings:

```json
{
  "clients": {
    "kotlin-lsp": {
      "enabled": true,
      "command": ["/path/to/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp"],
      "selector": "source.kotlin"
    }
  }
}
```

---

## Claude Code Integration

Claude Code (v2.0.74+) has **native LSP support** through plugins. This gives Claude 900x faster code navigation.

### Method 1: Create a Local LSP Plugin (Recommended)

Create a plugin directory structure:

```
~/.claude-plugins/kotlin-lsp/
├── plugin.json
└── .lsp.json
```

**File: `~/.claude-plugins/kotlin-lsp/plugin.json`**
```json
{
  "name": "kotlin-lsp",
  "version": "1.0.0",
  "description": "Kotlin Multiplatform Language Server",
  "author": "local"
}
```

**File: `~/.claude-plugins/kotlin-lsp/.lsp.json`**
```json
{
  "kotlin": {
    "command": "/Users/jivie/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp",
    "args": [],
    "extensionToLanguage": {
      ".kt": "kotlin",
      ".kts": "kotlin"
    },
    "transport": "stdio",
    "restartOnCrash": true,
    "maxRestarts": 3,
    "startupTimeout": 30000
  }
}
```

Then enable the plugin:

```bash
# Test locally first
claude --plugin-dir ~/.claude-plugins/kotlin-lsp

# Or add to your settings.json
```

**File: `~/.claude/settings.json`**
```json
{
  "enabledPlugins": {
    "kotlin-lsp": true
  }
}
```

### Method 2: Project-Level Plugin

Create the plugin in your project:

```
your-kotlin-project/
├── .claude-plugin/
│   ├── plugin.json
│   └── .lsp.json
└── src/
```

**File: `.claude-plugin/plugin.json`**
```json
{
  "name": "kotlin-lsp-local",
  "version": "1.0.0",
  "description": "Kotlin LSP for this project"
}
```

**File: `.claude-plugin/.lsp.json`**
```json
{
  "kotlin": {
    "command": "/Users/jivie/kotlin-lsp2/build/install/kmp-lsp/bin/kmp-lsp",
    "args": [],
    "extensionToLanguage": {
      ".kt": "kotlin",
      ".kts": "kotlin"
    }
  }
}
```

### Method 3: Add to CLAUDE.md for Context

Even with the LSP plugin, adding context to your `CLAUDE.md` helps:

```markdown
## Kotlin Development

This project uses a Kotlin Multiplatform LSP server providing:
- Code completion with full type information
- Hover documentation with KDoc
- Go-to-definition (including expect/actual navigation)
- Find all references across workspace
- Rename refactoring
- Code actions (quick fixes)
- Document and workspace symbols
- Signature help for function parameters
- Semantic syntax highlighting
- Code formatting

The LSP is configured via the kotlin-lsp plugin.
```

### LSP Configuration Options

Full configuration options for `.lsp.json`:

```json
{
  "kotlin": {
    "command": "/path/to/kmp-lsp",
    "args": [],
    "extensionToLanguage": {
      ".kt": "kotlin",
      ".kts": "kotlin"
    },
    "transport": "stdio",
    "env": {
      "JAVA_HOME": "/path/to/jdk"
    },
    "initializationOptions": {},
    "settings": {},
    "startupTimeout": 30000,
    "shutdownTimeout": 5000,
    "restartOnCrash": true,
    "maxRestarts": 3
  }
}
```

### What Claude Code Gets from the LSP

With the LSP connected, Claude Code can:
- **Navigate instantly** to definitions (50ms vs 45s with text search)
- **Find all references** to any symbol
- **See diagnostics** (errors/warnings) in real-time
- **Get hover info** with types and documentation
- **Understand project structure** via document/workspace symbols

---

## Available Features

### 1. Code Completion
- **Trigger**: Type `.` or `:`
- **Provides**: Functions, properties, classes, keywords, snippets
- **Context-aware**: Understands scope, imports, and types

### 2. Hover Information
- **Trigger**: Hover over any symbol
- **Shows**: Type signatures, KDoc documentation, expect/actual info

### 3. Go to Definition
- **Trigger**: Ctrl+Click or F12
- **Works with**: Functions, properties, classes, parameters
- **Multiplatform**: Navigates between expect/actual declarations

### 4. Find References
- **Trigger**: Shift+F12 or right-click → Find References
- **Searches**: All files in the workspace
- **Options**: Include/exclude declaration

### 5. Document Symbols (Outline)
- **Trigger**: Ctrl+Shift+O or View → Outline
- **Shows**: Hierarchical view of classes, functions, properties

### 6. Workspace Symbols
- **Trigger**: Ctrl+T or Ctrl+Shift+P → "Go to Symbol in Workspace"
- **Features**: Fuzzy matching, CamelCase search

### 7. Signature Help
- **Trigger**: Type `(` inside a function call
- **Shows**: Parameter names, types, and documentation
- **Updates**: As you type commas between arguments

### 8. Code Actions (Quick Fixes)
- **Trigger**: Ctrl+. or lightbulb icon
- **Available actions**:
  - Remove unused imports
  - Add explicit type annotations
  - Convert val ↔ var
  - Convert expression body ↔ block body
  - Import suggestions for unresolved references

### 9. Rename Symbol
- **Trigger**: F2 or right-click → Rename
- **Scope**: Renames across all files in workspace
- **Validates**: Checks for valid Kotlin identifiers

### 10. Formatting
- **Trigger**: Shift+Alt+F or right-click → Format Document
- **Handles**: Indentation, spacing, blank lines
- **Options**: Respects tabSize and insertSpaces settings

### 11. Semantic Tokens (Syntax Highlighting)
- **Automatic**: Enhanced highlighting based on symbol resolution
- **Distinguishes**: Classes, interfaces, enums, functions, properties, parameters, type parameters

### 12. Diagnostics
- **Automatic**: Shows errors and warnings as you type
- **Source**: Kotlin compiler analysis

---

## Kotlin Multiplatform Support

The LSP has full support for Kotlin Multiplatform projects:

### Expect/Actual Navigation
- **From expect**: Go to Definition shows all actual implementations
- **From actual**: Go to Definition shows the expect declaration
- **Hover**: Shows platform information

### Platform Detection
Automatically detects platform from source set paths:
- `commonMain/` → Common
- `jvmMain/` → JVM
- `jsMain/` → JavaScript
- `iosMain/` → iOS Native
- `macosMain/` → macOS Native
- etc.

### Module Resolution
- Respects `dependsOn` relationships
- Handles shared source sets
- Resolves dependencies across modules

---

## Project Configuration

The LSP supports two modes: **auto-detection** (default) or **explicit configuration**.

### Option 1: Explicit Configuration (Recommended for Complex Projects)

Create a `kmp-lsp.json` file in your project root:

```json
{
  "name": "my-kotlin-project",
  "modules": [
    {
      "name": "commonMain",
      "platform": "common",
      "sourceRoots": ["src/commonMain/kotlin"],
      "dependencies": [],
      "dependsOn": []
    },
    {
      "name": "jvmMain",
      "platform": "jvm",
      "sourceRoots": ["src/jvmMain/kotlin"],
      "dependencies": [
        "~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.0/*/*.jar"
      ],
      "dependsOn": ["commonMain"]
    },
    {
      "name": "iosMain",
      "platform": "ios",
      "sourceRoots": ["src/iosMain/kotlin"],
      "dependencies": [],
      "dependsOn": ["commonMain"]
    }
  ],
  "dependencies": [
    "libs/*.jar"
  ],
  "exclude": [
    "**/build/**",
    "**/.gradle/**"
  ],
  "settings": {
    "enableDiagnostics": true,
    "enableSemanticTokens": true,
    "diagnosticsDelay": 500,
    "maxCompletionItems": 100
  }
}
```

#### Configuration Reference

**Top-level fields:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Project name (optional, defaults to directory name) |
| `modules` | array | List of module configurations |
| `dependencies` | array | Global dependencies for all modules (supports globs) |
| `exclude` | array | File patterns to exclude from analysis |
| `settings` | object | LSP behavior settings |

**Module fields:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Module name (used for `dependsOn` references) |
| `platform` | string | `common`, `jvm`, `js`, `ios`, `macos`, `linux`, `windows`, `native` |
| `sourceRoots` | array | Relative paths to source directories |
| `dependencies` | array | JARs/KLIBs (supports `~` and `*` globs) |
| `dependsOn` | array | Module names this depends on (for expect/actual) |
| `isSource` | boolean | `true` for source, `false` for test modules |

**Settings fields:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enableDiagnostics` | boolean | `true` | Real-time error checking |
| `enableSemanticTokens` | boolean | `true` | Enhanced syntax highlighting |
| `diagnosticsDelay` | int | `500` | Delay before computing diagnostics (ms) |
| `maxCompletionItems` | int | `100` | Max completion suggestions |
| `autoImport` | boolean | `true` | Suggest imports in completions |
| `jdkHome` | string | auto | Path to JDK (auto-detected if null) |
| `kotlinStdlib` | string | auto | Path to kotlin-stdlib (auto-detected if null) |

#### Dependency Glob Patterns

Dependencies support glob patterns and `~` expansion:

```json
{
  "dependencies": [
    "libs/*.jar",
    "~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.0/*/*.jar",
    "/usr/local/lib/kotlin/*.klib"
  ]
}
```

### Option 2: Generate Config with Gradle Plugin (Recommended)

The easiest way to create an accurate `kmp-lsp.json` is to use the Gradle plugin:

**Step 1: Add the plugin to your project's `build.gradle.kts`:**

```kotlin
plugins {
    // Your existing plugins...
    id("org.kotlinlsp.gradle") version "1.0.0"
}

// Optional configuration
kmpLsp {
    outputFile = file("kmp-lsp.json")
    includeTestSources = false
    includeDependencies = true
    includeTransitiveDependencies = true
}
```

**Step 2: Generate the config:**

```bash
./gradlew generateLspConfig
```

This creates `kmp-lsp.json` with:
- All source sets from your Kotlin/KMP configuration
- Resolved dependency paths (actual JAR/KLIB locations)
- Correct `dependsOn` relationships for expect/actual
- Platform detection from source set names

**Debug: Print config to stdout:**

```bash
./gradlew printLspConfig
```

#### Plugin Configuration Options

```kotlin
kmpLsp {
    // Output file location (default: kmp-lsp.json in project root)
    outputFile = file("kmp-lsp.json")

    // Include test source sets (default: false)
    includeTestSources = false

    // Include resolved dependencies (default: true)
    includeDependencies = true

    // Include transitive dependencies (default: true)
    includeTransitiveDependencies = true

    // Pretty-print JSON (default: true)
    prettyPrint = true

    // Additional source roots to include
    additionalSourceRoots = listOf("generated/src/main/kotlin")

    // Patterns to exclude from analysis
    excludePatterns = listOf(
        "**/build/**",
        "**/.gradle/**",
        "**/generated/**"
    )
}
```

#### Installing the Plugin Locally

Build and publish to local repository:

```bash
cd /path/to/kotlin-lsp2
./gradlew :gradle-plugin:publishToMavenLocal
```

Then in your project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
    }
}
```

### Option 3: Auto-Detection (Zero Config)

If no `kmp-lsp.json` exists, the LSP auto-detects your project structure.

### How Project Discovery Works

1. **Gradle Tooling API (Primary)** - Connects to your Gradle build and reads:
   - Module structure from `settings.gradle.kts`
   - Source roots from `build.gradle.kts`
   - Dependencies and their locations

2. **Directory Scanning (Fallback)** - If Gradle fails, scans standard KMP layout:
   - `src/commonMain/kotlin/`
   - `src/jvmMain/kotlin/`, `src/jsMain/kotlin/`, etc.
   - `src/main/kotlin/` for single-module projects

3. **Single-File Mode** - Last resort for non-Gradle projects

### Supported Layouts

**Kotlin Multiplatform:**
```
my-project/
├── settings.gradle.kts
├── build.gradle.kts
├── src/
│   ├── commonMain/kotlin/
│   ├── jvmMain/kotlin/
│   ├── jsMain/kotlin/
│   └── iosMain/kotlin/
└── gradle/
    └── libs.versions.toml
```

**Single-Module JVM:**
```
my-project/
├── build.gradle.kts
└── src/
    └── main/kotlin/
```

**Multi-Module:**
```
my-project/
├── settings.gradle.kts
├── module-a/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
└── module-b/
    ├── build.gradle.kts
    └── src/main/kotlin/
```

### Supported Build Systems
- **Gradle with Kotlin DSL** (recommended)
- **Gradle with Groovy DSL**
- **Single-file mode** (fallback for non-Gradle projects)

### No Configuration File Needed

Unlike some LSPs, this one does **not** require a `project.json`, `compile_commands.json`, or similar. It reads your existing Gradle configuration automatically.

---

## Troubleshooting

### LSP Not Starting

1. Check the server runs manually:
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"rootUri":"file:///tmp"}}' | ./build/install/kotlin-lsp/bin/kotlin-lsp
   ```

2. Check Java version (requires JDK 11+):
   ```bash
   java -version
   ```

### No Completions

1. Ensure the file is in a source root (`src/main/kotlin/`, etc.)
2. Check that the project has a valid Gradle setup
3. Look for errors in the LSP log output

### Slow Performance

1. First analysis takes time (building module graph)
2. Subsequent operations are faster
3. Consider excluding large generated directories

### Diagnostics Not Showing

1. Save the file to trigger full analysis
2. Check that the file compiles with `./gradlew build`

---

## Logging

Enable debug logging by setting the environment variable:

```bash
export KOTLIN_LSP_LOG_LEVEL=DEBUG
./build/install/kotlin-lsp/bin/kotlin-lsp
```

Or pass JVM arguments:

```bash
java -Dorg.slf4j.simpleLogger.defaultLogLevel=debug -jar build/libs/kotlin-lsp-1.0-SNAPSHOT.jar
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    LSP Client (Editor)                   │
└─────────────────────────────────────────────────────────┘
                           │ JSON-RPC
                           ▼
┌─────────────────────────────────────────────────────────┐
│                   KmpLanguageServer                      │
│  ┌─────────────────────┐  ┌─────────────────────────┐   │
│  │ KmpTextDocumentSvc  │  │   KmpWorkspaceService   │   │
│  └─────────────────────┘  └─────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    AnalysisSession                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ KtFile Cache │  │ Module Cache │  │ E/A Index    │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────┐
│              Kotlin Analysis API (Standalone)            │
│         (Symbol resolution, type inference, etc.)        │
└─────────────────────────────────────────────────────────┘
```

---

## Contributing

### Adding New Features

1. Create a provider in `src/main/kotlin/org/kotlinlsp/analysis/`
2. Wire it up in `KmpTextDocumentService` or `KmpWorkspaceService`
3. Register capability in `KmpLanguageServer.initialize()`

### Testing

```bash
./gradlew test
```

---

## License

[Add your license here]
