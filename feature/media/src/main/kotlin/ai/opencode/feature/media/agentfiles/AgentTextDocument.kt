package ai.opencode.feature.media

import ai.opencode.core.files.AttachmentFileType
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle

/**
 * Highlighted lines for the code viewer, built off the main thread.
 *
 * Small files ([maxNumberedBytes], [maxNumberedLines], lines up to [maxNumberedLineBytes]) render
 * as numbered rows ([usesLazyTextView] false). Larger or long-line files format minified JSON, fold
 * paragraphs over [maxParagraphBytes], highlight only a [maxHighlightedCharacters] prefix, and
 * render as a plain lazy list of lines, so a multi-megabyte file lays out only what's on screen.
 *
 * Colours are applied when a line is drawn ([line]), so a theme change recolours it. Equality is
 * identity.
 */
@Stable
class AgentTextDocument private constructor(
    /** The formatted text the viewer shows. */
    val text: String,
    val lineCount: Int,
    /** The line a `path:42` reference pointed at, when it exists. */
    val focusLine: Int?,
    /** That line's UTF-16 range in [text] (lazy view only). */
    val focusRange: IntRange?,
    /** Whether the lazy (unnumbered) view shows it. */
    val usesLazyTextView: Boolean,
    private val lineStarts: IntArray,
    /** `start, end, kind` triples, sorted, non-overlapping. */
    private val tokens: IntArray,
) {
    /** Lines the viewer draws (a trailing newline adds none). */
    val displayLineCount: Int get() = lineStarts.size

    /** `[start, end)` of display line [index] in [text], without its newline. */
    fun lineRange(index: Int): IntRange {
        val start = lineStarts[index]
        val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else text.length.let { length ->
            if (length > start && text[length - 1] == '\n') length - 1 else length
        }
        return start until maxOf(start, end)
    }

    fun lineIndex(offset: Int): Int {
        val found = lineStarts.binarySearch(offset.coerceIn(0, text.length))
        return if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    }

    /** Whether display line [index] belongs to the focused line (lazy view). */
    fun isFocused(index: Int): Boolean {
        if (!usesLazyTextView) return index + 1 == focusLine
        val range = focusRange ?: return false
        val start = lineStarts[index]
        // Folded chunks of the line start inside it; an empty line is its own start.
        return start >= range.first && (start <= range.last || start == range.first)
    }

    /** Display line [index] coloured with [palette]. */
    fun line(index: Int, palette: SyntaxHighlighter.Palette): AnnotatedString {
        val range = lineRange(index)
        val start = range.first
        val end = range.last + 1
        val content = text.substring(start, end)
        if (tokens.isEmpty() || end <= start) return AnnotatedString(content)
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        var t = firstTokenEndingAfter(start)
        while (t < tokens.size / 3) {
            val tokenStart = tokens[t * 3]
            if (tokenStart >= end) break
            val tokenEnd = tokens[t * 3 + 1]
            val color: Color = palette.color(SyntaxHighlighter.TokenKind.entries[tokens[t * 3 + 2]])
            spans += AnnotatedString.Range(SpanStyle(color = color), maxOf(tokenStart, start) - start, minOf(tokenEnd, end) - start)
            t += 1
        }
        return AnnotatedString(content, spans)
    }

    private fun firstTokenEndingAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size / 3
        while (low < high) {
            val mid = (low + high) ushr 1
            if (tokens[mid * 3 + 1] <= offset) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** Highlighting is linear and capped to a prefix and line length shared with [SyntaxHighlighter]. */
        const val maxHighlightedCharacters = SyntaxHighlighter.maxHighlightedUTF8Bytes

        /** Files up to this size with short lines render numbered rows; larger files use the lazy view. */
        const val maxNumberedBytes = 64 * 1024
        const val maxNumberedLines = 1_500
        const val maxNumberedLineBytes = SyntaxHighlighter.maxHighlightedLineUTF8Bytes

        /** Maximum bytes per paragraph so no single line lays out megabytes at once. */
        const val maxParagraphBytes = 4_096
        val jsonExtensions: Set<String> = setOf("json", "jsonc", "jsonl")

        /** Prose, not code: keyword colouring would mark ordinary words. */
        val proseExtensions: Set<String> = setOf("md", "markdown", "mdx", "txt", "log", "csv", "tsv", "rtf")
        val markdownExtensions: Set<String> = setOf("md", "markdown", "mdx")

        fun of(source: String, fileName: String, focusLine: Int?): AgentTextDocument {
            val language = AttachmentFileType.fileExtension(fileName)
            val formattedBytes = formatSourceBytes(source.encodeToByteArray(), language)
            val formatted = formattedBytes.decodeToString()
            val scan = scan(formattedBytes)
            val clampedFocus = focusLine?.takeIf { it in 1..maxOf(scan.lineCount, 1) }
            val hint = language.ifEmpty { null }
            val highlights = language !in proseExtensions
            return if (formattedBytes.size <= maxNumberedBytes && scan.lineCount <= maxNumberedLines && scan.longestLineBytes <= maxNumberedLineBytes) {
                val tokens = if (highlights) collectTokens(formatted, SyntaxHighlighter.maxHighlightedUTF8Bytes, hint) else IntArray(0)
                AgentTextDocument(formatted, scan.lineCount, clampedFocus, null, false, lineStarts(formatted), tokens)
            } else {
                val focusRange = clampedFocus?.let { focusRange(formatted, it) }
                val tokens = if (highlights) collectTokens(formatted, maxHighlightedCharacters, hint) else IntArray(0)
                AgentTextDocument(formatted, scan.lineCount, clampedFocus, focusRange, true, lineStarts(formatted), tokens)
            }
        }

        /** Plain lines, never highlighted: numbered rows when small, the lazy view when large. */
        fun plainText(source: String): AgentTextDocument {
            val bytes = source.encodeToByteArray()
            val folded = foldLongLinesBytes(bytes, maxParagraphBytes)
            val formatted = if (folded === bytes) source else folded.decodeToString()
            val scan = scan(folded)
            val numbered = folded.size <= maxNumberedBytes && scan.lineCount <= maxNumberedLines && scan.longestLineBytes <= maxNumberedLineBytes
            return AgentTextDocument(formatted, scan.lineCount, null, null, !numbered, lineStarts(formatted), IntArray(0))
        }

        private fun collectTokens(source: String, maxBytes: Int, language: String?): IntArray {
            var tokens = IntArray(3 * 256)
            var count = 0
            SyntaxHighlighter.applyHighlighting(source, maxBytes, language) { start, end, kind ->
                if (count + 3 > tokens.size) tokens = tokens.copyOf(tokens.size * 2)
                tokens[count] = start
                tokens[count + 1] = end
                tokens[count + 2] = kind.ordinal
                count += 3
            }
            return tokens.copyOf(count)
        }

        /** Line starts for display; a trailing newline adds no empty last line. */
        private fun lineStarts(text: String): IntArray {
            var starts = IntArray(64)
            var count = 0
            fun add(value: Int) {
                if (count == starts.size) starts = starts.copyOf(starts.size * 2)
                starts[count++] = value
            }
            add(0)
            var index = text.indexOf('\n')
            while (index >= 0) {
                add(index + 1)
                index = text.indexOf('\n', index + 1)
            }
            if (count > 1 && starts[count - 1] == text.length) count -= 1
            return starts.copyOf(count)
        }

        /** Newlines (a trailing one adds no line) and the longest line in bytes. */
        data class Scan(val lineCount: Int, val longestLineBytes: Int)

        fun scan(bytes: ByteArray): Scan {
            if (bytes.isEmpty()) return Scan(1, 0)
            var newlines = 0
            var longest = 0
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == NEWLINE) {
                    longest = maxOf(longest, i - start)
                    newlines += 1
                    start = i + 1
                }
            }
            if (start < bytes.size) longest = maxOf(longest, bytes.size - start)
            val endsWithNewline = bytes[bytes.size - 1] == NEWLINE
            return Scan(maxOf(newlines + if (endsWithNewline) 0 else 1, 1), longest)
        }

        /**
         * Formats minified JSON into indented lines and folds any remaining multi-kilobyte lines
         * so every paragraph lays out in bounded time.
         */
        fun formatSource(source: String, language: String): String {
            val bytes = source.encodeToByteArray()
            val formatted = formatSourceBytes(bytes, language)
            return if (formatted === bytes) source else formatted.decodeToString()
        }

        private fun formatSourceBytes(source: ByteArray, language: String): ByteArray {
            val scan = scan(source)
            val isMinifiedJSON = language in jsonExtensions &&
                (scan.longestLineBytes > maxNumberedLineBytes || (scan.lineCount <= 4 && scan.longestLineBytes > 200))
            if (isMinifiedJSON) return formatJSONBytes(source, maxParagraphBytes)
            return if (scan.longestLineBytes > maxParagraphBytes) foldLongLinesBytes(source, maxParagraphBytes) else source
        }

        /** Single-pass UTF-8 JSON pretty-printer that builds no object tree. */
        fun formatJSON(source: String, maxLineBytes: Int = maxParagraphBytes): String {
            if (source.isEmpty()) return source
            return formatJSONBytes(source.encodeToByteArray(), maxLineBytes).decodeToString()
        }

        private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

        private fun formatJSONBytes(src: ByteArray, maxLineBytes: Int): ByteArray {
            val count = src.size
            if (count == 0) return src
            var dst = ByteArray(maxOf(count * 2 + 256, 4096))
            var outPos = 0
            var lineBytes = 0
            val maxIndent = 64
            var inString = false
            var depth = 0
            var i = 0

            fun reserve(needed: Int) {
                if (outPos + needed <= dst.size) return
                var next = dst.size * 2
                while (outPos + needed > next) next *= 2
                dst = dst.copyOf(next)
            }

            fun put(byte: Byte) {
                dst[outPos++] = byte
            }

            fun writeNewlineAndIndent() {
                val indent = minOf(depth * 2, maxIndent)
                put(NEWLINE)
                if (indent > 0) {
                    dst.fill(SPACE, outPos, outPos + indent)
                    outPos += indent
                }
                lineBytes = indent
            }

            fun indexOf(byte: Byte, from: Int, length: Int): Int {
                for (k in from until from + length) if (src[k] == byte) return k
                return -1
            }

            while (i < count) {
                reserve(72)
                val byte = src[i]
                if (inString) {
                    if (maxLineBytes > 0 && lineBytes >= maxLineBytes && byte != QUOTE && byte != NEWLINE && !isContinuation(byte)) {
                        put(NEWLINE)
                        lineBytes = 0
                    }
                    val start = i
                    var cursor = i
                    while (cursor < count) {
                        val b = src[cursor]
                        if (b == QUOTE || b == BACKSLASH || b == NEWLINE) break
                        if (maxLineBytes > 0 && lineBytes + (cursor - start) >= maxLineBytes && !isContinuation(b)) break
                        cursor += 1
                    }
                    if (cursor > start) {
                        val span = cursor - start
                        reserve(span + 8)
                        System.arraycopy(src, start, dst, outPos, span)
                        outPos += span
                        lineBytes += span
                        i = cursor
                        if (i < count && maxLineBytes > 0 && lineBytes >= maxLineBytes &&
                            src[i] != QUOTE && src[i] != BACKSLASH && src[i] != NEWLINE && !isContinuation(src[i])
                        ) {
                            put(NEWLINE)
                            lineBytes = 0
                        }
                        continue
                    }
                    put(byte)
                    lineBytes = if (byte == NEWLINE) 0 else lineBytes + 1
                    if (byte == BACKSLASH && i + 1 < count) {
                        i += 1
                        val escaped = src[i]
                        put(escaped)
                        lineBytes = if (escaped == NEWLINE) 0 else lineBytes + 1
                    } else if (byte == QUOTE) {
                        inString = false
                    }
                    i += 1
                    continue
                }

                when (byte) {
                    QUOTE -> {
                        val remaining = count - (i + 1)
                        val allowed = if (maxLineBytes > 0) minOf(remaining, maxOf(maxLineBytes - lineBytes, 64)) else remaining
                        if (allowed > 0) {
                            val quoteHit = indexOf(QUOTE, i + 1, allowed)
                            if (quoteHit >= 0) {
                                val innerLen = quoteHit - (i + 1)
                                if (indexOf(BACKSLASH, i + 1, innerLen) < 0 && indexOf(NEWLINE, i + 1, innerLen) < 0) {
                                    val span = innerLen + 2
                                    reserve(span + 8)
                                    System.arraycopy(src, i, dst, outPos, span)
                                    outPos += span
                                    lineBytes += span
                                    i += span
                                    continue
                                }
                            }
                        }
                        inString = true
                        put(QUOTE)
                        lineBytes += 1
                    }
                    SPACE, TAB, CARRIAGE_RETURN -> Unit
                    NEWLINE -> {
                        if (depth == 0 && outPos > 0 && dst[outPos - 1] != NEWLINE) {
                            put(NEWLINE)
                            lineBytes = 0
                        }
                    }
                    OPEN_BRACE, OPEN_BRACKET -> {
                        put(byte)
                        lineBytes += 1
                        val closing = if (byte == OPEN_BRACE) CLOSE_BRACE else CLOSE_BRACKET
                        var next = i + 1
                        while (next < count && (src[next] == SPACE || src[next] == TAB || src[next] == NEWLINE || src[next] == CARRIAGE_RETURN)) {
                            next += 1
                        }
                        if (next < count && src[next] == closing) {
                            put(closing)
                            lineBytes += 1
                            i = next
                        } else {
                            depth += 1
                            writeNewlineAndIndent()
                        }
                    }
                    CLOSE_BRACE, CLOSE_BRACKET -> {
                        depth = maxOf(0, depth - 1)
                        writeNewlineAndIndent()
                        put(byte)
                        lineBytes += 1
                    }
                    COMMA -> {
                        put(COMMA)
                        writeNewlineAndIndent()
                    }
                    COLON -> {
                        put(COLON)
                        put(SPACE)
                        lineBytes += 2
                    }
                    else -> {
                        if (maxLineBytes > 0 && lineBytes >= maxLineBytes && !isContinuation(byte)) {
                            put(NEWLINE)
                            lineBytes = 0
                        }
                        put(byte)
                        lineBytes += 1
                    }
                }
                i += 1
            }
            return dst.copyOf(outPos)
        }

        /**
         * Splits lines longer than [maxLineBytes] on UTF-8 scalar boundaries so no single
         * paragraph lays out megabytes at once.
         */
        fun foldLongLines(source: String, maxLineBytes: Int = maxParagraphBytes): String {
            val bytes = source.encodeToByteArray()
            val folded = foldLongLinesBytes(bytes, maxLineBytes)
            return if (folded === bytes) source else folded.decodeToString()
        }

        /** Returns [src] itself when nothing needs folding. */
        private fun foldLongLinesBytes(src: ByteArray, maxLineBytes: Int): ByteArray {
            if (maxLineBytes <= 0 || scan(src).longestLineBytes <= maxLineBytes) return src
            val count = src.size
            val dst = ByteArray(count + count / maxLineBytes + 16)
            var outPos = 0
            var pos = 0
            while (pos < count) {
                var lineEnd = pos
                while (lineEnd < count && src[lineEnd] != NEWLINE) lineEnd += 1
                while (lineEnd - pos > maxLineBytes) {
                    var split = pos + maxLineBytes
                    while (split > pos && isContinuation(src[split])) split -= 1
                    if (split == pos) split = pos + maxLineBytes
                    val chunk = split - pos
                    System.arraycopy(src, pos, dst, outPos, chunk)
                    outPos += chunk
                    dst[outPos++] = NEWLINE
                    pos = split
                }
                val copyLen = if (lineEnd < count) lineEnd - pos + 1 else lineEnd - pos
                if (copyLen > 0) {
                    System.arraycopy(src, pos, dst, outPos, copyLen)
                    outPos += copyLen
                    pos += copyLen
                }
            }
            return dst.copyOf(outPos)
        }

        /** Line [targetLine]'s UTF-16 range in [text], without its newline. */
        private fun focusRange(text: String, targetLine: Int): IntRange? {
            if (targetLine < 1) return null
            var currentLine = 1
            var start = 0
            while (true) {
                val newline = text.indexOf('\n', start)
                if (currentLine == targetLine) return start until (if (newline < 0) text.length else newline)
                if (newline < 0) return null
                start = newline + 1
                currentLine += 1
            }
        }

        private const val NEWLINE: Byte = 0x0A
        private const val SPACE: Byte = 0x20
        private const val TAB: Byte = 0x09
        private const val CARRIAGE_RETURN: Byte = 0x0D
        private const val QUOTE: Byte = 0x22
        private const val BACKSLASH: Byte = 0x5C
        private const val OPEN_BRACE: Byte = 0x7B
        private const val CLOSE_BRACE: Byte = 0x7D
        private const val OPEN_BRACKET: Byte = 0x5B
        private const val CLOSE_BRACKET: Byte = 0x5D
        private const val COMMA: Byte = 0x2C
        private const val COLON: Byte = 0x3A
    }
}
