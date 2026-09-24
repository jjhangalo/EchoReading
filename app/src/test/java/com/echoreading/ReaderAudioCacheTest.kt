package com.echoreading

import com.echoreading.reader.CachedReading
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReadingChunk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReaderAudioCacheTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        tempDir = Files.createTempDirectory("audio-cache-test").toFile()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        tempDir.deleteRecursively()
    }

    private fun createFakeChunkAudioDir(chunkCount: Int): File {
        val dir = File(tempDir, "reading-${System.nanoTime()}").apply { mkdirs() }
        for (i in 0 until chunkCount) {
            File(dir, "$i.wav").writeBytes(ByteArray(100))
        }
        return dir
    }

    @Test
    fun returnsCachedReadingWhenExactMatch() {
        val dir = createFakeChunkAudioDir(2)
        val chunks = listOf(ReadingChunk("Primeira frase.", 0), ReadingChunk("Segunda frase.", 16))
        val durations = listOf(3000L, 4000L)

        ReaderAudioCache.put(
            text = "Primeira frase. Segunda frase.",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = durations,
            audioDir = dir,
            timestamp = 10_000L,
        )

        val hit = ReaderAudioCache.get(
            text = "Primeira frase. Segunda frase.",
            voiceId = "pt-PT",
            speed = 1.0f,
            now = 15_000L,
        )

        assertNotNull(hit)
        assertEquals("Primeira frase. Segunda frase.", hit?.text)
        assertEquals("pt-PT", hit?.voiceId)
        assertEquals(1.0f, hit?.speed ?: 0f, 0.001f)
        assertEquals(2, hit?.chunks?.size)
        assertEquals(durations, hit?.durations)
        assertEquals(dir.absolutePath, hit?.audioDir?.absolutePath)
        assertTrue(ReaderAudioCache.isCachedDir(dir, now = 15_000L))
    }

    @Test
    fun returnsNullWhenTextDiffers() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto original",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto original", 0)),
            durations = listOf(2000L),
            audioDir = dir,
        )

        val result = ReaderAudioCache.get(
            text = "Texto diferente",
            voiceId = "pt-PT",
            speed = 1.0f,
        )
        assertNull(result)
    }

    @Test
    fun returnsNullWhenVoiceDiffers() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(2000L),
            audioDir = dir,
        )

        val result = ReaderAudioCache.get(
            text = "Texto",
            voiceId = "pt-BR",
            speed = 1.0f,
        )
        assertNull(result)
    }

    @Test
    fun returnsNullWhenSpeedDiffers() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(2000L),
            audioDir = dir,
        )

        val result = ReaderAudioCache.get(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.5f,
        )
        assertNull(result)
    }

    @Test
    fun returnsNullAndCleansUpWhenExpired() {
        val dir = createFakeChunkAudioDir(1)
        val timestamp = 1_000_000L
        ReaderAudioCache.put(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(2000L),
            audioDir = dir,
            timestamp = timestamp,
        )

        assertTrue(dir.exists())

        // Within TTL: valid
        val validHit = ReaderAudioCache.get(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            now = timestamp + ReaderAudioCache.CACHE_TTL_MS,
        )
        assertNotNull(validHit)

        // Beyond TTL: expired
        val expired = ReaderAudioCache.get(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            now = timestamp + ReaderAudioCache.CACHE_TTL_MS + 1L,
        )
        assertNull(expired)
        assertNull(ReaderAudioCache.current)
        assertFalse(dir.exists())
    }

    @Test
    fun returnsNullAndClearsIfAudioFilesMissing() {
        val dir = createFakeChunkAudioDir(2)
        ReaderAudioCache.put(
            text = "Texto com dois chunks",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Chunk 1", 0), ReadingChunk("Chunk 2", 8)),
            durations = listOf(1000L, 1000L),
            audioDir = dir,
        )

        // Delete one of the chunk files
        File(dir, "1.wav").delete()

        val hit = ReaderAudioCache.get("Texto com dois chunks", "pt-PT", 1.0f)
        assertNull(hit)
        assertNull(ReaderAudioCache.current)
    }

    @Test
    fun puttingNewCacheDeletesOldDirectory() {
        val dir1 = createFakeChunkAudioDir(1)
        val dir2 = createFakeChunkAudioDir(1)

        ReaderAudioCache.put(
            text = "Texto 1",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto 1", 0)),
            durations = listOf(1000L),
            audioDir = dir1,
        )
        assertTrue(dir1.exists())

        ReaderAudioCache.put(
            text = "Texto 2",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto 2", 0)),
            durations = listOf(1000L),
            audioDir = dir2,
        )

        assertFalse(dir1.exists())
        assertTrue(dir2.exists())
        assertEquals(dir2.absolutePath, ReaderAudioCache.current?.audioDir?.absolutePath)
    }

    @Test
    fun clearRemovesFilesAndResetState() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(1000L),
            audioDir = dir,
        )

        assertTrue(dir.exists())
        ReaderAudioCache.clear()

        assertNull(ReaderAudioCache.current)
        assertFalse(dir.exists())
        assertFalse(ReaderAudioCache.isCachedDir(dir))
    }

    @Test
    fun returnsNullAndClearsIfAudioFileIsEmptyZeroBytes() {
        val dir = createFakeChunkAudioDir(2)
        ReaderAudioCache.put(
            text = "Texto com arquivo vazio",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Chunk 1", 0), ReadingChunk("Chunk 2", 8)),
            durations = listOf(1000L, 1000L),
            audioDir = dir,
        )

        // Truncate one of the chunk files to 0 bytes
        File(dir, "1.wav").writeBytes(ByteArray(0))

        val hit = ReaderAudioCache.get("Texto com arquivo vazio", "pt-PT", 1.0f)
        assertNull(hit)
        assertNull(ReaderAudioCache.current)
        assertFalse(dir.exists())
    }

    @Test
    fun isCachedDirReturnsFalseAndCleansUpWhenExpired() {
        val dir = createFakeChunkAudioDir(1)
        val timestamp = 1_000_000L
        ReaderAudioCache.put(
            text = "Texto",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(1000L),
            audioDir = dir,
            timestamp = timestamp,
        )

        assertTrue(ReaderAudioCache.isCachedDir(dir, now = timestamp + 1000L))

        // When TTL has passed
        val isCached = ReaderAudioCache.isCachedDir(dir, now = timestamp + ReaderAudioCache.CACHE_TTL_MS + 1L)
        assertFalse(isCached)
        assertNull(ReaderAudioCache.current)
        assertFalse(dir.exists())
    }

    @Test
    fun putRejectsMismatchedChunksAndDurations() {
        val dir = createFakeChunkAudioDir(2)
        ReaderAudioCache.put(
            text = "Texto incompativel",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Chunk 1", 0), ReadingChunk("Chunk 2", 8)),
            durations = listOf(1000L), // Only 1 duration for 2 chunks!
            audioDir = dir,
        )

        assertNull(ReaderAudioCache.current)
    }

    @Test
    fun matchesSpeedWithSmallFloatTolerance() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto precisao float",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(1000L),
            audioDir = dir,
        )

        val hit = ReaderAudioCache.get("Texto precisao float", "pt-PT", 1.000001f)
        assertNotNull(hit)
    }

    @Test
    fun isCachedDirRecognizesSameDirectoryWithCanonicalPaths() {
        val dir = createFakeChunkAudioDir(1)
        ReaderAudioCache.put(
            text = "Texto caminho",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto", 0)),
            durations = listOf(1000L),
            audioDir = dir,
        )

        // Directory path with redundant separator/relative traversal
        val relativeDir = File(dir, "../${dir.name}")
        assertTrue(ReaderAudioCache.isCachedDir(relativeDir))
    }
}
