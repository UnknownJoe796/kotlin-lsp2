# REDO: KMP-First Kotlin LSP

## Executive Summary

Build a new Kotlin LSP from scratch using the Analysis API standalone mode. The key insight: **KLIB support already exists in the Analysis API** - we just need to wire it up correctly.

The official kotlin-lsp doesn't support KMP because it doesn't set up library modules with KLIB binary roots. We can do better.

## Why Build From Scratch?

| Approach | Pros | Cons |
|----------|------|------|
| Patch kotlin-lsp | Less code | Partially closed source, complex IntelliJ deps, moving target |
| Contribute upstream | "Proper" solution | Slow, may not align with JetBrains priorities |
| **Build new LSP** | Full control, KMP-first, simpler | More initial work |

The Analysis API does all the hard work (parsing, resolution, type inference). An LSP is just:
1. JSON-RPC message handling
2. Map LSP requests → Analysis API calls
3. Map Analysis API results → LSP responses

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    KMP Kotlin LSP                        │
├─────────────────────────────────────────────────────────┤
│  LSP Protocol Layer (JSON-RPC over stdio)               │
│  ┌─────────────────────────────────────────────────┐   │
│  │ textDocument/completion → CompletionHandler      │   │
│  │ textDocument/hover → HoverHandler                │   │
│  │ textDocument/definition → DefinitionHandler      │   │
│  │ textDocument/diagnostics → DiagnosticsHandler    │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│  Project Model Layer                                    │
│  ┌─────────────────────────────────────────────────┐   │
│  │ WorkspaceManager - tracks open files, modules    │   │
│  │ GradleImporter - parses build.gradle.kts         │   │
│  │ ModuleBuilder - creates KtSourceModule/KtLibrary │   │
│  └─────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│  Kotlin Analysis API (standalone mode)                  │
│  ┌─────────────────────────────────────────────────┐   │
│  │ StandaloneAnalysisAPISession                     │   │
│  │ KtSourceModule (user code)                       │   │
│  │ KtLibraryModule (JARs + KLIBs!)                  │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## Key Dependencies

```kotlin
dependencies {
    // Analysis API - the brain
    implementation("org.jetbrains.kotlin:analysis-api-standalone:2.3.0")
    implementation("org.jetbrains.kotlin:analysis-api-fir:2.3.0")

    // KLIB support (already in analysis-api, just need correct setup)
    implementation("org.jetbrains.kotlin:kotlin-util-klib:2.3.0")

    // LSP protocol handling
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    // Gradle tooling for project import
    implementation("org.gradle:gradle-tooling-api:8.5")
}
```

## Project Structure

```
kmp-lsp/
├── build.gradle.kts
├── src/main/kotlin/
│   ├── Main.kt                      # Entry point
│   ├── server/
│   │   ├── KmpLanguageServer.kt     # LSP4J server implementation
│   │   └── KmpTextDocumentService.kt
│   ├── protocol/
│   │   ├── Handlers.kt              # LSP request handlers
│   │   └── Converters.kt            # LSP ↔ Analysis API conversions
│   ├── project/
│   │   ├── WorkspaceManager.kt      # Manages open projects
│   │   ├── SessionFactory.kt        # Creates Analysis API sessions
│   │   └── GradleImporter.kt        # Imports KMP projects
│   └── analysis/
│       ├── CompletionProvider.kt
│       ├── HoverProvider.kt
│       ├── DefinitionProvider.kt
│       └── DiagnosticsProvider.kt
└── src/test/kotlin/
    └── ...
```

## Phase 1: Minimal LSP (Week 1)

### Goal
Get a working LSP that can open a single Kotlin file and provide completions from stdlib.

### 1.1 LSP Server Skeleton

```kotlin
// Main.kt
fun main() {
    val server = KmpLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}

// KmpLanguageServer.kt
class KmpLanguageServer : LanguageServer, LanguageClientAware {
    private lateinit var client: LanguageClient
    private val textDocumentService = KmpTextDocumentService(this)
    private val workspaceService = KmpWorkspaceService(this)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val capabilities = ServerCapabilities().apply {
            textDocumentSync = TextDocumentSyncKind.Incremental
            completionProvider = CompletionOptions(false, listOf("."))
            hoverProvider = Either.forLeft(true)
            definitionProvider = Either.forLeft(true)
        }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun getTextDocumentService() = textDocumentService
    override fun getWorkspaceService() = workspaceService
    override fun connect(client: LanguageClient) { this.client = client }
    override fun shutdown() = CompletableFuture.completedFuture(null)
    override fun exit() { exitProcess(0) }
}
```

