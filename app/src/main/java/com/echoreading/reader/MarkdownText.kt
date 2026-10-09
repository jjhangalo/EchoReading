package com.echoreading.reader

enum class MarkdownBlockKind { PARAGRAPH, HEADING, BULLET, NUMBERED, QUOTE, CODE, RULE }
enum class MarkdownMark { BOLD, ITALIC, CODE, STRIKE, LINK }

data class MarkdownSpan(val start: Int, val end: Int, val mark: MarkdownMark)
data class MarkdownVisualRange(
    val kind: MarkdownBlockKind,
    val start: Int,
    val end: Int,
    val level: Int = 0,
)

data class MarkdownDocument(
    val isMarkdown: Boolean,
    val spokenText: String,
    val visualText: String,
    val visualSpans: List<MarkdownSpan>,
    val visualRanges: List<MarkdownVisualRange>,
    private val speechOffsets: IntArray,
    private val visualOffsets: IntArray,
    val sourceText: String,
) {
    fun readingChunks(maxChars: Int = 280): List<ReadingChunk> {
        val chunks = ReadingChunks.split(spokenText, maxChars)
        if (!isMarkdown) return chunks
        return chunks.map { chunk ->
            chunk.copy(start = speechOffsets.getOrElse(chunk.start) { 0 })
        }
    }

    fun originalToVisual(offset: Int): Int {
        if (!isMarkdown) return offset.coerceIn(0, sourceText.length)
        val target = offset.coerceIn(0, sourceText.length)
        var low = 0
        var high = visualOffsets.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (visualOffsets[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    fun visualToOriginal(offset: Int): Int {
        if (!isMarkdown) return offset.coerceIn(0, sourceText.length)
        return visualOffsets.getOrElse(offset.coerceAtLeast(0)) { sourceText.length }
    }
}

// ponytail: supports common Markdown constructs; use a CommonMark parser if full syntax coverage is needed.
object MarkdownText {
    private data class SourceLine(val text: String, val start: Int)
    private data class Inline(
        val text: String,
        val offsets: List<Int>,
        val spans: List<MarkdownSpan>,
        val formatted: Boolean,
    )

    private val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+|$)")
    private val bullet = Regex("^ {0,3}[-+*][ \\t]+")
    private val numbered = Regex("^ {0,3}(\\d{1,9})[.)][ \\t]+")
    private val quote = Regex("^ {0,3}(?:>[ \\t]?)+")
    private val trailingHashes = Regex("[ \\t]+#+[ \\t]*$")

    fun parse(source: String): MarkdownDocument {
        val lines = splitLines(source)
        val speech = StringBuilder()
        val visual = StringBuilder()
        val speechOffsets = mutableListOf<Int>()
        val visualOffsets = mutableListOf<Int>()
        val visualSpans = mutableListOf<MarkdownSpan>()
        val visualRanges = mutableListOf<MarkdownVisualRange>()
        var recognized = false
        var fenceChar = ' '
        var fenceLength = 0
        var index = 0

        fun append(inline: Inline, line: SourceLine, kind: MarkdownBlockKind, level: Int = 0,
            marker: String = ""
        ) {
            val lineEnd = line.start + line.text.length
            speech.append(inline.text).append('\n')
            speechOffsets.addAll(inline.offsets)
            speechOffsets.add(lineEnd)

            val markerStart = line.start + line.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (kind == MarkdownBlockKind.BULLET || kind == MarkdownBlockKind.NUMBERED ||
                kind == MarkdownBlockKind.QUOTE
            ) {
                for (sourceIndex in line.start until markerStart) {
                    visual.append(source[sourceIndex])
                    visualOffsets.add(sourceIndex)
                }
            }
            val displayMarker = when (kind) {
                MarkdownBlockKind.BULLET -> "• "
                MarkdownBlockKind.NUMBERED -> "$marker "
                MarkdownBlockKind.QUOTE -> "│ "
                else -> ""
            }
            displayMarker.forEachIndexed { markerIndex, character ->
                visual.append(character)
                visualOffsets.add((markerStart + markerIndex).coerceAtMost(lineEnd))
            }
            val bodyStart = visual.length
            visual.append(inline.text)
            visualOffsets.addAll(inline.offsets)
            visualSpans.addAll(inline.spans.map {
                it.copy(start = it.start + bodyStart, end = it.end + bodyStart)
            })
            if (visual.length > bodyStart) {
                visualRanges += MarkdownVisualRange(kind, bodyStart, visual.length, level)
            }
            visual.append('\n')
            visualOffsets.add(lineEnd)
        }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.text.trimStart(' ')
            val indentation = line.text.length - trimmed.length
            val fence = if (indentation <= 3 && trimmed.length >= 3 && trimmed[0] in "`~") {
                trimmed.takeWhile { it == trimmed[0] }.length.takeIf { it >= 3 }
            } else null

            if (fenceLength > 0) {
                if (fence != null && trimmed[0] == fenceChar && fence >= fenceLength &&
                    trimmed.drop(fence).isBlank()
                ) {
                    fenceLength = 0
                } else {
                    val inline = Inline(line.text, line.text.indices.map { line.start + it }, emptyList(), false)
                    append(inline, line, MarkdownBlockKind.CODE)
                }
                index++
                continue
            }

            val closedLater = fence != null && lines.drop(index + 1).any { candidate ->
                val closing = candidate.text.trim()
                closing.length >= fence && closing.all { it == trimmed[0] }
            }
            if (fence != null && closedLater) {
                recognized = true
                fenceChar = trimmed[0]
                fenceLength = fence
                index++
                continue
            }

            if (line.text.isBlank()) {
                speech.append('\n')
                speechOffsets.add(line.start + line.text.length)
                visual.append('\n')
                visualOffsets.add(line.start + line.text.length)
                index++
                continue
            }

            val next = lines.getOrNull(index + 1)?.text?.trim()
            val setextLevel = when {
                next != null && next.length >= 1 && next.all { it == '=' } -> 1
                next != null && next.length >= 1 && next.all { it == '-' } -> 2
                else -> 0
            }
            if (setextLevel > 0 && indentation <= 3 &&
                heading.find(line.text)?.let { line.text.substring(it.value.length).isNotBlank() } != true &&
                bullet.find(line.text) == null && numbered.find(line.text) == null && quote.find(line.text) == null
            ) {
                val inline = parseInline(line.text.trim(), line.start + line.text.indexOfFirst { !it.isWhitespace() })
                append(inline, line, MarkdownBlockKind.HEADING, setextLevel)
                recognized = true
                index += 2
                continue
            }

            val ruleText = trimmed.filterNot { it.isWhitespace() }
            if (indentation <= 3 && ruleText.length >= 3 && ruleText[0] in "-*_" &&
                ruleText.all { it == ruleText[0] }
            ) {
                val start = visual.length
                line.text.forEachIndexed { sourceIndex, character ->
                    if (character == ruleText[0]) {
                        visual.append('─')
                        visualOffsets.add(line.start + sourceIndex)
                    }
                }
                visualRanges += MarkdownVisualRange(MarkdownBlockKind.RULE, start, visual.length)
                visual.append('\n')
                visualOffsets.add(line.start + line.text.length)
                recognized = true
                index++
                continue
            }

            val headingMatch = heading.find(line.text)?.takeIf {
                line.text.substring(it.value.length).isNotBlank()
            }
            val bulletMatch = bullet.find(line.text)
            val numberMatch = numbered.find(line.text)
            val quoteMatch = quote.find(line.text)
            val kind: MarkdownBlockKind
            val level: Int
            val marker: String
            val prefix: Int
            when {
                headingMatch != null -> {
                    kind = MarkdownBlockKind.HEADING
                    level = headingMatch.groupValues[1].length
                    marker = ""
                    prefix = headingMatch.value.length
                }
                bulletMatch != null -> {
                    kind = MarkdownBlockKind.BULLET
                    level = 0
                    marker = ""
                    prefix = bulletMatch.value.length
                }
                numberMatch != null -> {
                    kind = MarkdownBlockKind.NUMBERED
                    level = 0
                    marker = numberMatch.groupValues[1] + "."
                    prefix = numberMatch.value.length
                }
                quoteMatch != null -> {
                    kind = MarkdownBlockKind.QUOTE
                    level = 0
                    marker = ""
                    prefix = quoteMatch.value.length
                }
                else -> {
                    kind = MarkdownBlockKind.PARAGRAPH
                    level = 0
                    marker = ""
                    prefix = 0
                }
            }
            var body = line.text.substring(prefix)
            if (kind == MarkdownBlockKind.HEADING) body = body.replace(trailingHashes, "")
            val bodyStart = line.start + prefix
            val inline = parseInline(body, bodyStart)
            append(inline, line, kind, level, marker)
            recognized = recognized || kind != MarkdownBlockKind.PARAGRAPH || inline.formatted
            index++
        }

        if (!recognized) return MarkdownDocument(
            false, source, source, emptyList(), emptyList(), IntArray(0), IntArray(0), source
        )
        val spoken = speech.toString().trimEnd()
        val displayed = visual.toString().removeSuffix("\n")
        return MarkdownDocument(
            true, spoken, displayed, visualSpans, visualRanges,
            speechOffsets.take(spoken.length).toIntArray(),
            visualOffsets.take(displayed.length).toIntArray(), source,
        )
    }

    private fun splitLines(source: String): List<SourceLine> {
        val result = mutableListOf<SourceLine>()
        var start = 0
        var index = 0
        while (index < source.length) {
            if (source[index] == '\n' || source[index] == '\r') {
                result += SourceLine(source.substring(start, index), start)
                if (source[index] == '\r' && source.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
            index++
        }
        result += SourceLine(source.substring(start), start)
        return result
    }

    private fun parseInline(source: String, base: Int, depth: Int = 0): Inline {
        if (depth > 8) return Inline(source, source.indices.map { base + it }, emptyList(), false)
        val text = StringBuilder()
        val offsets = mutableListOf<Int>()
        val spans = mutableListOf<MarkdownSpan>()
        var formatted = false
        var index = 0

        fun append(child: Inline, mark: MarkdownMark? = null) {
            val start = text.length
            text.append(child.text)
            offsets.addAll(child.offsets)
            spans.addAll(child.spans.map { it.copy(start = it.start + start, end = it.end + start) })
            if (mark != null && text.length > start) spans += MarkdownSpan(start, text.length, mark)
            formatted = true
        }

        while (index < source.length) {
            if (source[index] == '\\' &&
                source.getOrNull(index + 1)?.let { it in "\\`*_{}[]()#+-.!>~" } == true
            ) {
                text.append(source[index + 1])
                offsets += base + index + 1
                formatted = true
                index += 2
                continue
            }

            if (source[index] == '`') {
                val close = source.indexOf('`', index + 1)
                if (close > index + 1) {
                    append(Inline(source.substring(index + 1, close),
                        (index + 1 until close).map { base + it }, emptyList(), false), MarkdownMark.CODE)
                    index = close + 1
                    continue
                }
            }

            val labelStart = if (source.startsWith("![", index)) index + 2
                else if (source[index] == '[') index + 1 else -1
            if (labelStart >= 0) {
                val separator = source.indexOf("](", labelStart)
                var close = -1
                if (separator >= 0) {
                    var nesting = 1
                    for (position in separator + 2 until source.length) {
                        when (source[position]) {
                            '(' -> nesting++
                            ')' -> if (--nesting == 0) { close = position; break }
                        }
                    }
                }
                if (separator > labelStart && close > separator + 2) {
                    append(parseInline(source.substring(labelStart, separator), base + labelStart, depth + 1), MarkdownMark.LINK)
                    index = close + 1
                    continue
                }
            }

            val marker = when {
                source.startsWith("**", index) -> "**"
                source.startsWith("__", index) -> "__"
                source.startsWith("~~", index) -> "~~"
                source[index] == '*' -> "*"
                source[index] == '_' -> "_"
                else -> null
            }
            if (marker != null && (marker[0] != '_' ||
                    index == 0 || !source[index - 1].isLetterOrDigit())
            ) {
                var close = source.indexOf(marker, index + marker.length)
                while (close >= 0 && marker[0] == '_' &&
                    source.getOrNull(close + marker.length)?.isLetterOrDigit() == true
                ) close = source.indexOf(marker, close + marker.length)
                if (close > index + marker.length && !source[index + marker.length].isWhitespace() &&
                    !source[close - 1].isWhitespace()
                ) {
                    append(parseInline(source.substring(index + marker.length, close),
                        base + index + marker.length, depth + 1), when (marker) {
                        "**", "__" -> MarkdownMark.BOLD
                        "~~" -> MarkdownMark.STRIKE
                        else -> MarkdownMark.ITALIC
                    })
                    index = close + marker.length
                    continue
                }
            }

            text.append(source[index])
            offsets += base + index
            index++
        }
        return Inline(text.toString(), offsets, spans, formatted)
    }
}
