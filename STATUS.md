# KMP LSP - Project Status

## Overview

This is a Kotlin Language Server with Kotlin Multiplatform (KMP) support. It provides basic IDE features through the Language Server Protocol.

**Current State: Functional Prototype**

---

## Implemented Features ✅

| Feature | Description | Quality |
|---------|-------------|---------|
| **Code Completion** | Keywords, stdlib, project symbols, KLIB symbols | Good |
| **Hover** | Type info, KDoc, expect/actual relationships | Good |
| **Go-to-Definition** | Same-file and cross-file navigation | Good |
| **Expect/Actual Navigation** | Jump between expect ↔ actual declarations | Good |
| **Diagnostics** | Compiler errors and warnings | Good |
| **Project Import** | Gradle project structure detection | Partial |
| **KLIB Reading** | Extract symbols from Kotlin libraries | Partial |
| **Symbol Indexing** | Cross-file symbol lookup | Good |

---

## Missing Features ❌

### Core LSP Features (Not Implemented)

| Feature | Description | Difficulty |
|---------|-------------|------------|
| **Find References** | Find all usages of a symbol | Medium |
| **Workspace Symbols** | Global symbol search (Cmd+T) | Low |
| **Document Symbols** | File outline/breadcrumb | Low |
| **Signature Help** | Parameter hints during function calls | Medium |
| **Code Actions** | Quick fixes ("import X", "add return") | Medium |
| **Rename** | Safe symbol renaming across files | Medium-High |
| **Formatting** | Code formatting | Low |
| **Semantic Tokens** | Enhanced syntax highlighting | Medium |
| **Call Hierarchy** | Incoming/outgoing calls | Medium |
| **Type Hierarchy** | Supertypes/subtypes view | Medium |

### Analysis Limitations

| Limitation | Impact | Difficulty to Fix |
|------------|--------|-------------------|
| **JVM-Only Analysis** | Native/JS files get no type resolution | **Very High** |
| **Single-File Analysis** | No cross-module type checking | High |
| **No Incremental Analysis** | Full re-analysis on every change | **Very High** |
| **KLIB Symbols Without Types** | Completions show names only, not signatures | High |

---

## Technical Debt & Architecture Issues

### 1. JVM-Only Compiler Environment

```kotlin
// Currently hardcoded in SessionManager.kt
KotlinCoreEnvironment.createForProduction(
    configFiles = EnvironmentConfigFiles.JVM_CONFIG_FILES  // Always JVM!
)
```

**Impact**: Native source sets (iosMain, macosMain, etc.) and JS source sets get PSI parsing but no platform-specific type resolution. Types like `platform.posix.*`, `kotlinx.cinterop.*`, or JS DOM types won't resolve.

### 2. Full-File Analysis

```kotlin
// Every analysis is full-file via TopDownAnalyzerFacadeForJVM
TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(...)
```

**Impact**: No incremental analysis. Every keystroke triggers complete re-analysis of the file, which degrades performance on large files.

### 3. Limited KLIB Integration

KLIBs are read for symbol names but not full type information:
- Shows: `runBlocking`
- Should show: `runBlocking(context: CoroutineContext, block: suspend () -> T): T`

---

## Viability Assessment

### Achievable Improvements (Low-Medium Effort)

These can be added without architectural changes:

| Feature | Approach | Effort |
|---------|----------|--------|
| Workspace Symbols | Expose existing IndexManager via LSP | 1-2 days |
| Document Symbols | Walk PSI tree, return outline | 1-2 days |
| Find References | Extend IndexManager to track usages | 3-5 days |
| Formatting | Integrate ktfmt or ktlint | 1-2 days |
| Signature Help | Query BindingContext at call sites | 2-3 days |
| Code Actions | Wire compiler suggestions to LSP | 3-5 days |

### Difficult Improvements (High Effort)

These require significant work but are achievable:

| Feature | Challenge | Effort |
|---------|-----------|--------|
| Rename Refactoring | Requires reliable Find References first | 1-2 weeks |
| Full KLIB Types | Deeper protobuf parsing of IR metadata | 1-2 weeks |
| Semantic Tokens | Map resolved types to token modifiers | 1 week |

