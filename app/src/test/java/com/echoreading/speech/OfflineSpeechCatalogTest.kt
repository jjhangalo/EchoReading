package com.echoreading.speech

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSpeechCatalogTest {
    @Test
    fun catalogContainsPinnedTinyBaseAndSmallModels() {
        assertEquals(listOf("tiny", "base", "small"), OfflineSpeech.models.map { it.id })
        assertEquals(OfflineSpeech.DEFAULT_MODEL_ID, OfflineSpeech.models.single { it.recommended }.id)
        assertEquals(103_609_903L, OfflineSpeech.option("tiny").downloadBytes)
        assertEquals(160_609_290L, OfflineSpeech.option("base").downloadBytes)
        assertEquals(375_485_327L, OfflineSpeech.option("small").downloadBytes)

        OfflineSpeech.models.forEach { model ->
            assertEquals(40, model.revision.length)
            assertEquals(3, model.files.size)
            assertTrue(model.files.all { it.size > 0 && it.sha256.length == 64 })
        }
    }

    @Test
    fun downloadProgressIsAlwaysClamped() {
        assertEquals(0f, ModelDownloadSnapshot(copiedBytes = -1, totalBytes = 100).progress, 0f)
        assertEquals(0.5f, ModelDownloadSnapshot(copiedBytes = 50, totalBytes = 100).progress, 0f)
        assertEquals(1f, ModelDownloadSnapshot(copiedBytes = 150, totalBytes = 100).progress, 0f)
    }

    @Test
    fun sha256ValidationUsesTheExpectedDigest() = runBlocking {
        val directory = Files.createTempDirectory("speech-hash-test").toFile()
        try {
            val file = File(directory, "sample").apply { writeText("abc") }
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                OfflineSpeech.sha256(file),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun audioLimitsUseThirtySecondChunksAndThirtyMinuteMaximum() {
        assertEquals(480_000, AudioDecoder.CHUNK_SAMPLES)
        assertEquals(1_800_000L, AudioDecoder.MAX_DURATION_MS)
    }
}
