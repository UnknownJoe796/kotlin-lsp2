// by Claude - Code Formatting provider
package org.kotlinlsp.analysis

import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.jetbrains.kotlin.psi.*
import org.slf4j.LoggerFactory

/**
 * Provides code formatting functionality.
 * Implements basic Kotlin code formatting rules.
 */
class FormattingProvider(private val analysisSession: AnalysisSession) {

    private val logger = LoggerFactory.getLogger(FormattingProvider::class.java)

    fun format(uri: String, options: FormattingOptions): List<TextEdit> {
        logger.debug("Formatting requested for: $uri")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()
        val originalText = ktFile.text

        val formattedText = formatKotlinCode(originalText, options)

        if (formattedText == originalText) {
            return emptyList()
        }

        // Return a single edit that replaces the entire document
        val lastLine = originalText.count { it == '\n' }
        val lastLineLength = originalText.substringAfterLast('\n', originalText).length

        return listOf(
            TextEdit(
                Range(Position(0, 0), Position(lastLine, lastLineLength)),
                formattedText
            )
        )
    }

    fun formatRange(uri: String, range: Range, options: FormattingOptions): List<TextEdit> {
        logger.debug("Range formatting requested for: $uri")

        val ktFile = analysisSession.getKtFile(uri) ?: return emptyList()
        val text = ktFile.text

        val startOffset = positionToOffset(text, range.start)
        val endOffset = positionToOffset(text, range.end)
        if (startOffset < 0 || endOffset < 0) return emptyList()

        // Find the complete lines containing the range
        val lineStart = text.lastIndexOf('\n', startOffset - 1) + 1
        val lineEnd = text.indexOf('\n', endOffset).let { if (it < 0) text.length else it }

        val rangeText = text.substring(lineStart, lineEnd)
        val formattedRange = formatKotlinCode(rangeText, options)

        if (formattedRange == rangeText) {
            return emptyList()
        }

        return listOf(
            TextEdit(
                Range(
                    offsetToPosition(lineStart, text),
                    offsetToPosition(lineEnd, text)
                ),
                formattedRange
            )
        )
    }

    private fun formatKotlinCode(code: String, options: FormattingOptions): String {
        val indentChar = if (options.isInsertSpaces) " " else "\t"
        val indentSize = options.tabSize

        val lines = code.lines()
        val result = StringBuilder()
        var currentIndent = 0
        var inMultilineString = false
        var inComment = false

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Track multiline strings
            val rawStringStarts = trimmed.count { it == '"' && trimmed.indexOf("\"\"\"") >= 0 }
            if (trimmed.contains("\"\"\"")) {
                val count = "\"\"\"".toRegex().findAll(trimmed).count()
                if (count % 2 == 1) {
                    inMultilineString = !inMultilineString
                }
            }

            // Track block comments
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inComment = true
            }
            if (trimmed.contains("*/")) {
                inComment = false
            }

            // Skip formatting inside multiline strings
            if (inMultilineString && !trimmed.contains("\"\"\"")) {
                result.append(line)
                if (index < lines.lastIndex) result.append("\n")
                continue
            }

            // Decrease indent for closing braces/brackets
            if (trimmed.startsWith("}") || trimmed.startsWith(")") || trimmed.startsWith("]")) {
                currentIndent = maxOf(0, currentIndent - 1)
            }

            // Handle else, catch, finally on the same line as closing brace
            if (trimmed.startsWith("} else") || trimmed.startsWith("} catch") || trimmed.startsWith("} finally")) {
                currentIndent = maxOf(0, currentIndent)
            }

            // Decrease indent for when branches (entry, not the case itself)
            if (trimmed.startsWith("->") && !trimmed.contains("{")) {
                // Keep same indent
            }

            // Build the formatted line
            val formattedLine = if (trimmed.isEmpty()) {
                "" // Empty lines have no indentation
            } else if (inComment && !trimmed.startsWith("/*") && !trimmed.startsWith("*/")) {
                // Inside block comment - preserve relative indentation with base indent
                val baseIndent = indentChar.repeat(currentIndent * indentSize)
                "$baseIndent * ${trimmed.removePrefix("*").trim()}"
            } else {
                val baseIndent = indentChar.repeat(currentIndent * indentSize)
                "$baseIndent$trimmed"
            }

