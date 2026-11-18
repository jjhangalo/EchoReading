package com.echoreading.speech

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSpeechCatalogTest {
    @Test
    fun catalogContainsPinnedWhisperModelsAndParakeet() {
        assertEquals(listOf("tiny", "base", "small", "parakeet-v3"), OfflineSpeech.models.map { it.id })
        assertEquals(103_609_903L, OfflineSpeech.option("tiny").downloadBytes)
        assertEquals(160_609_290L, OfflineSpeech.option("base").downloadBytes)
        assertEquals(375_485_327L, OfflineSpeech.option("small").downloadBytes)
        assertEquals(670_576_784L, OfflineSpeech.option("parakeet-v3").downloadBytes)

        OfflineSpeech.models.forEach { model ->
            assertEquals(40, model.revision.length)
            assertEquals(if (model.engine == SpeechEngine.WHISPER) 3 else 4, model.files.size)
            assertTrue(model.files.all { it.size > 0 && it.sha256.length == 64 })
        }
        assertEquals(SpeechEngine.WHISPER, OfflineSpeech.option("base").engine)
        assertEquals(SpeechEngine.NEMO_TRANSDUCER, OfflineSpeech.option("parakeet-v3").engine)
        assertEquals("csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", OfflineSpeech.option("parakeet-v3").repository)
    }

    @Test
    fun recommendationFollowsDeviceMemory() {
        val gigabyte = 1024L * 1024L * 1024L
        // Um aparelho de 4 GB anunciados reporta ~3,5 GiB e um de 6 GB ~5,5 GiB.
        assertEquals("tiny", OfflineSpeech.recommendModelId(2L * gigabyte, false))
        assertEquals("tiny", OfflineSpeech.recommendModelId(8L * gigabyte, true))
        assertEquals("base", OfflineSpeech.recommendModelId(3_500_000_000L, false))
        assertEquals("base", OfflineSpeech.recommendModelId(4L * gigabyte, false))
        assertEquals("parakeet-v3", OfflineSpeech.recommendModelId(5L * gigabyte, false))
        assertEquals("parakeet-v3", OfflineSpeech.recommendModelId(6L * gigabyte, false))
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

    @Test
    fun whisperBoundariesUseFixedThirtySecondWindows() = runBlocking {
        val file = pcmFile(seconds = 75, amplitude = 0.4f)
        try {
            val boundaries = AudioDecoder.chunkBoundaries(file, SpeechEngine.WHISPER)
            assertEquals(listOf(0L, 480_000L, 960_000L, 1_200_000L), boundaries)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parakeetBoundariesCutAtSilenceWithinTheWindow() = runBlocking {
        // 45 s: 0,4 de amplitude com 1 s de silêncio a cada 6 s (segundos 5, 11, 17, ...).
        val file = pcmFile(seconds = 45, amplitude = 0.4f, silentSecondModulo = 6)
        try {
            val boundaries = AudioDecoder.chunkBoundaries(file, SpeechEngine.NEMO_TRANSDUCER)
            assertEquals(0L, boundaries.first())
            assertEquals(45L * AudioDecoder.SAMPLE_RATE, boundaries.last())
            assertTrue("blocos de 12-20 s exigem mais do que um corte", boundaries.size > 2)
            for (index in 1 until boundaries.size - 1) {
                val length = boundaries[index] - boundaries[index - 1]
                assertTrue(
                    "bloco de $length amostras fora da janela de 12-20 s",
                    length in 12L * AudioDecoder.SAMPLE_RATE..20L * AudioDecoder.SAMPLE_RATE,
                )
                // O corte cai no silêncio: a última amostra do bloco anterior é zero.
                val previous = AudioDecoder.readChunk(file, boundaries, index - 1)
                assertEquals(0f, previous.last(), 0.0001f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun readChunkAmplifiesQuietChunksOnlyWhenRequested() = runBlocking {
        val file = pcmFile(seconds = 1, amplitude = 0.05f)
        try {
            val boundaries = listOf(0L, 1600L)
            val quiet = AudioDecoder.readChunk(file, boundaries, 0)
            val boosted = AudioDecoder.readChunk(file, boundaries, 0, normalizeGain = true)
            assertEquals(0.05f, quiet.max(), 0.001f)
            assertEquals(0.9f, boosted.max(), 0.01f)
        } finally {
            file.delete()
        }
    }
}

/** Grava `seconds` de PCM 16 kHz mono; segundos com índice múltiplo de `silentSecondModulo` ficam em silêncio. */
private fun pcmFile(seconds: Int, amplitude: Float, silentSecondModulo: Int = 0): File {
    val file = Files.createTempFile("audio-decoder-test", ".pcm").toFile()
    val sampleCount = seconds * AudioDecoder.SAMPLE_RATE
    val buffer = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(sampleCount) { index ->
        val silent = silentSecondModulo > 0 && (index / AudioDecoder.SAMPLE_RATE) % silentSecondModulo == silentSecondModulo - 1
        buffer.putShort(if (silent) 0.toShort() else (amplitude * 32767f).toInt().toShort())
    }
    file.writeBytes(buffer.array())
    return file
}