### 1.2 Analysis Session Factory

```kotlin
// SessionFactory.kt
class SessionFactory {
    private var session: StandaloneAnalysisAPISession? = null

    fun createSession(
        sourceRoots: List<Path>,
        libraryRoots: List<Path>,  // JARs and KLIBs!
        platform: TargetPlatform
    ): StandaloneAnalysisAPISession {
        return buildStandaloneAnalysisAPISession {
            buildKtModuleProvider {
                this.platform = platform

                // Create library modules for each dependency
                val libraryModules = libraryRoots.map { libPath ->
                    addModule(buildKtLibraryModule {
                        addBinaryRoot(libPath)
                        this.platform = platform
                        libraryName = libPath.nameWithoutExtension
                    })
                }

                // Create source module with dependencies
                addModule(buildKtSourceModule {
                    sourceRoots.forEach { addSourceRoot(it) }
                    libraryModules.forEach { addRegularDependency(it) }
                    this.platform = platform
                    moduleName = "main"
                })
            }
        }
    }
}
```

### 1.3 Completion Handler

```kotlin
// CompletionProvider.kt
class CompletionProvider(private val sessionFactory: SessionFactory) {

    fun getCompletions(uri: String, position: Position): List<CompletionItem> {
        val file = getKtFile(uri)
        val offset = positionToOffset(file, position)

        return analyze(file) {
            val scope = file.scopeContext(file.findElementAt(offset)!!)

            scope.scopes.flatMap { it.callables }
                .map { callable ->
                    CompletionItem().apply {
                        label = callable.name.asString()
                        kind = when (callable) {
                            is KaFunctionSymbol -> CompletionItemKind.Function
                            is KaPropertySymbol -> CompletionItemKind.Property
                            else -> CompletionItemKind.Value
                        }
                        detail = callable.returnType.render()
                    }
                }
        }
    }
}
```

### 1.4 Test It

```bash
# Start LSP
java -jar kmp-lsp.jar

# Send initialize request via stdio
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}
```

## Phase 2: KMP Project Import (Week 2)

### Goal
Parse Gradle KMP projects and create proper module structure with KLIB dependencies.

### 2.1 Gradle Tooling API Integration

```kotlin
// GradleImporter.kt
class GradleImporter {

    fun importProject(projectRoot: Path): KmpProject {
        val connection = GradleConnector.newConnector()
            .forProjectDirectory(projectRoot.toFile())
            .connect()

        return connection.use { conn ->
            val model = conn.getModel(KotlinMppModel::class.java)

            KmpProject(
                modules = model.sourceSets.map { sourceSet ->
                    KmpModule(
                        name = sourceSet.name,
                        platform = parsePlatform(sourceSet),
                        sourceRoots = sourceSet.sourceDirectories,
                        dependencies = resolveDependencies(sourceSet),
                        dependsOn = sourceSet.dependsOn.map { it.name }
                    )
                }
            )
        }
    }

    private fun resolveDependencies(sourceSet: KotlinSourceSet): List<LibraryDependency> {
        // Get resolved artifacts from Gradle
        return sourceSet.dependencies.map { dep ->
            LibraryDependency(
                name = dep.name,
                path = dep.artifactFile.toPath(),
                isKlib = dep.artifactFile.extension == "klib"
            )
        }
    }
}
```

### 2.2 Multi-Module Session Builder

```kotlin
// SessionFactory.kt (extended)
fun createKmpSession(project: KmpProject): StandaloneAnalysisAPISession {
    return buildStandaloneAnalysisAPISession {
        buildKtModuleProvider {
            val moduleMap = mutableMapOf<String, KtModule>()

            // First pass: create all library modules
            val allLibraries = project.modules.flatMap { it.dependencies }.distinctBy { it.path }
            val libraryModules = allLibraries.associate { lib ->
                lib.path to addModule(buildKtLibraryModule {
                    addBinaryRoot(lib.path)
                    platform = if (lib.isKlib) {
                        // KLIB - determine platform from path or metadata
                        determinePlatformFromKlib(lib.path)
                    } else {
                        JvmPlatforms.defaultJvmPlatform
                    }
                    libraryName = lib.name
                })
            }

            // Second pass: create source modules with dependencies
            for (module in project.modules.sortedByDependencyOrder()) {
                val ktModule = addModule(buildKtSourceModule {
                    module.sourceRoots.forEach { addSourceRoot(it) }
                    platform = module.platform
                    moduleName = module.name

                    // Add library dependencies
                    module.dependencies.forEach { dep ->
                        libraryModules[dep.path]?.let { addRegularDependency(it) }
                    }

                    // Add dependsOn (for expect/actual)
                    module.dependsOn.forEach { depName ->
                        moduleMap[depName]?.let { addDependsOnDependency(it) }
                    }
                })
                moduleMap[module.name] = ktModule
            }
        }
    }
}
```