### Potentially Blocking Issues ⚠️

These may require **architectural changes** or are **extremely difficult**:

#### 1. Platform-Specific Analysis (Native/JS)

**Problem**: The current architecture uses `TopDownAnalyzerFacadeForJVM` exclusively. There is no equivalent simple API for Native or JS targets.

**Options**:
- Use Kotlin Analysis API (analysis-api-standalone) - major rewrite
- Maintain separate compiler environments per platform - complex
- Accept JVM-only analysis as a limitation - reduced functionality

**Verdict**: **Very difficult without Analysis API migration**

#### 2. True Incremental Analysis

**Problem**: The Kotlin compiler's public API doesn't expose incremental analysis. Each analysis is full-file.

**Options**:
- Use Kotlin Analysis API which has incremental support - major rewrite
- Build custom incremental cache on top of current analysis - very complex
- Accept full-file analysis with aggressive caching - performance trade-off

**Verdict**: **Very difficult without Analysis API migration**

#### 3. Multi-Module Type Resolution

**Problem**: Currently each file is analyzed in isolation. Proper multi-module resolution requires:
- Module descriptors with dependency relationships
- Shared analysis sessions across modules
- Proper source root configuration per module

**Options**:
- Manual module descriptor setup with current API - complex and fragile
- Use Analysis API which handles modules properly - major rewrite
- Rely on indexing for navigation, accept limited type checking - reduced functionality

**Verdict**: **Difficult but possible with significant effort**

---

## The Analysis API Question

Many of the "very difficult" limitations could be addressed by migrating from the current compiler-based approach to the **Kotlin Analysis API** (`analysis-api-standalone`).

### What Analysis API Provides
- ✅ Incremental analysis
- ✅ Multi-platform support (JVM, Native, JS)
- ✅ Proper module handling
- ✅ KLIB integration
- ✅ Modern, supported API

### Why We Didn't Use It
- Different architecture (session-based vs environment-based)
- Less documentation and examples available
- Would require significant rewrite of analysis layer

### Migration Effort
Estimated 2-4 weeks to migrate analysis layer to Analysis API, affecting:
- `SessionManager.kt` - complete rewrite
- `CompletionProvider.kt` - adapt to Analysis API types
- `HoverProvider.kt` - adapt to Analysis API types
- `DefinitionProvider.kt` - adapt to Analysis API types
- `DiagnosticsProvider.kt` - adapt to Analysis API types

---

## Recommended Path Forward

### Option A: Incremental Enhancement (Current Architecture)

Keep current architecture, add missing features that don't require Analysis API:
1. Add Workspace/Document Symbols
2. Add Find References
3. Add Signature Help
4. Add Code Actions
5. Add Formatting
6. Improve KLIB type extraction

**Pros**: Lower risk, incremental progress
**Cons**: Can't fix platform-specific analysis or incremental analysis
**Best for**: Quick wins, limited scope projects

### Option B: Analysis API Migration

Rewrite analysis layer using Kotlin Analysis API:
1. Replace SessionManager with Analysis API session management
2. Adapt all providers to Analysis API types
3. Get incremental analysis, multi-platform support "for free"

**Pros**: Solves fundamental limitations, future-proof
**Cons**: Significant effort, risk of regressions
**Best for**: Long-term viability, production use

### Option C: Hybrid Approach

1. First, add easy features (Symbols, References, Formatting)
2. Then, evaluate Analysis API migration based on real-world usage
3. Migrate incrementally if needed

**Pros**: Balanced approach, gathers feedback
**Cons**: May do work twice if migration happens
**Best for**: Uncertain requirements, learning project

---

## Conclusion

This LSP is a **functional prototype** suitable for:
- ✅ Learning and experimentation
- ✅ Small KMP projects
- ✅ Basic Kotlin editing
- ✅ Foundation for further development

It is **not production-ready** for:
- ❌ Large commercial projects
- ❌ Full IDE replacement
- ❌ Native/JS development (analysis limitations)
- ❌ Performance-critical workflows

The most significant blocking issue is **platform-specific analysis** - without Analysis API migration, Native and JS source sets will never have proper type resolution. For JVM-only or common-code KMP projects, the current implementation may be sufficient.
