package com.echoreading

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.CachedReading
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
import com.echoreading.share.ShareIntentHandler
import com.echoreading.voice.OnnxMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone M4 Tier 5 Adversarial Coverage Hardening Suite.
 *
 * Exhaustive white-box stress verification covering:
 * - Group 1: Intent Routing & Continuous Playback Expansion Contract (M2 ↔ M3)
 * - Group 2: Protobuf Wire Format, Varint Stress & Malformed Binary Recovery (M1)
 * - Group 3: ClipData Fallback & Security Hardening (M3)
 * - Group 4: Audio Cache, Timeline Numerical Boundaries & Chunking Invariants (M2 & M3)
 */
class AdversarialTier5HardeningTest {

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

    /**
     * Executes the exact intent routing contract defined by MainActivity.handleIncomingIntent.
     */
    private fun dispatchIncomingIntentContract(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND) {
            val sharedText = ShareIntentHandler.extractText(intent)
            if (!sharedText.isNullOrBlank()) {
                ReaderState.loadText(context, sharedText)
            }
        } else if (action == Intent.ACTION_VIEW) {
            val viewText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            if (!viewText.isNullOrBlank()) {
                val currentSnapshot = ReaderState.snapshot.value
                val isCurrentSession = currentSnapshot.text == viewText &&
                    (currentSnapshot.status == ReadingStatus.PLAYING ||
                     currentSnapshot.status == ReadingStatus.PAUSED ||
                     currentSnapshot.status == ReadingStatus.PREPARING)
                if (isCurrentSession) {
                    // QuickReadActivity expansion: preserve continuous audio playback
                    ReaderState.loadTextEvent.tryEmit(viewText)
                } else {
                    ReaderState.loadText(context, viewText)
                }
            }
        }
    }

    // =========================================================================
    // GROUP 1: INTENT ROUTING & CONTINUOUS PLAYBACK EXPANSION CONTRACT (M2 ↔ M3)
    // =========================================================================

    @Test
    fun t01_actionView_withMatchingTextWhilePlaying_preservesPlaybackAndEmitsEvent() {
        val activeText = "Capítulo 1: O início da jornada literária"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = activeText,
            status = ReadingStatus.PLAYING,
            positionMs = 12450L,
            durationMs = 45000L,
            characterOffset = 180,
            voiceId = "pt-PT",
            speed = 1.25f
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) activeText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        // 1. Playback service must NOT have been sent ACTION_STOP
        assertTrue("Playback must not be stopped on expansion of active session", startedServices.isEmpty())

        // 2. State machine invariants must be strictly preserved
        val current = ReaderState.snapshot.value
        assertEquals("Status must remain PLAYING", ReadingStatus.PLAYING, current.status)
        assertEquals("Position must not reset", 12450L, current.positionMs)
        assertEquals("Duration must remain intact", 45000L, current.durationMs)
        assertEquals("Offset must not reset", 180, current.characterOffset)
        assertEquals("Text must remain identical", activeText, current.text)
    }

    @Test
    fun t02_actionView_withMatchingTextWhilePaused_preservesPausedSession() {
        val activeText = "Artigo pausado pelo utilizador"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = activeText,
            status = ReadingStatus.PAUSED,
            positionMs = 8200L,
            durationMs = 25000L,
            characterOffset = 90
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) activeText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        assertTrue("Playback must not be stopped on expansion of paused session", startedServices.isEmpty())
        val current = ReaderState.snapshot.value
        assertEquals("Status must remain PAUSED", ReadingStatus.PAUSED, current.status)
        assertEquals("Position must be retained", 8200L, current.positionMs)
        assertEquals("Offset must be retained", 90, current.characterOffset)
    }

    @Test
    fun t03_actionView_withMatchingTextWhilePreparing_preservesPreparingSession() {
        val activeText = "Texto em preparação para síntese"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = activeText,
            status = ReadingStatus.PREPARING,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) activeText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        assertTrue("Preparing state must not be interrupted", startedServices.isEmpty())
        assertEquals(ReadingStatus.PREPARING, ReaderState.snapshot.value.status)
    }

    @Test
    fun t04_actionView_withMatchingTextWhileIdle_triggersLoadTextAndResetsOffset() {
        val sameText = "Texto que estava ocioso no leitor"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = sameText,
            status = ReadingStatus.IDLE,
            positionMs = 5000L,
            characterOffset = 120
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) sameText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        val current = ReaderState.snapshot.value
        assertEquals(ReadingStatus.IDLE, current.status)
        assertEquals("Position must be reset to 0 for idle session reload", 0L, current.positionMs)
        assertEquals("Offset must be reset to 0 for idle session reload", 0, current.characterOffset)
    }

    @Test
    fun t05_actionView_withMatchingTextWhileError_clearsErrorAndResetsToIdle() {
        val errText = "Texto com erro prévio de áudio"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = errText,
            status = ReadingStatus.ERROR,
            error = "AudioTrack allocation failed",
            positionMs = 3000L,
            characterOffset = 45
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) errText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        val current = ReaderState.snapshot.value
        assertEquals(ReadingStatus.IDLE, current.status)
        assertNull("Error must be cleared on re-loading text", current.error)
        assertEquals(0L, current.positionMs)
        assertEquals(0, current.characterOffset)
    }

    @Test
    fun t06_actionView_withDifferentTextWhilePlaying_stopsOldPlaybackAndLoadsNew() {
        val oldText = "Notícia antiga a tocar"
        val newText = "Nova notícia partilhada para leitura"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = oldText,
            status = ReadingStatus.PLAYING,
            positionMs = 18000L,
            characterOffset = 300
        )

        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_VIEW
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) newText else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        // Must stop previous playback
        assertTrue("Previous audio playback must be stopped when new text arrives", startedServices.isNotEmpty())
        val current = ReaderState.snapshot.value
        assertEquals(newText, current.text)
        assertEquals(ReadingStatus.IDLE, current.status)
        assertEquals(0L, current.positionMs)
        assertEquals(0, current.characterOffset)
    }

    @Test
    fun t07_actionView_withBlankOrEmptyText_safelyIgnored() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto ativo intacto",
            status = ReadingStatus.PLAYING,
            positionMs = 4000L
        )

        val blankIntents = listOf(
            object : Intent() {
                override fun getAction(): String = Intent.ACTION_VIEW
                override fun getCharSequenceExtra(name: String?): CharSequence? = ""
            },
            object : Intent() {
                override fun getAction(): String = Intent.ACTION_VIEW
                override fun getCharSequenceExtra(name: String?): CharSequence? = "   \n\t  "
            },
            object : Intent() {
                override fun getAction(): String = Intent.ACTION_VIEW
                override fun getCharSequenceExtra(name: String?): CharSequence? = null
            }
        )

        for (intent in blankIntents) {
            dispatchIncomingIntentContract(fakeContext, intent)
            assertEquals("Texto ativo intacto", ReaderState.snapshot.value.text)
            assertEquals(ReadingStatus.PLAYING, ReaderState.snapshot.value.status)
            assertTrue(startedServices.isEmpty())
        }
    }

    @Test
    fun t08_actionSend_withValidText_loadsTextAndResetsState() {
        val shared = "Artigo partilhado via WhatsApp para o leitor"
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? =
                if (name == Intent.EXTRA_TEXT) shared else null
        }

        dispatchIncomingIntentContract(fakeContext, intent)

        val current = ReaderState.snapshot.value
        assertEquals(shared, current.text)
        assertEquals(ReadingStatus.IDLE, current.status)
        assertEquals(0L, current.positionMs)
        assertEquals(0, current.characterOffset)
    }

    @Test
    fun t09_actionSend_withNonTextMime_isSafelyIgnored() {
        ReaderState.snapshot.value = ReaderSnapshot(text = "Estado preservado")
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "application/pdf"
            override fun getCharSequenceExtra(name: String?): CharSequence? = "Documento em PDF"
        }

        dispatchIncomingIntentContract(fakeContext, intent)
        assertEquals("Estado preservado", ReaderState.snapshot.value.text)
    }

    // =========================================================================
    // GROUP 2: PROTOBUF WIRE FORMAT, VARINT STRESS & BINARY RECOVERY (M1)
    // =========================================================================

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun readVarint(input: java.io.InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            val b = input.read()
            if (b == -1) throw java.io.EOFException("Unexpected EOF while reading varint")
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Malformed varint: exceeds 64 bits")
    }

    @Test
    fun t10_protobufVarint_roundTripStressAllBoundaries() {
        val boundaryValues = listOf(
            0L, 1L, 2L, 63L, 64L, 127L, 128L, 255L, 256L,
            16383L, 16384L, 2097151L, 2097152L,
            268435455L, 268435456L, 34359738367L, 34359738368L,
            Int.MAX_VALUE.toLong(),
            Long.MAX_VALUE
        )

        for (expected in boundaryValues) {
            val bos = ByteArrayOutputStream()
            writeVarint(bos, expected)
            val bytes = bos.toByteArray()
            val actual = readVarint(ByteArrayInputStream(bytes))
            assertEquals("Varint round-trip failed for value $expected", expected, actual)
        }
    }

    @Test
    fun t11_protobufWireTypes_gracefulScanHandling() {
        val bos = ByteArrayOutputStream()
        // 1. Valid field 1, wire 0 (varint): ir_version = 8
        bos.write(0x08)
        writeVarint(bos, 8L)

        // 2. Field 3, wire 1 (64-bit fixed): 8 bytes payload
        bos.write((3 shl 3) or 1)
        bos.write(ByteArray(8) { 0xAA.toByte() })

        // 3. Field 4, wire 5 (32-bit fixed): 4 bytes payload
        bos.write((4 shl 3) or 5)
        bos.write(ByteArray(4) { 0xBB.toByte() })

        // 4. Field 7, wire 2 (length-delimited): GraphProto payload (128 bytes)
        bos.write((7 shl 3) or 2)
        writeVarint(bos, 128L)
        bos.write(ByteArray(128) { 0xCC.toByte() })

        // 5. Field 14, wire 2: StringStringEntryProto (metadata_props: sample_rate=22050)
        val entryBos = ByteArrayOutputStream()
        val key = "sample_rate".toByteArray(Charsets.UTF_8)
        val value = "22050".toByteArray(Charsets.UTF_8)
        entryBos.write(0x0A)
        writeVarint(entryBos, key.size.toLong())
        entryBos.write(key)
        entryBos.write(0x12)
        writeVarint(entryBos, value.size.toLong())
        entryBos.write(value)

        val entryBytes = entryBos.toByteArray()
        bos.write(0x72) // (14 shl 3) | 2
        writeVarint(bos, entryBytes.size.toLong())
        bos.write(entryBytes)

        // 6. Unknown legacy wire type (e.g. wire type 3 - deprecated start group)
        bos.write((99 shl 3) or 3)

        val modelBytes = bos.toByteArray()
        val file = tempFolder.newFile("wire_types_test.onnx")
        file.writeBytes(modelBytes)

        val metadata = OnnxMetadata.readMetadata(file)
        assertEquals("sample_rate must be successfully parsed despite other wire types", "22050", metadata["sample_rate"])
        assertTrue("hasSampleRate must be true", OnnxMetadata.hasSampleRate(file))
    }

    @Test
    fun t12_protobufEntry_reversedKeyValueOrder_parsesCorrectly() {
        val entryBos = ByteArrayOutputStream()
        val key = "model_type".toByteArray(Charsets.UTF_8)
        val value = "vits".toByteArray(Charsets.UTF_8)

        // Value (field 2) written BEFORE Key (field 1)
        entryBos.write(0x12)
        writeVarint(entryBos, value.size.toLong())
        entryBos.write(value)

        entryBos.write(0x0A)
        writeVarint(entryBos, key.size.toLong())
        entryBos.write(key)

        val entryBytes = entryBos.toByteArray()
        val modelBos = ByteArrayOutputStream()
        modelBos.write(0x72)
        writeVarint(modelBos, entryBytes.size.toLong())
        modelBos.write(entryBytes)

        val file = tempFolder.newFile("reversed_key_value.onnx")
        file.writeBytes(modelBos.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("vits", meta["model_type"])
    }

    @Test
    fun t13_protobufEntry_unknownFieldInsideEntry_safelySkipped() {
        val entryBos = ByteArrayOutputStream()
        val key = "sample_rate".toByteArray(Charsets.UTF_8)
        val value = "16000".toByteArray(Charsets.UTF_8)

        // Key (field 1)
        entryBos.write(0x0A)
        writeVarint(entryBos, key.size.toLong())
        entryBos.write(key)

        // Unknown nested field 3 (wire type 0 varint)
        entryBos.write((3 shl 3) or 0)
        writeVarint(entryBos, 9999L)

        // Unknown nested field 4 (wire type 2 length-delimited)
        val dummy = "dummy_extension".toByteArray(Charsets.UTF_8)
        entryBos.write((4 shl 3) or 2)
        writeVarint(entryBos, dummy.size.toLong())
        entryBos.write(dummy)

        // Value (field 2)
        entryBos.write(0x12)
        writeVarint(entryBos, value.size.toLong())
        entryBos.write(value)

        val entryBytes = entryBos.toByteArray()
        val modelBos = ByteArrayOutputStream()
        modelBos.write(0x72)
        writeVarint(modelBos, entryBytes.size.toLong())
        modelBos.write(entryBytes)

        val file = tempFolder.newFile("unknown_entry_fields.onnx")
        file.writeBytes(modelBos.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("16000", meta["sample_rate"])
        assertTrue(OnnxMetadata.hasSampleRate(file))
    }

    @Test
    fun t14_protobufMetadata_nonAsciiUnicodeStress() {
        val complexProps = mapOf(
            "sample_rate" to "22050",
            "model_type" to "vits",
            "voice" to "Português (Portugal) · Tugão 🇵🇹",
            "comment" to "Piper síntese de voz de alta precisão com acentuação e cedilha: á, é, í, ó, ú, ç, ã, õ",
            "chinese_test" to "普通话语音模型",
            "arabic_test" to "النموذج الصوتي",
            "math_symbols" to "∑ √ ∞ ⚡"
        )

        val encoded = OnnxMetadata.encodeMetadataProps(complexProps)
        val file = tempFolder.newFile("unicode_metadata.onnx")
        file.writeBytes(encoded)

        val decoded = OnnxMetadata.readMetadata(file)
        assertEquals(complexProps.size, decoded.size)
        for ((k, expectedVal) in complexProps) {
            assertEquals("Mismatch for key $k", expectedVal, decoded[k])
        }
    }

    @Test
    fun t15_hasSampleRate_adversarialInputStrings() {
        val cases = listOf(
            "22050" to true,
            "16000" to true,
            "48000" to true,
            "1" to true,
            "0" to false,
            "-22050" to false,
            "-1" to false,
            "abc" to false,
            "" to false,
            "22050.5" to false,
            " 22050 " to false,
            "99999999999999999999" to false // Integer overflow
        )

        for ((str, expectedResult) in cases) {
            val file = tempFolder.newFile("sr_${str.hashCode()}.onnx")
            val encoded = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to str))
            file.writeBytes(encoded)

            val actual = OnnxMetadata.hasSampleRate(file)
            assertEquals("hasSampleRate check failed for string '$str'", expectedResult, actual)
        }
    }

    @Test
    fun t16_repairModelFile_companionConfigResolutionPrecedence() {
        val dir = tempFolder.newFolder("voice_precedence")
        val onnxFile = File(dir, "model.onnx")
        onnxFile.writeBytes(ByteArray(100) { 0x08 }) // Unpatched synthetic model

        // Precedence 1: Exact sibling <name>.onnx.json takes highest priority
        val exactJson = File(dir, "model.onnx.json")
        exactJson.writeText(JSONObject().apply {
            put("audio", JSONObject().apply { put("sample_rate", 24000) })
            put("num_speakers", 2)
        }.toString())

        // Also create a meta.json with different sample rate
        val metaJson = File(dir, "meta.json")
        metaJson.writeText(JSONObject().apply {
            put("sampleRate", 16000)
            put("languageCode", "pt_BR")
        }.toString())

        val success = OnnxMetadata.repairModelFile(onnxFile)
        assertTrue(success)
        val repairedMeta = OnnxMetadata.readMetadata(onnxFile)
        assertEquals("Exact sibling .onnx.json must take precedence", "24000", repairedMeta["sample_rate"])
        assertEquals("2", repairedMeta["n_speakers"])
    }

    @Test
    fun t17_concurrentMultiThreadedModelScanAndRepair() {
        val dir = tempFolder.newFolder("concurrent_models")
        val fileCount = 8
        val files = (0 until fileCount).map { i ->
            val f = File(dir, "voice_$i.onnx")
            val encoded = OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "${16000 + i * 1000}"))
            f.writeBytes(encoded)
            f
        }

        val threadCount = 16
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (rep in 0 until 50) {
                        val file = files[rep % fileCount]
                        val hasSr = OnnxMetadata.hasSampleRate(file)
                        if (!hasSr) errors.incrementAndGet()
                        val meta = OnnxMetadata.readMetadata(file)
                        if (!meta.containsKey("sample_rate")) errors.incrementAndGet()
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent scan must complete within 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Concurrent reads must have zero errors", 0, errors.get())
    }

    // =========================================================================
    // GROUP 3: CLIPDATA FALLBACK & SECURITY HARDENING (M3)
    // =========================================================================

    @Test
    fun t18_clipDataFallback_singleItemText_extractedSuccessfully() {
        val clipText = "Texto partilhado via área de transferência ClipData"
        val mockItem = object : ClipData.Item(clipText) {
            override fun getText(): CharSequence = clipText
        }
        val mockClipData = object : ClipData(ClipDescription("plain", arrayOf("text/plain")), mockItem) {
            override fun getItemCount(): Int = 1
            override fun getItemAt(index: Int): ClipData.Item = mockItem
        }
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? = null // EXTRA_TEXT missing
            override fun getClipData(): ClipData? = mockClipData
        }

        val extracted = ShareIntentHandler.extractText(intent)
        assertEquals(clipText, extracted)
    }

    @Test
    fun t19_clipDataFallback_multipleItems_extractsFirstItem() {
        val firstText = "Primeiro item relevante"
        val secondText = "Segundo item ignorado"
        val mockItem1 = object : ClipData.Item(firstText) {
            override fun getText(): CharSequence = firstText
        }
        val mockItem2 = object : ClipData.Item(secondText) {
            override fun getText(): CharSequence = secondText
        }
        val mockClipData = object : ClipData(ClipDescription("plain", arrayOf("text/plain")), mockItem1) {
            override fun getItemCount(): Int = 2
            override fun getItemAt(index: Int): ClipData.Item = if (index == 0) mockItem1 else mockItem2
        }
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? = null
            override fun getClipData(): ClipData? = mockClipData
        }

        val extracted = ShareIntentHandler.extractText(intent)
        assertEquals(firstText, extracted)
    }

    @Test
    fun t20_clipDataFallback_emptyClipData_returnsNull() {
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? = null
            override fun getClipData(): ClipData? {
                return object : ClipData("empty", arrayOf("text/plain"), ClipData.Item(null as CharSequence?)) {
                    override fun getItemCount(): Int = 0
                }
            }
        }

        val extracted = ShareIntentHandler.extractText(intent)
        assertNull(extracted)
    }

    @Test
    fun t21_clipDataFallback_nullItemText_returnsNull() {
        val intent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? = null
            override fun getClipData(): ClipData? {
                return ClipData(ClipDescription("empty", arrayOf("text/plain")), ClipData.Item(null as CharSequence?))
            }
        }

        val extracted = ShareIntentHandler.extractText(intent)
        assertNull(extracted)
    }

    @Test
    fun t22_exoticTextMimeTypes_acceptedAndRejectedStrictly() {
        val acceptedMimes = listOf(
            "text/plain",
            "text/*",
            "text/plain; charset=utf-8",
            "text/html",
            "text/markdown",
            "text/csv",
            "text/xml",
            "text/rtf"
        )

        for (mime in acceptedMimes) {
            val extracted = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = "Válido"
            )
            assertEquals("MIME type '$mime' must be accepted", "Válido", extracted)
        }

        val rejectedMimes = listOf(
            "application/text",
            "application/pdf",
            "image/jpeg",
            "image/png",
            "audio/wav",
            "video/mp4",
            "application/octet-stream",
            "",
            null
        )

        for (mime in rejectedMimes) {
            val extracted = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = "Inválido"
            )
            assertNull("MIME type '$mime' must be rejected", extracted)
        }
    }

    @Test
    fun t23_maliciousIntentExceptions_defensivelyCaught() {
        val evilIntent = object : Intent() {
            override fun getAction(): String = Intent.ACTION_SEND
            override fun getType(): String = "text/plain"
            override fun getCharSequenceExtra(name: String?): CharSequence? {
                throw SecurityException("Simulated SecurityException on untrusted extra extraction")
            }
            override fun getClipData(): ClipData? {
                throw RuntimeException("Simulated BadParcelableException")
            }
        }

        val extracted = ShareIntentHandler.extractText(evilIntent)
        assertNull("Exceptions during intent unwrapping must be caught safely", extracted)
    }

    @Test
    fun t24_concurrentLoadTextAndRestoreStress() {
        val threadCount = 12
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until iterationsPerThread) {
                        if (i % 2 == 0) {
                            ReaderState.loadText(fakeContext, "Text share from thread $i iter $j")
                        } else {
                            ReaderState.save(fakeContext)
                            ReaderState.restore(fakeContext)
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent operations must finish within 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero errors expected during heavy concurrent state updates", 0, errors.get())
        assertTrue("Final state text must be non-empty", ReaderState.snapshot.value.text.isNotEmpty())
    }

    // =========================================================================
    // GROUP 4: AUDIO CACHE, TIMELINE & CHUNKING INVARIANTS (M2 & M3)
    // =========================================================================

    @Test
    fun t25_audioTimeline_locate_exactBoundariesAndClamping() {
        val timeline = AudioTimeline()
        timeline.add(1000L) // chunk 0: [0, 1000)
        timeline.add(2000L) // chunk 1: [1000, 3000)
        timeline.add(1500L) // chunk 2: [3000, 4500)

        assertEquals(4500L, timeline.preparedMs)

        // 1. Position 0ms -> Chunk 0, offset 0
        assertEquals(0 to 0L, timeline.locate(0L))

        // 2. Exact boundary 1000ms -> Chunk 1, offset 0
        assertEquals(1 to 0L, timeline.locate(1000L))

        // 3. Inside chunk 1 (2500ms) -> Chunk 1, offset 1500
        assertEquals(1 to 1500L, timeline.locate(2500L))

        // 4. Exact boundary 3000ms -> Chunk 2, offset 0
        assertEquals(2 to 0L, timeline.locate(3000L))

        // 5. Exact total end 4500ms -> Chunk 2, offset 1500
        assertEquals(2 to 1500L, timeline.locate(4500L))

        // 6. Beyond end (9999ms) -> Clamped to Chunk 2, offset 1500
        assertEquals(2 to 1500L, timeline.locate(9999L))

        // 7. Negative position (-500ms) -> Clamped to Chunk 0, offset 0
        assertEquals(0 to 0L, timeline.locate(-500L))
    }

    @Test
    fun t26_audioTimeline_globalPosition_outOfBoundsIndices() {
        val timeline = AudioTimeline()
        timeline.add(2000L)
        timeline.add(3000L)

        // Negative item index -> clamped to 0
        val posNeg = timeline.globalPosition(-5, 500L)
        assertEquals(500L, posNeg)

        // Exceeding item index -> clamped to durations.size (sum of all durations + local)
        val posExceed = timeline.globalPosition(10, 200L)
        assertEquals(5200L, posExceed)

        // Negative local offset -> clamped to 0
        val posNegLocal = timeline.globalPosition(1, -100L)
        assertEquals(2000L, posNegLocal)
    }

    @Test
    fun t27_readerAudioCache_ttlExpiryAndSpeedTolerance() {
        val cacheDir = tempFolder.newFolder("cache_ttl_test")
        File(cacheDir, "0.wav").writeBytes(ByteArray(128))

        val text = "Texto para validação de TTL e tolerância de velocidade"
        val chunks = listOf(ReadingChunk(text, 0))
        val durations = listOf(1500L)
        val now = 1_000_000L

        ReaderAudioCache.put(
            text = text,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = durations,
            audioDir = cacheDir,
            timestamp = now
        )

        // Hit within TTL (< 5 min)
        val hit = ReaderAudioCache.get(text, "pt-PT", 1.0f, now = now + 4 * 60 * 1000L)
        assertNotNull("Cache must hit at 4 minutes", hit)

        // Speed matching tolerance: diff <= 0.01f
        val speedHit = ReaderAudioCache.get(text, "pt-PT", 1.008f, now = now + 1000L)
        assertNotNull("Cache must hit within 0.01 speed delta", speedHit)

        val speedMiss = ReaderAudioCache.get(text, "pt-PT", 1.05f, now = now + 1000L)
        assertNull("Cache must miss beyond 0.01 speed delta", speedMiss)

        // Miss and auto-clear after TTL (> 5 min)
        val expired = ReaderAudioCache.get(text, "pt-PT", 1.0f, now = now + 5 * 60 * 1000L + 1L)
        assertNull("Cache must expire after 5 minutes", expired)
        assertNull("Cache must be cleared after expiration", ReaderAudioCache.current)
    }

    @Test
    fun t28_readingChunks_split_complexPunctuationAndWhitespace() {
        val complexArticle = buildString {
            append("«A leitura em voz alta desenvolve competências linguísticas fundamentais...» ")
            append("afirmou o investigador — com convicção!\n\n")
            append("Será que a tecnologia offline responde a estes desafios? ")
            append("Certamente: rapidez, privacidade e acessibilidade garantidas.")
        }

        val chunks = ReadingChunks.split(complexArticle, maxChars = 80)
        assertTrue("Must split into multiple chunks", chunks.size >= 3)
        assertTrue("All chunks must be <= 80 characters", chunks.all { it.text.length <= 80 })
        assertTrue("No chunk text should start or end with whitespace", chunks.all { it.text == it.text.trim() })

        // Verify start indices monotonically increase
        for (i in 0 until chunks.size - 1) {
            assertTrue("Start index must increase", chunks[i].start < chunks[i + 1].start)
        }
    }
}