### 2.3 Platform Detection from KLIB

```kotlin
// KlibPlatformDetector.kt
fun determinePlatformFromKlib(klibPath: Path): TargetPlatform {
    val library = resolveSingleFileKlib(klibPath)
    val manifest = library.manifestProperties

    return when {
        manifest.getProperty("native_targets")?.contains("ios") == true ->
            NativePlatforms.nativePlatformByTargets(listOf(KonanTarget.IOS_ARM64))
        manifest.getProperty("native_targets")?.contains("macos") == true ->
            NativePlatforms.nativePlatformByTargets(listOf(KonanTarget.MACOS_ARM64))
        manifest.getProperty("ir_provider") == "js" ->
            JsPlatforms.defaultJsPlatform
        else ->
            CommonPlatforms.defaultCommonPlatform
    }
}
```

## Phase 3: Full LSP Features (Week 3)

### 3.1 Hover Provider

```kotlin
// HoverProvider.kt
fun getHover(uri: String, position: Position): Hover? {
    val file = getKtFile(uri)
    val element = file.findElementAt(positionToOffset(file, position)) ?: return null

    return analyze(file) {
        val symbol = element.parent.symbol ?: return@analyze null

        Hover().apply {
            contents = Either.forLeft(MarkupContent().apply {
                kind = MarkupKind.MARKDOWN
                value = buildString {
                    append("```kotlin\n")
                    append(symbol.render())
                    append("\n```")

                    symbol.psi?.containingFile?.virtualFile?.path?.let {
                        append("\n\n*Defined in: $it*")
                    }
                }
            })
        }
    }
}
```

### 3.2 Go-to-Definition Provider

```kotlin
// DefinitionProvider.kt
fun getDefinition(uri: String, position: Position): List<Location> {
    val file = getKtFile(uri)
    val element = file.findElementAt(positionToOffset(file, position)) ?: return emptyList()

    return analyze(file) {
        val symbol = element.parent.symbol ?: return@analyze emptyList()

        symbol.psi?.let { psi ->
            listOf(Location().apply {
                this.uri = psi.containingFile.virtualFile.url
                range = psiToRange(psi)
            })
        } ?: emptyList()
    }
}
```

### 3.3 Diagnostics Provider

```kotlin
// DiagnosticsProvider.kt
fun getDiagnostics(uri: String): List<Diagnostic> {
    val file = getKtFile(uri)

    return analyze(file) {
        file.collectDiagnostics().map { diagnostic ->
            Diagnostic().apply {
                range = psiToRange(diagnostic.psi)
                message = diagnostic.defaultMessage
                severity = when (diagnostic.severity) {
                    KaSeverity.ERROR -> DiagnosticSeverity.Error
                    KaSeverity.WARNING -> DiagnosticSeverity.Warning
                    else -> DiagnosticSeverity.Information
                }
            }
        }
    }
}
```

## Phase 4: Incremental Updates (Week 4)

### 4.1 Document Sync

```kotlin
// DocumentManager.kt
class DocumentManager {
    private val documents = ConcurrentHashMap<String, TextDocument>()

    fun didOpen(params: DidOpenTextDocumentParams) {
        documents[params.textDocument.uri] = TextDocument(
            params.textDocument.uri,
            params.textDocument.text,
            params.textDocument.version
        )
        // Trigger diagnostics
        publishDiagnostics(params.textDocument.uri)
    }

    fun didChange(params: DidChangeTextDocumentParams) {
        documents.computeIfPresent(params.textDocument.uri) { _, doc ->
            doc.applyChanges(params.contentChanges)
        }
        // Re-analyze and publish diagnostics
        publishDiagnostics(params.textDocument.uri)
    }
}
```

### 4.2 Session Invalidation

```kotlin
// SessionManager.kt
class SessionManager {
    private var session: StandaloneAnalysisAPISession? = null
    private var lastModified = mutableMapOf<Path, Long>()

