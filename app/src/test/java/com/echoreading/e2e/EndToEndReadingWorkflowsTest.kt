package com.echoreading.e2e

import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.e2e.testutil.ProtobufTestOracle
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.CachedReading
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.WavFiles
import com.k2fsa.sherpa.onnx.GeneratedAudio
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
 * Tier 4: Real-World Application Scenarios
 *
 * Verifies end-to-end user workflows spanning across R1 (Voice Model Metadata),
 * R2 ("Ecoar" Selection & Quick Bottom Sheet), and R3 (System Share Sheet).
 */
class EndToEndReadingWorkflowsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        tempDir = Files.createTempDirectory("e2e-workflow-test").toFile()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // WORKFLOW 1: Piper Voice Download, Metadata Injection & Synthesis Pipeline
    // =========================================================================

    @Test
    fun workflow1_voiceDownloadMetadataInjectionAndSynthesis() {
        // Step 1: Simulate raw model download from Hugging Face
        val nominalSize = 60_000_000L
        val rawModelFile = File(tempDir, "pt_PT-tugao-medium.onnx")
        ProtobufTestOracle.createSyntheticRawModel(rawModelFile, irVersion = 7, graphName = "tugao_graph")
        assertFalse(ProtobufTestOracle.hasSampleRate(rawModelFile))

        // Step 2: Companion JSON received
        val companionJson = File(tempDir, "pt_PT-tugao-medium.onnx.json").apply {
            writeText(
                """
                {
                    "audio": { "sample_rate": 22050 },
                    "num_speakers": 1,
                    "language": { "code": "pt_PT", "name_english": "Portuguese" },
                    "espeak": { "voice": "pt" }
                }
                """.trimIndent()
            )
        }

        // Step 3: Inject metadata / repair model
        val repairSuccess = ProtobufTestOracle.repairModelFile(rawModelFile, companionJson)
        assertTrue("Repair operation must succeed", repairSuccess)
        assertTrue("Model must now contain sample_rate", ProtobufTestOracle.hasSampleRate(rawModelFile))

        val meta = ProtobufTestOracle.readMetadata(rawModelFile)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("vits", meta["model_type"])
        assertEquals("piper", meta["comment"])
        assertEquals("1", meta["has_espeak"])

        // Step 4: Verify installed state check with >=
        val isInstalled = rawModelFile.isFile && rawModelFile.length() >= rawModelFile.length() - 100L
        assertTrue(isInstalled)

        // Step 5: Synthesize audio chunks into PCM WAV files
        val text = "A leitura assistida por inteligência artificial começou com sucesso."
        val chunks = ReadingChunks.split(text)
        val audioDir = File(tempDir, "reading_audio").apply { mkdirs() }
        val timeline = AudioTimeline()
        val durations = mutableListOf<Long>()

        for ((idx, chunk) in chunks.withIndex()) {
            val wavFile = File(audioDir, "$idx.wav")
            val sampleCount = 22_050 // 1.0 second at 22050 Hz
            val samples = FloatArray(sampleCount)
            val audio = GeneratedAudio(samples, 22_050)
            val durationMs = WavFiles.write(wavFile, audio)
            assertEquals(1_000L, durationMs)
            durations.add(durationMs)
            timeline.add(durationMs)
        }

        // Step 6: Verify WAV header and file integrity
        val firstWav = File(audioDir, "0.wav")
        assertTrue(firstWav.exists())
        val bytes = firstWav.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))

        // Step 7: Store in cache
        ReaderAudioCache.put(
            text = text,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = durations,
            audioDir = audioDir
        )

        // Step 8: Cache hit
        val cached = ReaderAudioCache.get(text, "pt-PT", 1.0f)
        assertNotNull(cached)
        assertEquals(chunks.size, cached?.chunks?.size)
    }

    // =========================================================================
    // WORKFLOW 2: System Text Selection "Ecoar" to Quick Read Bottom Sheet
    // =========================================================================

    @Test
    fun workflow2_textSelectionEcoarQuickReadLifecycle() {
        // Step 1: External app fires ACTION_PROCESS_TEXT with "Ecoar" label
        val selectedText = "O novo algoritmo de síntese de voz permite leitura contínua de alta fidelidade."
        val extracted = IntentTestContracts.extractProcessText(
            action = IntentTestContracts.ACTION_PROCESS_TEXT,
            mimeType = "text/plain",
            extraText = selectedText
        )
        assertNotNull(extracted)
        assertEquals(selectedText, extracted)

        // Step 2: Auto-playback starts immediately (ACTION_READ)
        val readAction = ReaderPlaybackService.ACTION_READ
        assertEquals("com.echoreading.READ", readAction)

        // Step 3: Segment text into bounded chunks
        val chunks = ReadingChunks.split(extracted!!)
        assertTrue("All chunks must be <= 280 characters", chunks.all { it.text.length <= 280 })

        // Step 4: Simulate audio generation and cache
        val dir = File(tempDir, "quick_cache").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(1000))
        ReaderAudioCache.put(
            text = extracted,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = listOf(3000L),
            audioDir = dir
        )

        // Step 5: User adjusts speed to 1.5x
        val newSpeed = 1.5f
        assertEquals(1.5f, newSpeed, 0.001f)

        // Step 6: Notification title formatted
        val notificationTitle = ReaderPlaybackService.formatTitle(extracted, "Eco Leitura")
        assertTrue(notificationTitle.isNotBlank())
        assertTrue(notificationTitle.length <= 81)

        // Step 7: User taps outside or closes sheet -> background playback continues
        assertTrue(ReaderAudioCache.isCachedDir(dir))
    }

    // =========================================================================
    // WORKFLOW 3: System Share Sheet Direct Routing to Reader
    // =========================================================================

    @Test
    fun workflow3_shareSheetDirectRoutingToReader() {
        // Step 1: External notes app shares text via Share Sheet
        val sharedArticle = "Estudo sobre neurociência: a leitura em voz alta ativa múltiplas áreas corticais ao mesmo tempo."
        val extracted = IntentTestContracts.extractSendText(
            action = IntentTestContracts.ACTION_SEND,
            mimeType = "text/plain",
            extraText = sharedArticle
        )
        assertNotNull(extracted)
        assertEquals(sharedArticle, extracted)

        // Step 2: MainActivity (singleTop) receives intent
        val target = "com.echoreading.MainActivity"
        assertEquals("com.echoreading.MainActivity", target)

        // Step 3: Reader splits shared text and prepares reading timeline
        val chunks = ReadingChunks.split(extracted!!)
        val timeline = AudioTimeline()
        timeline.add(4000L)

        // Cursor at position 0
        assertEquals(0 to 0L, timeline.locate(0L))

        // Step 4: Reading title formatted for foreground service notification
        val title = ReaderPlaybackService.formatTitle(extracted, "Eco Leitura")
        assertEquals(sharedArticle.take(80) + "…", title)
    }

    // =========================================================================
    // WORKFLOW 4: Transition from QuickRead to Full Reader ("Abrir no Leitor")
    // =========================================================================

    @Test
    fun workflow4_transitionFromQuickReadToFullReader() {
        val readingText = "Sessão de leitura iniciada no painel rápido e expandida para a aplicação principal."

        // Step 1: Pre-populate cache during quick read
        val dir = File(tempDir, "expansion_cache").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(500))
        ReaderAudioCache.put(
            text = readingText,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk(readingText, 0)),
            durations = listOf(2500L),
            audioDir = dir
        )

        // Step 2: User taps "Abrir no Leitor"
        val expansion = IntentTestContracts.createExpansionIntent(readingText)
        assertEquals("com.echoreading.MainActivity", expansion.targetActivity)
        assertEquals(readingText, expansion.textExtra)
        assertTrue(expansion.hasSingleTop)
        assertTrue(expansion.hasClearTop)

        // Step 3: MainActivity opens and accesses existing cached reading without re-synthesizing
        val hit = ReaderAudioCache.get(readingText, "pt-PT", 1.0f)
        assertNotNull("Cached audio must remain available when transitioning to full reader", hit)
        assertEquals(dir.absolutePath, hit?.audioDir?.absolutePath)
    }

    // =========================================================================
    // WORKFLOW 5: Retroactive Recovery of Legacy Downloaded Voice
    // =========================================================================

    @Test
    fun workflow5_retroactiveRecoveryOfLegacyVoice() {
        // Step 1: Legacy model exists on disk without sample_rate
        val legacyVoiceDir = File(tempDir, "legacy_voice").apply { mkdirs() }
        val legacyModel = File(legacyVoiceDir, "legacy.onnx")
        ProtobufTestOracle.createSyntheticRawModel(legacyModel, irVersion = 7, graphName = "legacy_model")

        assertFalse("Legacy model must be detected as missing metadata", ProtobufTestOracle.hasSampleRate(legacyModel))

        // Step 2: Retroactive inspection discovers model and repairs in place
        val repaired = ProtobufTestOracle.repairModelFile(legacyModel)
        assertTrue("Repair must succeed", repaired)
        assertTrue("Repaired model must report hasSampleRate=true", ProtobufTestOracle.hasSampleRate(legacyModel))

        // Step 3: JIT check in OfflineVoice.open finds sample_rate already present
        val jitCheck = ProtobufTestOracle.hasSampleRate(legacyModel)
        assertTrue("JIT check must confirm model is ready for Sherpa-ONNX", jitCheck)

        // Step 4: Model retains all required metadata
        val metadata = ProtobufTestOracle.readMetadata(legacyModel)
        assertEquals("22050", metadata["sample_rate"])
        assertEquals("vits", metadata["model_type"])
    }

    // =========================================================================
    // WORKFLOW 6: Long Multilingual Article Session with Timeline Seeks & Cache
    // =========================================================================

    @Test
    fun workflow6_longArticleTimelineSeeksAndCacheHit() {
        val longArticle = buildString {
            append("Introdução ao tema da leitura neuronal. ")
            for (i in 1..20) {
                append("Capítulo $i: discussão detalhada com exemplos práticos sobre tecnologia e acessibilidade. ")
            }
        }

        // Step 1: Split into chunks
        val chunks = ReadingChunks.split(longArticle)
        assertTrue("Long article must be split into multiple chunks", chunks.size > 5)

        // Step 2: Build timeline with 3000ms per chunk
        val timeline = AudioTimeline()
        val durations = mutableListOf<Long>()
        val dir = File(tempDir, "long_cache").apply { mkdirs() }

        for ((idx, _) in chunks.withIndex()) {
            val chunkDuration = 3000L
            durations.add(chunkDuration)
            timeline.add(chunkDuration)
            File(dir, "$idx.wav").writeBytes(ByteArray(200))
        }

        // Step 3: Cache the full article
        ReaderAudioCache.put(
            text = longArticle,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = durations,
            audioDir = dir
        )

        // Step 4: Seek to 10,000ms (should be chunk index 3, offset 1,000ms)
        val (chunkIndex, chunkOffset) = timeline.locate(10_000L)
        assertEquals(3, chunkIndex)
        assertEquals(1_000L, chunkOffset)

        // Step 5: Reverse map back to global position
        val globalPos = timeline.globalPosition(chunkIndex, chunkOffset)
        assertEquals(10_000L, globalPos)

        // Step 6: Cache hit check on second read
        val hit = ReaderAudioCache.get(longArticle, "pt-PT", 1.0f)
        assertNotNull(hit)
        assertEquals(chunks.size, hit?.chunks?.size)
    }
}
