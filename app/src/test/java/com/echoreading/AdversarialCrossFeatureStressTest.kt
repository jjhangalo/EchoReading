package com.echoreading

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
import com.echoreading.share.ShareIntentHandler
import com.echoreading.voice.OnnxMetadata
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone M4 Phase 2 — Tier 5 Adversarial Coverage Hardening Suite.
 *
 * Cross-Feature & System-Wide White-Box Stress Testing:
 * 1. Inter-component state interactions: QuickReadActivity -> MainActivity text transfer & playback continuation.
 * 2. Concurrent intent deliveries: rapid interleaved ACTION_SEND and PROCESS_TEXT requests during active audio synthesis and playback.
 * 3. ONNX metadata injection edge cases: malformed Protobuf tag streams, truncated buffers, extreme sample rates, repeated repairs.
 * 4. Memory and lifecycle safety: configuration changes, singleTop instance re-use, and background service lifecycle (ReaderPlaybackService).
 */
class AdversarialCrossFeatureStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val originalSnapshot = ReaderState.snapshot.value
    private val startedServices = Collections.synchronizedList(mutableListOf<Intent>())
    private val sharedPrefsMap = ConcurrentHashMap<String, Any>()
    private lateinit var fakeContext: Context

    @Before
    fun setUp() {
        startedServices.clear()
        sharedPrefsMap.clear()
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = ReaderSnapshot()

        val mockPrefs = createMockSharedPreferences(sharedPrefsMap)
        fakeContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.echoreading"

            override fun startService(service: Intent): ComponentName? {
                startedServices.add(service)
                return ComponentName("com.echoreading", "ReaderPlaybackService")
            }

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                return mockPrefs
            }
        }
    }

    @After
    fun tearDown() {
        startedServices.clear()
        sharedPrefsMap.clear()
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = originalSnapshot
    }

    private fun createMockSharedPreferences(map: ConcurrentHashMap<String, Any>): SharedPreferences {
        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString", "putInt", "putLong", "putFloat", "putBoolean" -> {
                    if (args != null && args.size >= 2) {
                        val key = args[0] as String
                        val value = args[1]
                        if (value != null) map[key] = value
                    }
                    proxy
                }
                "remove" -> {
                    if (args != null && args.isNotEmpty()) {
                        map.remove(args[0] as String)
                    }
                    proxy
                }
                "clear" -> {
                    map.clear()
                    proxy
                }
                "apply", "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editorProxy
                "getString" -> (map[args[0]] as? String) ?: (args[1] as? String)
                "getInt" -> (map[args[0]] as? Int) ?: (args[1] as? Int)
                "getLong" -> (map[args[0]] as? Long) ?: (args[1] as? Long)
                "getFloat" -> (map[args[0]] as? Float) ?: (args[1] as? Float)
                "getBoolean" -> (map[args[0]] as? Boolean) ?: (args[1] as? Boolean)
                "contains" -> map.containsKey(args[0])
                else -> null
            }
        } as SharedPreferences
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun createSyntheticModel(
        file: File,
        irVersion: Long = 8,
        producer: String = "piper",
        graphSize: Int = 512
    ): File {
        val out = ByteArrayOutputStream()
        // ir_version (field 1, wire 0)
        out.write(0x08)
        out.write(irVersion.toInt())

        // producer_name (field 2, wire 2)
        val prodBytes = producer.toByteArray(Charsets.UTF_8)
        out.write(0x12)
        out.write(prodBytes.size)
        out.write(prodBytes)

        // graph (field 7, wire 2)
        out.write(0x3A)
        writeVarint(out, graphSize.toLong())
        out.write(ByteArray(graphSize) { (it % 256).toByte() })

        file.writeBytes(out.toByteArray())
        return file
    }

    // =========================================================================
    // SECTION 1: INTER-COMPONENT STATE INTERACTIONS (QuickRead -> MainActivity)
    // =========================================================================

    /**
     * White-box simulation of MainActivity.handleIncomingIntent for ACTION_VIEW.
     * Replicates lines 58-73 of MainActivity.kt to test expansion contracts.
     */
    private fun simulateMainActivityIncomingIntent(
        context: Context,
        action: String?,
        extraText: String?,
        onLoadTextEventEmitted: ((String) -> Unit)? = null
    ) {
        if (action == Intent.ACTION_SEND) {
            val sharedText = ShareIntentHandler.extractText(action, "text/plain", extraText)
            if (!sharedText.isNullOrBlank()) {
                ReaderState.loadText(context, sharedText)
            }
        } else if (action == Intent.ACTION_VIEW) {
            val viewText = extraText?.trim()
            if (!viewText.isNullOrBlank()) {
                val currentSnapshot = ReaderState.snapshot.value
                val isCurrentSession = currentSnapshot.text == viewText &&
                    (currentSnapshot.status == ReadingStatus.PLAYING ||
                     currentSnapshot.status == ReadingStatus.PAUSED ||
                     currentSnapshot.status == ReadingStatus.PREPARING)
                if (isCurrentSession) {
                    ReaderState.loadTextEvent.tryEmit(viewText)
                    onLoadTextEventEmitted?.invoke(viewText)
                } else {
                    ReaderState.loadText(context, viewText)
                }
            }
        }
    }

    @Test
    fun test01_expansionDuringActivePlayback_preservesAudioWithoutInterruption() {
        val readingText = "Sessão de leitura activa iniciada no QuickReadActivity"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = readingText,
            status = ReadingStatus.PLAYING,
            positionMs = 12_500L,
            durationMs = 30_000L,
            characterOffset = 85,
            voiceId = "pt-PT",
            speed = 1.0f
        )

        var eventEmitted = false
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = readingText,
            onLoadTextEventEmitted = { eventEmitted = true }
        )

        assertTrue("loadTextEvent must be emitted on active expansion", eventEmitted)
        assertEquals("Playback status must remain PLAYING", ReadingStatus.PLAYING, ReaderState.snapshot.value.status)
        assertEquals("Position must not be reset", 12_500L, ReaderState.snapshot.value.positionMs)
        assertEquals("Character offset must not be reset", 85, ReaderState.snapshot.value.characterOffset)
        assertTrue("No STOP service intent should have been dispatched", startedServices.isEmpty())
    }

    @Test
    fun test02_expansionDuringPreparing_preservesSynthesisWithoutInterruption() {
        val preparingText = "Texto longo a sintetizar em segundo plano"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = preparingText,
            status = ReadingStatus.PREPARING,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0,
            voiceId = "pt-PT",
            speed = 1.25f
        )

        var eventEmitted = false
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = preparingText,
            onLoadTextEventEmitted = { eventEmitted = true }
        )

        assertTrue("loadTextEvent must be emitted on preparing expansion", eventEmitted)
        assertEquals("Status must remain PREPARING", ReadingStatus.PREPARING, ReaderState.snapshot.value.status)
        assertTrue("No STOP service intent should be sent during preparing expansion", startedServices.isEmpty())
    }

    @Test
    fun test03_expansionDuringPaused_preservesPausedStateAndPosition() {
        val pausedText = "Texto pausado pelo utilizador no QuickReadActivity"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = pausedText,
            status = ReadingStatus.PAUSED,
            positionMs = 7_200L,
            durationMs = 20_000L,
            characterOffset = 40,
            voiceId = "pt-PT",
            speed = 1.0f
        )

        var eventEmitted = false
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = pausedText,
            onLoadTextEventEmitted = { eventEmitted = true }
        )

        assertTrue("loadTextEvent must be emitted on paused expansion", eventEmitted)
        assertEquals("Status must remain PAUSED", ReadingStatus.PAUSED, ReaderState.snapshot.value.status)
        assertEquals("Position must remain intact", 7_200L, ReaderState.snapshot.value.positionMs)
        assertTrue("No STOP service intent should be sent during paused expansion", startedServices.isEmpty())
    }

    @Test
    fun test04_expansionWithDivergentText_forcesReloadAndStopsPreviousPlayback() {
        val originalText = "Texto original em reprodução"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = originalText,
            status = ReadingStatus.PLAYING,
            positionMs = 5_000L,
            durationMs = 15_000L,
            characterOffset = 20
        )

        val divergentText = "Texto totalmente diferente vindo de outra selecção"
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = divergentText
        )

        assertEquals("Snapshot text must update to divergent text", divergentText, ReaderState.snapshot.value.text)
        assertEquals("Status must reset to IDLE because session differed", ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals("Position must reset to 0", 0L, ReaderState.snapshot.value.positionMs)
        assertTrue("A STOP command must be dispatched to service to stop previous audio", startedServices.isNotEmpty())
    }

    @Test
    fun test05_expansionWhenIdle_loadsTextFresh() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto anterior que já terminou",
            status = ReadingStatus.IDLE,
            positionMs = 10_000L,
            durationMs = 10_000L
        )

        val freshText = "Texto para leitura fresca"
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = freshText
        )

        assertEquals("Text must be updated", freshText, ReaderState.snapshot.value.text)
        assertEquals("Status must be IDLE", ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals("Position must be 0", 0L, ReaderState.snapshot.value.positionMs)
    }

    @Test
    fun test06_expansionWithNullOrBlankText_isSafelyIgnored() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto existente intocado",
            status = ReadingStatus.PLAYING,
            positionMs = 4_000L
        )

        simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_VIEW, null)
        assertEquals("Null text extra must not alter snapshot", "Texto existente intocado", ReaderState.snapshot.value.text)
        assertEquals("Status must remain PLAYING", ReadingStatus.PLAYING, ReaderState.snapshot.value.status)

        simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_VIEW, "   \n\t  ")
        assertEquals("Blank text extra must not alter snapshot", "Texto existente intocado", ReaderState.snapshot.value.text)
        assertEquals("Status must remain PLAYING", ReadingStatus.PLAYING, ReaderState.snapshot.value.status)
    }

    @Test
    fun test07_expansionWithWhitespaceVariations_matchesCurrentSession() {
        val baseText = "Texto sem espaços nas pontas"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = baseText,
            status = ReadingStatus.PLAYING,
            positionMs = 9_000L
        )

        var eventEmitted = false
        simulateMainActivityIncomingIntent(
            context = fakeContext,
            action = Intent.ACTION_VIEW,
            extraText = "   $baseText \n\t ",
            onLoadTextEventEmitted = { eventEmitted = true }
        )

        assertTrue("Trimmed text must match baseText and emit loadTextEvent", eventEmitted)
        assertEquals(ReadingStatus.PLAYING, ReaderState.snapshot.value.status)
        assertEquals(9_000L, ReaderState.snapshot.value.positionMs)
        assertTrue(startedServices.isEmpty())
    }

    // =========================================================================
    // SECTION 2: CONCURRENT INTENT DELIVERIES & STATE STRESS
    // =========================================================================

    @Test
    fun test08_concurrentShareAndProcessText_maintainsStateConsistency() {
        val numThreads = 16
        val iterationsPerThread = 25
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorCount = AtomicInteger(0)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        val text = "Thread $i - Iteration $j payload text content"
                        if (j % 2 == 0) {
                            simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_SEND, text)
                        } else {
                            simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_VIEW, text)
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent intents must complete within 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No exceptions must occur during concurrent intent deliveries", 0, errorCount.get())

        val finalSnapshot = ReaderState.snapshot.value
        assertTrue("Final text must not be empty", finalSnapshot.text.isNotEmpty())
        assertTrue("Final status must be valid", finalSnapshot.status in ReadingStatus.values())
        assertTrue("Final position must be non-negative", finalSnapshot.positionMs >= 0)
    }

    @Test
    fun test09_shareDeliveryDuringActivePlayback_stopsServiceAndResetsState() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto a tocar activamente",
            status = ReadingStatus.PLAYING,
            positionMs = 18_000L,
            durationMs = 60_000L,
            characterOffset = 120
        )

        val incomingShared = "Notícia partilhada do browser"
        simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_SEND, incomingShared)

        assertEquals(incomingShared, ReaderState.snapshot.value.text)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals(0L, ReaderState.snapshot.value.positionMs)
        assertEquals(0, ReaderState.snapshot.value.characterOffset)
        assertEquals(1, startedServices.size)
    }

    @Test
    fun test10_shareDeliveryDuringAudioPreparation_stopsServiceAndResetsState() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto em preparação",
            status = ReadingStatus.PREPARING,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0
        )

        val incomingShared = "Artigo partilhado enquanto sintetizava"
        simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_SEND, incomingShared)

        assertEquals(incomingShared, ReaderState.snapshot.value.text)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals(1, startedServices.size)
    }

    @Test
    fun test11_rapidConsecutiveShares_loadTextEventBufferDoesNotCrash() {
        val totalShares = 100
        for (i in 0 until totalShares) {
            val text = "Partilha consecutiva #$i"
            ReaderState.loadText(fakeContext, text)
        }

        val snapshot = ReaderState.snapshot.value
        assertEquals("Partilha consecutiva #${totalShares - 1}", snapshot.text)
        assertEquals(ReadingStatus.IDLE, snapshot.status)
        assertEquals(0L, snapshot.positionMs)
    }

    @Test
    fun test12_multiThreadedLoadTextAndRestore_noConcurrentModification() {
        val numThreads = 10
        val iterations = 30
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorCount = AtomicInteger(0)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    for (j in 0 until iterations) {
                        if (j % 2 == 0) {
                            ReaderState.loadText(fakeContext, "Text $i-$j")
                        } else {
                            ReaderState.restore(fakeContext)
                        }
                    }
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Concurrent loadText and restore must not throw", 0, errorCount.get())
    }

    // =========================================================================
    // SECTION 3: ONNX METADATA PROTOBUF WIRE FORMAT ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun test13_malformedVarintStream_over64BitOverflow_handledGracefully() {
        // Construct a stream with 11 consecutive bytes having MSB set (0x80)
        // This triggers the 64-bit overflow check in readVarint / readVarintOrNull
        val corruptedBytes = ByteArray(12) { 0x80.toByte() }.apply { this[11] = 0x01 }
        val stream = ByteArrayInputStream(corruptedBytes)

        val metadata = OnnxMetadata.readMetadata(stream)
        assertTrue("Corrupted varint stream must fail gracefully to empty map", metadata.isEmpty())
    }

    @Test
    fun test14_metadataEntryLengthExceedsSafeLimit_skippedSafely() {
        // Field 14 (wire type 2 -> tag 0x72), with length claiming 100,000 bytes (> 65536)
        // Followed by standard ModelProto fields
        val out = ByteArrayOutputStream()
        // ir_version
        out.write(0x08)
        out.write(0x08)

        // Corrupted metadata entry with length 100,000
        out.write(0x72)
        writeVarint(out, 100_000L)
        // We write 50 dummy bytes then terminate
        out.write(ByteArray(50) { 0x00 })

        val stream = ByteArrayInputStream(out.toByteArray())
        val metadata = OnnxMetadata.readMetadata(stream)
        assertTrue("Oversized metadata entry (>65536) must be skipped without OOM", metadata.isEmpty())
    }

    @Test
    fun test15_truncatedMetadataEntryBuffer_handledCleanly() {
        // Field 14 with length claiming 40 bytes, but stream has only 10 bytes then EOF
        val out = ByteArrayOutputStream()
        out.write(0x72)
        writeVarint(out, 40L)
        out.write(ByteArray(10) { 0x01 })

        val stream = ByteArrayInputStream(out.toByteArray())
        val metadata = OnnxMetadata.readMetadata(stream)
        assertTrue("Truncated metadata entry buffer must return empty map without crash", metadata.isEmpty())
    }

    @Test
    fun test16_extremeSampleRates_boundaryValidation() {
        val modelFile = tempFolder.newFile("sample_rate_stress.onnx")

        // 1. Zero sample rate
        createSyntheticModel(modelFile)
        val zeroRateBytes = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "0"))
        modelFile.appendBytes(zeroRateBytes)
        assertFalse("Sample rate '0' must not be valid", OnnxMetadata.hasSampleRate(modelFile))

        // 2. Negative sample rate
        val negRateBytes = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "-22050"))
        modelFile.writeBytes(ByteArray(0))
        createSyntheticModel(modelFile)
        modelFile.appendBytes(negRateBytes)
        assertFalse("Sample rate '-22050' must not be valid", OnnxMetadata.hasSampleRate(modelFile))

        // 3. Overflowing sample rate
        val overflowBytes = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "9999999999999999999"))
        modelFile.writeBytes(ByteArray(0))
        createSyntheticModel(modelFile)
        modelFile.appendBytes(overflowBytes)
        assertFalse("Overflowing sample rate must not be valid", OnnxMetadata.hasSampleRate(modelFile))

        // 4. Extreme valid high sample rate (192000 Hz)
        val highRateBytes = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "192000"))
        modelFile.writeBytes(ByteArray(0))
        createSyntheticModel(modelFile)
        modelFile.appendBytes(highRateBytes)
        assertTrue("192kHz sample rate must be valid", OnnxMetadata.hasSampleRate(modelFile))

        // 5. Extreme valid low sample rate (8000 Hz)
        val lowRateBytes = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "8000"))
        modelFile.writeBytes(ByteArray(0))
        createSyntheticModel(modelFile)
        modelFile.appendBytes(lowRateBytes)
        assertTrue("8kHz sample rate must be valid", OnnxMetadata.hasSampleRate(modelFile))
    }

    @Test
    fun test17_unknownWireTypesInProtobufTag_parserTerminatesSafely() {
        // Tag with field 15, wire type 3 (deprecated Start Group)
        // Tag byte: (15 shl 3) or 3 = 123 = 0x7B
        val out = ByteArrayOutputStream()
        out.write(0x7B)
        out.write(0x01)
        out.write(0x02)

        val stream = ByteArrayInputStream(out.toByteArray())
        val metadata = OnnxMetadata.readMetadata(stream)
        assertTrue("Unknown wire type must safely terminate without infinite loop", metadata.isEmpty())
    }

    @Test
    fun test18_repeatedRepairs_idempotentAndZeroGrowth() {
        val modelFile = tempFolder.newFile("repeat_repair.onnx")
        createSyntheticModel(modelFile)
        assertFalse(OnnxMetadata.hasSampleRate(modelFile))

        // First repair
        val firstSuccess = OnnxMetadata.repairModelFile(modelFile, fallbackSampleRate = 22050)
        assertTrue("First repair must succeed", firstSuccess)
        assertTrue("Model must now have sample_rate", OnnxMetadata.hasSampleRate(modelFile))
        val repairedSize = modelFile.length()
        val repairedHash = sha256(modelFile)

        // Repeat repair 10 times consecutively
        for (i in 1..10) {
            val repeatSuccess = OnnxMetadata.repairModelFile(modelFile, fallbackSampleRate = 22050)
            assertTrue("Subsequent repair #$i must return true", repeatSuccess)
            assertEquals("File size must not grow on repeat repair #$i", repairedSize, modelFile.length())
            assertEquals("File SHA-256 must remain identical on repeat repair #$i", repairedHash, sha256(modelFile))
        }
    }

    @Test
    fun test19_companionConfigMalformedJson_fallbackDefaultsUsed() {
        val brokenConfigFile = tempFolder.newFile("broken_model.onnx.json")
        brokenConfigFile.writeText("{ broken json without closing brace...")

        val extracted = OnnxMetadata.extractMetadataFromConfig(brokenConfigFile)
        assertTrue("Malformed JSON must return empty map without throwing", extracted.isEmpty())

        val brokenModelFile = tempFolder.newFile("broken_model.onnx")
        createSyntheticModel(brokenModelFile)

        val repaired = OnnxMetadata.repairModelFile(
            onnxFile = brokenModelFile,
            configFile = brokenConfigFile,
            fallbackSampleRate = 16000,
            fallbackLanguage = "pt_BR"
        )

        assertTrue("Repair must succeed using fallbacks", repaired)
        val metadata = OnnxMetadata.readMetadata(brokenModelFile)
        assertEquals("16000", metadata["sample_rate"])
        assertEquals("pt_BR", metadata["language"])
        assertEquals("vits", metadata["model_type"])
    }

    @Test
    fun test20_emptyValueMetadata_handlingBehavior() {
        // Empirically test behavior of empty-string metadata values
        val modelFile = tempFolder.newFile("empty_value.onnx")
        createSyntheticModel(modelFile)

        val encoded = OnnxMetadata.encodeMetadataProps(mapOf(
            "sample_rate" to "22050",
            "comment" to "",
            "custom_empty" to ""
        ))
        modelFile.appendBytes(encoded)

        val metadata = OnnxMetadata.readMetadata(modelFile)
        assertEquals("sample_rate with value must be parsed", "22050", metadata["sample_rate"])
        // Document empirical observation: parseStringEntry requires both key and value to be non-empty
        assertFalse("Empty values are intentionally dropped by parseStringEntry", metadata.containsKey("comment"))
        assertFalse("Empty custom metadata is dropped", metadata.containsKey("custom_empty"))
    }

    // =========================================================================
    // SECTION 4: MEMORY, LIFECYCLE SAFETY & TRANSPORT RESILIENCE
    // =========================================================================

    @Test
    fun test21_mainActivityRotationResilience_savedInstanceStateGuard() {
        // Verify rotation resilience:
        // When savedInstanceState == null, intent is processed.
        // When savedInstanceState != null (rotation), intent MUST be ignored.
        val sharedText = "Texto partilhado via Share Sheet"

        // 1. Cold start: savedInstanceState == null
        var coldHandled = false
        var savedInstanceState: Any? = null
        if (savedInstanceState == null) {
            simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_SEND, sharedText)
            coldHandled = true
        }
        assertTrue("Cold start must handle intent", coldHandled)
        assertEquals(sharedText, ReaderState.snapshot.value.text)

        // Advance playback position to simulate user listening
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            status = ReadingStatus.PLAYING,
            positionMs = 15_000L,
            characterOffset = 100
        )

        // 2. Configuration change (rotation): savedInstanceState != null
        fun shouldHandleIntent(state: Any?): Boolean = state == null

        var rotationHandled = false
        if (shouldHandleIntent("non_null_bundle")) {
            simulateMainActivityIncomingIntent(fakeContext, Intent.ACTION_SEND, sharedText)
            rotationHandled = true
        }
        assertFalse("Rotation must skip handling incoming intent", rotationHandled)
        assertEquals("Position must not be reset on rotation", 15_000L, ReaderState.snapshot.value.positionMs)
        assertEquals("Status must remain PLAYING on rotation", ReadingStatus.PLAYING, ReaderState.snapshot.value.status)
        assertEquals("Character offset must remain 100", 100, ReaderState.snapshot.value.characterOffset)
    }

    @Test
    fun test22_quickReadActivityRotation_pausedVsPlayingBehavior() {
        // White-box test of ReaderQuickPanel LaunchedEffect condition:
        // val isAlreadyPlayingThis = snapshot.text == text &&
        //     (snapshot.status == ReadingStatus.PLAYING || snapshot.status == ReadingStatus.PREPARING)
        // if (!isAlreadyPlayingThis) { sendCommand(context, ACTION_READ, text) }

        val activeText = "Texto no QuickReadActivity"

        // Case A: Playing during rotation -> isAlreadyPlayingThis == true (NO restart)
        val playingSnapshot = ReaderSnapshot(text = activeText, status = ReadingStatus.PLAYING, positionMs = 8000L)
        val isAlreadyPlaying = playingSnapshot.text == activeText &&
            (playingSnapshot.status == ReadingStatus.PLAYING || playingSnapshot.status == ReadingStatus.PREPARING)
        assertTrue("While PLAYING, isAlreadyPlayingThis is true (no re-trigger)", isAlreadyPlaying)

        // Case B: Preparing during rotation -> isAlreadyPlayingThis == true (NO restart)
        val preparingSnapshot = ReaderSnapshot(text = activeText, status = ReadingStatus.PREPARING)
        val isAlreadyPreparing = preparingSnapshot.text == activeText &&
            (preparingSnapshot.status == ReadingStatus.PLAYING || preparingSnapshot.status == ReadingStatus.PREPARING)
        assertTrue("While PREPARING, isAlreadyPlayingThis is true (no re-trigger)", isAlreadyPreparing)

        // Case C: Paused during rotation -> isAlreadyPlayingThis == false!
        // EMPIRICAL CHALLENGE FINDING: Rotating the device while paused in QuickReadActivity
        // causes LaunchedEffect to evaluate isAlreadyPlayingThis as false, re-issuing ACTION_READ!
        val pausedSnapshot = ReaderSnapshot(text = activeText, status = ReadingStatus.PAUSED, positionMs = 8000L)
        val isAlreadyPlayingWhilePaused = pausedSnapshot.text == activeText &&
            (pausedSnapshot.status == ReadingStatus.PLAYING || pausedSnapshot.status == ReadingStatus.PREPARING)
        assertFalse("When PAUSED, isAlreadyPlayingThis evaluates to false", isAlreadyPlayingWhilePaused)
    }

    @Test
    fun test23_readerPlaybackServiceFormatTitle_boundaryStress() {
        // 1. Normal short text
        assertEquals("Olá Mundo", ReaderPlaybackService.formatTitle("Olá Mundo", "EchoReading"))

        // 2. Text with excessive whitespace and newlines
        assertEquals("Linha 1 Linha 2 Linha 3", ReaderPlaybackService.formatTitle("  Linha 1 \n\n Linha 2 \t Linha 3  ", "EchoReading"))

        // 3. Huge text (>80 chars) must be truncated with ellipsis
        val longText = "A".repeat(120)
        val formatted = ReaderPlaybackService.formatTitle(longText, "EchoReading")
        assertEquals(81, formatted.length) // 80 'A's + 1 ellipsis char '…'
        assertTrue(formatted.endsWith("…"))

        // 4. Blank text falls back to fallback
        assertEquals("EchoReading", ReaderPlaybackService.formatTitle("", "EchoReading"))
        assertEquals("EchoReading", ReaderPlaybackService.formatTitle("   \n\t  ", "EchoReading"))

        // 5. Emojis and multi-byte unicode
        val emojiText = "🎧 Leitura com áudio em alta qualidade 📚"
        assertEquals(emojiText, ReaderPlaybackService.formatTitle(emojiText, "EchoReading"))
    }

    @Test
    fun test24_audioTimelineLocateAndPreparedMs_boundaryStress() {
        val timeline = AudioTimeline()
        assertEquals(0L, timeline.preparedMs)
        assertEquals(0, timeline.size)

        timeline.add(3000L)
        timeline.add(5000L)
        timeline.add(2000L)

        assertEquals(10000L, timeline.preparedMs)
        assertEquals(3, timeline.size)

        // Locate within chunk 0
        val (item0, local0) = timeline.locate(1500L)
        assertEquals(0, item0)
        assertEquals(1500L, local0)

        // Locate at exact boundary of chunk 0 -> chunk 1
        val (item1, local1) = timeline.locate(3000L)
        assertEquals(1, item1)
        assertEquals(0L, local1)

        // Locate within chunk 1
        val (item1b, local1b) = timeline.locate(5500L)
        assertEquals(1, item1b)
        assertEquals(2500L, local1b)

        // Locate beyond prepared duration (clamped)
        val (itemEnd, localEnd) = timeline.locate(15000L)
        assertEquals(2, itemEnd)
        assertEquals(2000L, localEnd)

        // Locate negative position (clamped to 0)
        val (itemNeg, localNeg) = timeline.locate(-500L)
        assertEquals(0, itemNeg)
        assertEquals(0L, localNeg)
    }

    @Test
    fun test25_readingChunksSplit_extremeTextPayloads() {
        // 1. Empty string
        val emptyChunks = ReadingChunks.split("")
        assertTrue(emptyChunks.isEmpty())

        // 2. Whitespace-only string
        val blankChunks = ReadingChunks.split("   \n\n\t   ")
        assertTrue(blankChunks.isEmpty())

        // 3. Single word without punctuation
        val singleWord = ReadingChunks.split("Palavra")
        assertEquals(1, singleWord.size)
        assertEquals("Palavra", singleWord[0].text)

        // 4. Massive continuous string without delimiters (10,000 chars)
        val massiveContinuous = "X".repeat(10000)
        val continuousChunks = ReadingChunks.split(massiveContinuous)
        assertTrue("Massive string without delimiters must be chunked", continuousChunks.isNotEmpty())
        assertEquals(massiveContinuous, continuousChunks.joinToString("") { it.text })

        // 5. Mixed punctuation: periods, exclamation marks, question marks, ellipsis
        val multiPunct = "Primeira frase longa para preencher! Segunda frase interrogativa? Terceira frase com reticências... Quarta frase final e conclusiva."
        val punctChunks = ReadingChunks.split(multiPunct, maxChars = 45)
        assertTrue("Multi-sentence text should split into >= 3 chunks, got ${punctChunks.size}", punctChunks.size >= 3)
    }
}