    fun getSession(): StandaloneAnalysisAPISession {
        if (shouldInvalidate()) {
            session?.let { Disposer.dispose(it.project) }
            session = createNewSession()
        }
        return session!!
    }

    private fun shouldInvalidate(): Boolean {
        // Check if source files or build.gradle.kts changed
        return sourceRoots.any {
            Files.getLastModifiedTime(it).toMillis() > lastModified[it] ?: 0
        }
    }
}
```

## Phase 5: Testing & Polish (Week 5)

### 5.1 Test Suite

```kotlin
// CompletionTest.kt
class CompletionTest {
    @Test
    fun `completion works for KLIB dependency`() {
        val project = createTestProject(
            sourceFiles = mapOf(
                "src/iosMain/kotlin/Main.kt" to """
                    import kotlinx.coroutines.flow.flow

                    fun test() {
                        flow {
                            emit/*caret*/
                        }
                    }
                """.trimIndent()
            ),
            dependencies = listOf(
                "kotlinx-coroutines-core-iosarm64.klib"
            )
        )

        val completions = server.completion(project.fileUri("Main.kt"), caretPosition)

        assertTrue(completions.any { it.label == "emit" })
    }
}
```

### 5.2 VS Code Extension

```json
// package.json
{
    "name": "kmp-kotlin",
    "displayName": "Kotlin Multiplatform",
    "description": "Kotlin LSP with full KMP support",
    "version": "0.1.0",
    "engines": { "vscode": "^1.80.0" },
    "activationEvents": ["onLanguage:kotlin"],
    "main": "./out/extension.js",
    "contributes": {
        "languages": [{
            "id": "kotlin",
            "extensions": [".kt", ".kts"]
        }]
    }
}
```

```typescript
// extension.ts
export function activate(context: vscode.ExtensionContext) {
    const serverPath = context.asAbsolutePath('server/kmp-lsp.jar');

    const serverOptions: ServerOptions = {
        run: { command: 'java', args: ['-jar', serverPath] },
        debug: { command: 'java', args: ['-jar', serverPath, '--debug'] }
    };

    const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: 'file', language: 'kotlin' }]
    };

    const client = new LanguageClient('kmpKotlin', 'KMP Kotlin', serverOptions, clientOptions);
    client.start();
}
```

## Timeline Summary

| Week | Phase | Deliverable |
|------|-------|-------------|
| 1 | Minimal LSP | Single-file completion with stdlib |
| 2 | KMP Import | Gradle KMP projects with KLIB deps |
| 3 | Full Features | Hover, go-to-def, diagnostics |
| 4 | Incremental | Document sync, session caching |
| 5 | Polish | Tests, VS Code extension |

## Key Files to Study

Before starting, read these files from JetBrains/kotlin:

1. **Session setup with KLIB:**
   - `analysis/analysis-api-standalone/tests/.../StandaloneSessionBuilderTest.kt`
   - Shows `buildKtLibraryModule` with `addBinaryRoot()` for KLIBs

2. **KLIB reading utilities:**
   - `native/analysis-api-klib-reader/src/.../readKlibDeclarationAddresses.kt`
   - `native/analysis-api-klib-reader/src/.../getSymbols.kt`

3. **Analysis API usage:**
   - `analysis/analysis-api/src/.../analyze.kt`
   - The `analyze(module) { }` pattern for all queries

4. **Project structure:**
   - `analysis/analysis-api/src/.../projectStructure/KaModule.kt`
   - `KaSourceModule`, `KaLibraryModule` interfaces

## Success Criteria

- [ ] `kotlinx.coroutines.flow.Flow` resolves in commonMain
- [ ] `Dispatchers.Default` resolves in iosMain with native KLIB
- [ ] `expect`/`actual` navigation works
- [ ] Completion shows platform-appropriate APIs
- [ ] No false errors on valid KMP code
- [ ] Works with VS Code

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Analysis API changes | Pin to specific Kotlin version |
| Complex Gradle projects | Start with simple KMP template |
| Performance | Lazy loading, caching, incremental |
| Missing Analysis API features | Fall back gracefully, log gaps |

## Open Questions

1. **Session lifecycle:** When to invalidate and recreate?
2. **Multi-project workspaces:** One session per project or shared?
3. **Build system:** Support Maven/other beyond Gradle?
4. **Native targets:** How to detect available targets?

---

## Next Steps

1. Create project skeleton with dependencies
2. Implement Phase 1 minimal LSP
3. Test with simple Kotlin file + stdlib
4. Iterate to Phase 2 with KMP project

**Let's build it.**
