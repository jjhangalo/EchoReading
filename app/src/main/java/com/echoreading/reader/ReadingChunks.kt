package com.echoreading.reader

data class ReadingChunk(val text: String, val start: Int)

object ReadingChunks {
    fun split(text: String, maxChars: Int = 280): List<ReadingChunk> {
        require(maxChars >= 40)
        val chunks = mutableListOf<ReadingChunk>()
        var start = 0
        while (start < text.length) {
            while (start < text.length && text[start].isWhitespace()) start++
            if (start == text.length) break
            var end = minOf(start + maxChars, text.length)
            if (end < text.length) {
                val sentenceEnd = (start until end).lastOrNull { text[it] in ".!?;:\n" }
                val spaceEnd = (start until end).lastOrNull { text[it].isWhitespace() }
                val preferred = sentenceEnd?.plus(1) ?: spaceEnd
                if (preferred != null && preferred > start + maxChars / 3) end = preferred
            }
            chunks += ReadingChunk(text.substring(start, end).trim(), start)
            start = end
        }
        return chunks
    }
}

class AudioTimeline {
    private val durations = mutableListOf<Long>()
    val preparedMs: Long get() = durations.sum()
    val size: Int get() = durations.size

    fun clear() = durations.clear()
    fun durations(): List<Long> = durations.toList()
    fun add(durationMs: Long) { durations += durationMs.coerceAtLeast(0) }

    fun globalPosition(item: Int, localMs: Long): Long =
        durations.take(item.coerceIn(0, durations.size)).sum() + localMs.coerceAtLeast(0)

    fun locate(positionMs: Long): Pair<Int, Long> {
        require(durations.isNotEmpty())
        var remaining = positionMs.coerceIn(0, preparedMs)
        durations.forEachIndexed { index, duration ->
            if (remaining < duration || index == durations.lastIndex) return index to remaining.coerceAtMost(duration)
            remaining -= duration
        }
        error("Empty audio timeline")
    }
}