            result.append(formattedLine)

            // Add newline except for last line
            if (index < lines.lastIndex) {
                result.append("\n")
            }

            // Increase indent for opening braces/brackets
            val openBraces = trimmed.count { it == '{' }
            val closeBraces = trimmed.count { it == '}' }
            val openParens = if (trimmed.endsWith("(") || (trimmed.contains("(") && !trimmed.contains(")"))) 1 else 0
            val closeParens = if (trimmed.startsWith(")")) 1 else 0

            currentIndent += openBraces - closeBraces + openParens - closeParens
            currentIndent = maxOf(0, currentIndent)

            // Handle lambda on same line
            if (trimmed.contains("->") && trimmed.endsWith("{")) {
                // Already handled by brace counting
            }
        }

        // Apply additional formatting rules
        var formatted = result.toString()
        formatted = normalizeSpacing(formatted)
        formatted = normalizeBlankLines(formatted)
        formatted = ensureTrailingNewline(formatted)

        return formatted
    }

    private fun normalizeSpacing(code: String): String {
        var result = code

        // Normalize spaces around operators (but not inside strings)
        result = normalizeOperatorSpacing(result)

        // Normalize spaces after commas
        result = result.replace(Regex(",(?!\\s)"), ", ")

        // Normalize spaces after colons in type declarations
        result = result.replace(Regex(":\\s{2,}"), ": ")

        // Normalize spaces around = in assignments
        result = normalizeAssignmentSpacing(result)

        // Remove trailing whitespace
        result = result.lines().joinToString("\n") { it.trimEnd() }

        return result
    }

    private fun normalizeOperatorSpacing(code: String): String {
        // This is a simplified version - a full implementation would
        // need to handle string literals properly
        var result = code

        // Add space around binary operators
        val binaryOps = listOf("==", "!=", "<=", ">=", "&&", "||", "->", "+=", "-=", "*=", "/=")
        for (op in binaryOps) {
            result = result.replace(Regex("(?<!\\s)${Regex.escape(op)}(?!\\s)")) { match ->
                " ${match.value} "
            }
        }

        return result
    }

    private fun normalizeAssignmentSpacing(code: String): String {
        // Normalize spaces around = but not == or other compound operators
        return code.replace(Regex("(?<![=!<>+\\-*/])=(?!=)")) { match ->
            val before = match.range.first > 0 && code[match.range.first - 1].isWhitespace()
            val after = match.range.last < code.lastIndex && code[match.range.last + 1].isWhitespace()

            when {
                !before && !after -> " = "
                !before -> " ="
                !after -> "= "
                else -> "="
            }
        }
    }

    private fun normalizeBlankLines(code: String): String {
        val lines = code.lines()
        val result = mutableListOf<String>()
        var consecutiveBlankLines = 0

        for (line in lines) {
            if (line.isBlank()) {
                consecutiveBlankLines++
                // Allow at most 1 blank line
                if (consecutiveBlankLines <= 1) {
                    result.add(line)
                }
            } else {
                consecutiveBlankLines = 0
                result.add(line)
            }
        }

        // Remove leading blank lines
        while (result.isNotEmpty() && result.first().isBlank()) {
            result.removeAt(0)
        }

        // Remove trailing blank lines (we'll add exactly one at the end)
        while (result.isNotEmpty() && result.last().isBlank()) {
            result.removeAt(result.lastIndex)
        }

        return result.joinToString("\n")
    }

    private fun ensureTrailingNewline(code: String): String {
        return if (code.endsWith("\n")) code else "$code\n"
    }

    private fun positionToOffset(text: String, position: Position): Int {
        var line = 0
        var offset = 0

        for ((i, char) in text.withIndex()) {
            if (line == position.line) {
                val col = i - offset
                if (col >= position.character) {
                    return i
                }
            }
            if (char == '\n') {
                if (line == position.line) {
                    return i
                }
                line++
                offset = i + 1
            }
        }

        return if (line == position.line) text.length else -1
    }

    private fun offsetToPosition(offset: Int, text: String): Position {
        var line = 0
        var column = 0

        for (i in 0 until minOf(offset, text.length)) {
            if (text[i] == '\n') {
                line++
                column = 0
            } else {
                column++
            }
        }

        return Position(line, column)
    }
}
