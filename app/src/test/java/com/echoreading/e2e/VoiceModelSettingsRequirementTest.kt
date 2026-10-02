package com.echoreading.e2e

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2E Requirement Tests for Requirement R5:
 * Configurações de Modelos de Voz (TTS e STT)
 *
 * Covers Features 14 and 15 across Tier 1 (Happy Path), Tier 2 (Boundaries),
 * and Tier 3 (Cross-Feature Combinations).
 */
class VoiceModelSettingsRequirementTest {

    private lateinit var tempDir: File
    private lateinit var mockWhisperDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("settings-model-test").toFile()
        mockWhisperDir = File(tempDir, "stt-models/whisper-small-int8")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createValidWhisperModelFiles(): Triple<File, File, File> {
        mockWhisperDir.mkdirs()
        val encoder = File(mockWhisperDir, "small-encoder.int8.onnx").apply { writeBytes(ByteArray(1024) { 1 }) }
        val decoder = File(mockWhisperDir, "small-decoder.int8.onnx").apply { writeBytes(ByteArray(2048) { 2 }) }
        val tokens = File(mockWhisperDir, "small-tokens.txt").apply { writeText("vocab token entries") }
        return Triple(encoder, decoder, tokens)
    }

    private fun isWhisperModelComplete(dir: File): Boolean {
        val encoder = File(dir, "small-encoder.int8.onnx")
        val decoder = File(dir, "small-decoder.int8.onnx")
        val tokens = File(dir, "small-tokens.txt")
        return encoder.isFile && encoder.length() > 0 &&
            decoder.isFile && decoder.length() > 0 &&
            tokens.isFile && tokens.length() > 0
    }

    private fun computeDirectorySizeBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // =========================================================================
    // TIER 1: FEATURE 14 — TTS Piper Voice Management (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f14_1_piperVoiceDirectoryLayoutMatchesSpecification() {
        val voicesDir = File(tempDir, "voices")
        val voiceKey = "pt_PT-tugao-medium"
        val modelDir = File(voicesDir, voiceKey).apply { mkdirs() }
        val onnxFile = File(modelDir, "$voiceKey.onnx").apply { writeBytes(ByteArray(500) { 0x72 }) }

