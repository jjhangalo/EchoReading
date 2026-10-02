package com.echoreading.reader

import java.io.File
import kotlin.math.abs

data class CachedReading(
    val text: String,
    val voiceId: String,
    val speed: Float,
    val chunks: List<ReadingChunk>,
    val durations: List<Long>,
    val audioDir: File,
    val timestamp: Long = System.currentTimeMillis(),
)

object ReaderAudioCache {
    const val CACHE_TTL_MS = 5 * 60 * 1000L

    @Volatile
    private var cached: CachedReading? = null

    val current: CachedReading?
        get() = cached

    private fun isSameFile(f1: File, f2: File): Boolean {
        val c1 = runCatching { f1.canonicalPath }.getOrDefault(f1.absolutePath)
        val c2 = runCatching { f2.canonicalPath }.getOrDefault(f2.absolutePath)
        return c1 == c2
    }

    @Synchronized
    fun isCachedDir(dir: File, now: Long = System.currentTimeMillis()): Boolean {
        val entry = cached ?: return false
        if (now - entry.timestamp > CACHE_TTL_MS) {
            clear()
            return false
        }
        return isSameFile(entry.audioDir, dir)
    }

    @Synchronized
    fun get(
        text: String,
        voiceId: String,
        speed: Float,
        now: Long = System.currentTimeMillis(),
    ): CachedReading? {
        val entry = cached ?: return null
        if (now - entry.timestamp > CACHE_TTL_MS) {
            clear()
            return null
        }
        if (entry.text != text || entry.voiceId != voiceId || abs(entry.speed - speed) > 0.01f) {
            return null
        }
        if (!entry.audioDir.isDirectory) {
            clear()
            return null
        }
        for (i in entry.chunks.indices) {
            val file = File(entry.audioDir, "$i.wav")
            if (!file.isFile || file.length() == 0L) {
                clear()
                return null
            }
        }
        return entry
    }

    @Synchronized
    fun put(
        text: String,
        voiceId: String,
        speed: Float,
        chunks: List<ReadingChunk>,
        durations: List<Long>,
        audioDir: File,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        if (chunks.isEmpty() || chunks.size != durations.size) return
        val old = cached
        if (old != null && !isSameFile(old.audioDir, audioDir)) {
            old.audioDir.deleteRecursively()
        }
        cached = CachedReading(
            text = text,
            voiceId = voiceId,
            speed = speed,
            chunks = chunks,
            durations = durations,
            audioDir = audioDir,
            timestamp = timestamp,
        )
    }

    @Synchronized
    fun clear() {
        val old = cached
        cached = null
        old?.audioDir?.deleteRecursively()
    }
}