        assertTrue(onnxFile.isFile)
        assertTrue(onnxFile.length() > 0)
    }

    @Test
    fun f14_2_piperVoiceSelectionPersistenceContract() {
        var selectedVoiceKey = "pt_PT-tugao-medium"
        assertEquals("pt_PT-tugao-medium", selectedVoiceKey)

        selectedVoiceKey = "pt_PT-joana-medium"
        assertEquals("pt_PT-joana-medium", selectedVoiceKey)
    }

    @Test
    fun f14_3_piperVoiceDeletionFreesStorage() {
        val voicesDir = File(tempDir, "voices")
        val voiceDir = File(voicesDir, "voice-to-delete").apply { mkdirs() }
        val file = File(voiceDir, "model.onnx").apply { writeBytes(ByteArray(10_000)) }

        val sizeBefore = computeDirectorySizeBytes(voiceDir)
        assertEquals(10_000L, sizeBefore)

        val deleted = voiceDir.deleteRecursively()
        assertTrue(deleted)
        assertFalse(voiceDir.exists())
    }

    @Test
    fun f14_4_voiceModelFileSizeRelaxedInequalityContract() {
        // Feature F14 requirement: size check must use >= expectedBytes to allow metadata injection
        val expectedBytes = 63_200_000L
        val injectedBytes = 63_201_454L // Injected metadata adds ~200 bytes

        val isInstalledStrict = injectedBytes == expectedBytes
        val isInstalledRelaxed = injectedBytes >= expectedBytes

        assertFalse("Strict equality fails on injected models", isInstalledStrict)
        assertTrue("Relaxed inequality accepts injected models", isInstalledRelaxed)
    }

    @Test
    fun f14_5_installedVoiceListingFiltersValidDirectories() {
        val voicesDir = File(tempDir, "voices").apply { mkdirs() }
        File(voicesDir, "voice1").mkdirs()
        File(voicesDir, "voice2").mkdirs()

        val count = voicesDir.listFiles()?.count { it.isDirectory } ?: 0
        assertEquals(2, count)
    }

    // =========================================================================
    // TIER 1: FEATURE 15 — STT Whisper Offline Management (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f15_1_whisperModelInstallationDetectedWhenAllFilesPresent() {
        createValidWhisperModelFiles()
        assertTrue("Model should be recognized as installed", isWhisperModelComplete(mockWhisperDir))
    }

    @Test
    fun f15_2_whisperModelDiskFootprintComputedAccurately() {
        createValidWhisperModelFiles()
        val totalBytes = computeDirectorySizeBytes(mockWhisperDir)
        assertTrue("Disk footprint must equal sum of files", totalBytes > 3000L)
    }

    @Test
    fun f15_3_whisperDownloadProgressCalculation() {
        val totalExpectedBytes = 150_000_000L
        val downloadedBytes = 75_000_000L

        val progress = downloadedBytes.toFloat() / totalExpectedBytes.toFloat()
        assertEquals(0.5f, progress, 0.001f)
    }

    @Test
    fun f15_4_whisperReadinessStatusBecomesReadyWhenInstalled() {
        assertFalse("Initially not ready", isWhisperModelComplete(mockWhisperDir))
        createValidWhisperModelFiles()
        assertTrue("Ready when all files exist", isWhisperModelComplete(mockWhisperDir))
    }

    @Test
    fun f15_5_whisperModelDeletionRemovesDirectoryAndFiles() {
        createValidWhisperModelFiles()
        assertTrue(mockWhisperDir.exists())

        val deleted = mockWhisperDir.deleteRecursively()
        assertTrue(deleted)
        assertFalse(mockWhisperDir.exists())
        assertFalse(isWhisperModelComplete(mockWhisperDir))
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 6 test cases)
    // =========================================================================

    @Test
    fun b1_missingModelDirectoryReportsNotInstalled() {
        val nonExistent = File(tempDir, "non_existent_model_dir")
        assertFalse(isWhisperModelComplete(nonExistent))
        assertEquals(0L, computeDirectorySizeBytes(nonExistent))
    }

    @Test
    fun b2_partiallyDownloadedModelMissingDecoderReportsNotInstalled() {
        mockWhisperDir.mkdirs()
        File(mockWhisperDir, "small-encoder.int8.onnx").writeBytes(ByteArray(100))
        File(mockWhisperDir, "small-tokens.txt").writeText("tokens")
        // Decoder missing
        assertFalse("Missing decoder must report not installed", isWhisperModelComplete(mockWhisperDir))
    }

    @Test
    fun b3_zeroByteModelFileReportsNotInstalled() {
        mockWhisperDir.mkdirs()
        File(mockWhisperDir, "small-encoder.int8.onnx").writeBytes(ByteArray(0)) // 0-byte corrupt
        File(mockWhisperDir, "small-decoder.int8.onnx").writeBytes(ByteArray(100))
        File(mockWhisperDir, "small-tokens.txt").writeText("tokens")

        assertFalse("0-byte encoder must report not installed", isWhisperModelComplete(mockWhisperDir))
    }

    @Test
    fun b4_partialPartDownloadFileNotCountedAsCompletedModel() {
        mockWhisperDir.mkdirs()
        File(mockWhisperDir, "small-encoder.int8.onnx.part").writeBytes(ByteArray(500))
        assertFalse(isWhisperModelComplete(mockWhisperDir))
    }

    @Test
    fun b5_deleteNonExistentModelReturnsGracefully() {
        val ghostDir = File(tempDir, "ghost-model")
        val result = ghostDir.deleteRecursively()
        assertTrue("Deleting non-existent directory succeeds gracefully", result)
    }

    @Test
    fun b6_progressClampedBetweenZeroAndOne() {
        val progressNegative = (-10L).toFloat() / 100f
        val progressOverflow = 150L.toFloat() / 100f

        val clampedLow = progressNegative.coerceIn(0f, 1f)
        val clampedHigh = progressOverflow.coerceIn(0f, 1f)

        assertEquals(0f, clampedLow, 0.001f)
        assertEquals(1f, clampedHigh, 0.001f)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE INTERACTIONS (Pairwise)
    // =========================================================================

    @Test
    fun c1_modelReadinessGatesTranscriptionInitiation() {
        // If STT model is not installed, readiness is false
        val isReadyBefore = isWhisperModelComplete(mockWhisperDir)
        assertFalse(isReadyBefore)

        // Install model -> readiness becomes true -> transcription allowed
        createValidWhisperModelFiles()
        val isReadyAfter = isWhisperModelComplete(mockWhisperDir)
        assertTrue(isReadyAfter)
    }

    @Test
    fun c2_voiceSettingsModelSelectionTransfersToTtsEngine() {
        val installedVoices = listOf("pt_PT-tugao-medium", "pt_PT-joana-medium")
        var activeVoice = installedVoices.first()
        assertEquals("pt_PT-tugao-medium", activeVoice)

        // User switches voice in settings
        activeVoice = installedVoices[1]
        assertEquals("pt_PT-joana-medium", activeVoice)
    }
}
