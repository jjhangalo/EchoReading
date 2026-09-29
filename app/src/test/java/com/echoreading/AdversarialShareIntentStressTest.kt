package com.echoreading

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
import com.echoreading.share.ShareIntentHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M3:
 * System Share Sheet Direct Routing to Reader.
 *
 * Covers:
 * 1. Fuzzing ShareIntentHandler.extractText with exotic MIME types, parameters, malformed actions, nulls.
 * 2. Payload fuzzing: multi-megabyte payloads, zalgo, emojis, surrogate pairs, RTL, control characters.
 * 3. Boundary conditions: single chars, spaces, tabs, newlines, exact string preservation.
 * 4. Concurrency & rapid emissions on ReaderState.loadText & loadTextEvent verifying state invariants
 *    (status == IDLE, positionMs == 0, durationMs == 0, characterOffset == 0, error == null).
 * 5. End-to-end integration with ReadingChunks and ReaderAudioCache.
 */
class AdversarialShareIntentStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val originalSnapshot = ReaderState.snapshot.value
    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var fakeContext: Context

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = ReaderSnapshot()
        fakePrefs = FakeSharedPreferences()
        fakeContext = createFakeContext(fakePrefs)
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = originalSnapshot
    }

    // =========================================================================
    // IN-MEMORY FAKE CONTEXT & SHAREDPREFERENCES
    // =========================================================================

    private fun createFakeContext(prefs: FakeSharedPreferences): Context {
        return object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
            override fun getPackageName(): String = "com.echoreading"
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val map = Collections.synchronizedMap(mutableMapOf<String, Any>())

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(val target: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any>()
            private val removes = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                if (value != null) pending[key] = value else removes.add(key)
                return this
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                if (values != null) pending[key] = values else removes.add(key)
                return this
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                removes.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                synchronized(target) {
                    if (clearAll) target.clear()
                    removes.forEach { target.remove(it) }
                    target.putAll(pending)
                }
            }
        }
    }

    // =========================================================================
    // SECTION 1: MIME TYPE & ACTION FUZZING STRESS TESTS
    // =========================================================================

    @Test
    fun fuzz_validMimeTypesWithParameters() {
        val payload = "Artigo partilhado com parâmetros MIME"
        val parameterMimes = listOf(
            "text/plain; charset=UTF-8",
            "text/plain; charset=iso-8859-1",
            "text/plain; format=flowed",
            "text/html; charset=UTF-8",
            "text/csv; header=present",
            "text/markdown; variant=GFM",
            "text/tab-separated-values",
            "text/calendar; component=VEVENT",
            "text/xml; version=1.0",
            "text/css; charset=utf-8",
            "text/rtf; charset=ansi",
            "text/plain; charset=utf-8; boundary=\"----frontier\""
        )

        for (mime in parameterMimes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = payload
            )
            assertEquals("Expected valid extraction for MIME: $mime", payload, result)
        }
    }

    @Test
    fun fuzz_wildcardAndSubtypeTextMimes() {
        val payload = "Partilha genérica de texto"
        val wildcardAndSubtypes = listOf(
            "text/*",
            "text/anything",
            "text/x-diff",
            "text/x-patch",
            "text/yaml",
            "text/x-python",
            "text/x-kotlin"
        )

        for (mime in wildcardAndSubtypes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = payload
            )
            assertEquals("Expected valid extraction for text subtype: $mime", payload, result)
        }
    }

    @Test
    fun fuzz_rejectionOfExoticNonTextMimes() {
        val payload = "Texto anexado a ficheiro binário"
        val nonTextMimes = listOf(
            "application/pdf",
            "application/json",
            "application/octet-stream",
            "application/zip",
            "application/xml",
            "application/vnd.android.package-archive",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
            "image/svg+xml",
            "image/bmp",
            "image/tiff",
            "audio/mpeg",
            "audio/wav",
            "audio/flac",
            "audio/ogg",
            "audio/aac",
            "audio/mp4",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "video/x-matroska",
            "video/3gpp",
            "multipart/form-data",
            "multipart/mixed",
            "multipart/byteranges",
            "message/rfc822",
            "font/ttf",
            "font/woff",
            "font/woff2",
            "model/gltf-binary"
        )

        for (mime in nonTextMimes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = payload
            )
            assertNull("MIME type '$mime' must be strictly rejected", result)
        }
    }

    @Test
    fun fuzz_trickyMimeBoundaries() {
        val payload = "Conteúdo de teste"
        val trickyInvalidMimes = listOf(
            "text",                     // Missing slash
            "context/plain",            // Contains 'text' but does not start with 'text/'
            "subtext/plain",            // Contains 'text' as suffix of main type
            "application/text",         // Type is application, not text
            "image/text",               // Type is image
            "audio/text",               // Type is audio
            "",                         // Empty
            "/text/plain",              // Leading slash
            " text/plain",              // Leading space
            "TEXT/PLAIN",               // Uppercase
            "Text/Plain",               // Mixed case
            "text/plain\u0000injected"  // Embedded null in mime type - check behavior
        )

        for (mime in trickyInvalidMimes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = payload
            )
            if (mime.startsWith("text/")) {
                // If it starts with text/, handler accepts it
                assertNotNull("Handler accepted prefix text/: $mime", result)
            } else {
                assertNull("Tricky MIME '$mime' must return null", result)
            }
        }
    }

    @Test
    fun fuzz_malformedAndAdversarialActions() {
        val payload = "Texto válido para ação inválida"
        val malformedActions = listOf(
            null,
            "",
            " ",
            "android.intent.action.send",                               // Lowercase
            "android.intent.action.SEND ",                              // Trailing space
            " android.intent.action.SEND",                              // Leading space
            "android.intent.action.SEND_MULTIPLE",                      // Multiple send
            "android.intent.action.PROCESS_TEXT",                       // Process text (QuickRead)
            "android.intent.action.VIEW",                               // View
            "android.intent.action.MAIN",                               // Main launcher
            "android.intent.action.EDIT",                               // Edit
            "android.intent.action.CHOOSER",                            // Chooser
            "com.echoreading.action.SHARE",                             // Custom action
            "android.intent.action.SEND\u0000",                         // Null byte injection
            "android.intent.action.SEND' OR '1'='1",                    // SQL injection pattern
            "<script>alert('xss')</script>",                            // XSS tag
            "; rm -rf / ;",                                             // Shell injection pattern
            "A".repeat(65_536)                                          // 64KB giant string
        )

        for (action in malformedActions) {
            val result = ShareIntentHandler.extractText(
                action = action,
                mimeType = "text/plain",
                extraText = payload
            )
            assertNull("Malformed action '$action' must return null", result)
        }
    }

    // =========================================================================
    // SECTION 2: TEXT PAYLOAD FUZZING: UNICODE, ACCENTS, RTL, EMOJIS, ZALGO
    // =========================================================================

    @Test
    fun fuzz_whitespaceOnlyPayloads() {
        val whitespaceVariations = listOf(
            null,
            "",
            " ",
            "  ",
            "\t",
            "\n",
            "\r",
            "\r\n",
            "   \t   \n  \r\n   ",
            "\t\t\t\t\t\t",
            "\n\n\n\n\n\n"
        )

        for (ws in whitespaceVariations) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = ws
            )
            assertNull("Whitespace-only or null payload must return null", result)
        }
    }

    @Test
    fun fuzz_singleCharacterPayloads() {
        val singleChars = listOf("a", "Z", "1", "0", "ç", "ã", "é", ".", "!", "?", "@", "#", "$", "§", "€", "¥")

        for (ch in singleChars) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = ch
            )
            assertEquals("Single character '$ch' must be extracted exactly", ch, result)
        }
    }

    @Test
    fun fuzz_whitespaceTrimmingBoundaries() {
        val core = "Conteúdo Central de Notícia"
        val noisyVariants = listOf(
            "   $core",
            "$core   ",
            "   $core   ",
            "\t\t$core\t\t",
            "\n\n$core\n\n",
            "\r\n\t  $core  \t\r\n",
            " \n \t \r \n $core \r \n \t \n "
        )

        for (noisy in noisyVariants) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = noisy
            )
            assertEquals("Core text must be cleanly trimmed", core, result)
        }
    }

    @Test
    fun fuzz_preservesInternalWhitespacesAndNewlines() {
        val multilineWithGaps = "Primeiro Parágrafo\n\n   Segundo Parágrafo com indentação\n\n\nTerceiro Parágrafo"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = multilineWithGaps
        )
        assertEquals("Internal whitespace and paragraphs must be strictly preserved", multilineWithGaps, result)
    }

    @Test
    fun fuzz_portugueseAndEuropeanDiacritics() {
        val europeanTexts = listOf(
            "À noite, vovô comprou café, chá, pão e maçã no sótão de Belém: 100% português!",
            "¿Cómo estás? ¡Muy bien! Mañana será un día fantástico en España con pingüinos.",
            "Où sont passés les élèves à l'île de la Réunion? L'œuvre est créée avec sérénité.",
            "Übermäßige Grüße von Jürgen aus München: Süßigkeiten für alle Kinder im Schloss.",
            "Blåbærgrød på Rømø i Grønland og Færøerne: særpræget nordisk sprogprøve."
        )

        for (text in europeanTexts) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = text
            )
            assertEquals("European diacritic text must match perfectly", text, result)
        }
    }

    @Test
    fun fuzz_nonLatinScriptsAndMultilingual() {
        val multilingualTexts = listOf(
            "В чащах юга жил-был цитрус? Да, но фальшивый экземпляр!",
            "Ο καλύτερος τρόπος να προβλέψεις το μέλλον είναι να το δημιουργήσεις.",
            "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。",
            "道可道，非常道；名可名，非常名。天地之始，萬物之母。",
            "모든 인간은 태어날 때부터 자유로우며 그 존엄과 권리에 있어 동등하다.",
            "सभी मनुष्यों को गौरव और अधिकारों के मामले में जन्मजात स्वतन्त्रता प्राप्त है।"
        )

        for (text in multilingualTexts) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = text
            )
            assertEquals("Multilingual script must be preserved with exact fidelity", text, result)
        }
    }

    @Test
    fun fuzz_rightToLeftScriptsAndDirectionalFormatting() {
        val rtlTexts = listOf(
            "يولد جميع الناس أحراراً ومتساوين في الكرامة والحقوق.",
            "כל בני אדם נולדו בני חורין ושווים בערכם ובזכויותיהם.",
            "Mixed: English text with \u200E(LRM) and Hebrew עברית with \u200F(RLM) markers."
        )

        for (text in rtlTexts) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = text
            )
            assertEquals("RTL and BIDI directional markers must be preserved", text, result)
        }
    }

    @Test
    fun fuzz_emojisAndSurrogatePairIntegrity() {
        val emojiPayloads = listOf(
            "Texto simples com emoji 🚀 e livro 📖",
            "Sequência complexa: 👨‍👩‍👧‍👦 (Família ZWJ)",
            "Modificadores de tom de pele: 👍🏻 👍🏼 👍🏽 👍🏾 👍🏿",
            "Bandeiras internacionais: 🇵🇹 🇦🇴 🇧🇷 🇲🇿 🇨🇻 🇬🇼 🇸🇹 🇹🇱",
            "Surrogates: \uD83D\uDE00 \uD83D\uDE01 \uD83D\uDE02 \uD83D\uDE03"
        )

        for (payload in emojiPayloads) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = "text/plain",
                extraText = payload
            )
            assertEquals("Emoji sequences must not suffer surrogate truncation", payload, result)
            assertNotNull(result)
            assertEquals("Character length must match", payload.length, result!!.length)
        }
    }

    @Test
    fun fuzz_zalgoAndCombiningDiacriticsFlooding() {
        // Construct zalgo text by stacking combining diacritical marks (\u0300 - \u036F)
        val sb = StringBuilder("Zalgo Test: ")
        for (i in 0 until 50) {
            sb.append('e')
            for (j in 0x0300..0x0320) {
                sb.append(j.toChar())
            }
        }
        val zalgo = sb.toString()

        val startTime = System.currentTimeMillis()
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = zalgo
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull("Zalgo text extraction must not crash", result)
        assertEquals("Zalgo text must match completely", zalgo, result)
        assertTrue("Zalgo processing must be fast (< 100ms), took ${elapsed}ms", elapsed < 100)
    }

    @Test
    fun fuzz_asciiControlCharactersAndNullBytes() {
        val payloadWithControls = "Título\u0000com byte nulo\u0007bell\u0008backspace\u001b[31mescape."
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = payloadWithControls
        )
        assertNotNull(result)
        assertEquals("Control characters within payload must not throw or truncate", payloadWithControls, result)
    }

    // =========================================================================
    // SECTION 3: EXTREME PAYLOAD & PERFORMANCE STRESS TESTS
    // =========================================================================

    @Test
    fun stress_oneMegabytePayload() {
        val chunk = "EchoReading 2.0 sistema de síntese vocal para leitura contínua. " // 64 chars
        val repetitions = 16_000 // ~1,024,000 chars
        val giant = chunk.repeat(repetitions)

        val startTime = System.currentTimeMillis()
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = giant
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull(result)
        assertEquals(giant.trim().length, result!!.length)
        assertTrue("1MB payload must process in under 200ms, took ${elapsed}ms", elapsed < 200)
    }

    @Test
    fun stress_twoMegabytePayload() {
        val chunk = "Artigo enciclopédico de grandes dimensões para teste de memória. " // 65 chars
        val repetitions = 32_000 // ~2,080,000 chars
        val giant = "   " + chunk.repeat(repetitions) + "   "

        val startTime = System.currentTimeMillis()
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = giant
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull(result)
        assertEquals(giant.trim(), result)
        assertTrue("2MB payload must process in under 500ms, took ${elapsed}ms", elapsed < 500)
    }

    @Test
    fun stress_customCharSequenceImplementation() {
        // Custom CharSequence implementation that lazily generates characters
        val customSequence = object : CharSequence {
            private val backing = "Texto a partir de CharSequence personalizado"
            override val length: Int get() = backing.length
            override fun get(index: Int): Char = backing[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = backing.subSequence(startIndex, endIndex)
            override fun toString(): String = backing
        }

        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = customSequence
        )
        assertEquals("Texto a partir de CharSequence personalizado", result)
    }

    // =========================================================================
    // SECTION 4: RAPID EMISSIONS & STATE INVARIANTS ON ReaderState.loadText
    // =========================================================================

    @Test
    fun stress_rapidSequentialLoadTextEmissions_500Iterations() {
        for (i in 1..500) {
            val text = "Texto de teste rápido número $i para validação de invariantes."
            ReaderState.loadText(fakeContext, text)

            val current = ReaderState.snapshot.value
            // Invariant 1: status == IDLE
            assertEquals("Status must be IDLE on iteration $i", ReadingStatus.IDLE, current.status)
            // Invariant 2: positionMs == 0L
            assertEquals("positionMs must be 0L on iteration $i", 0L, current.positionMs)
            // Invariant 3: durationMs == 0L
            assertEquals("durationMs must be 0L on iteration $i", 0L, current.durationMs)
            // Invariant 4: characterOffset == 0
            assertEquals("characterOffset must be 0 on iteration $i", 0, current.characterOffset)
            // Invariant 5: error == null
            assertNull("error must be null on iteration $i", current.error)
            // Invariant 6: text == text.trim()
            assertEquals("text must match cleaned string on iteration $i", text, current.text)

            // Invariant 7: SharedPreferences draft and text updated
            assertEquals(text, fakePrefs.getString("draft", null))
            assertEquals(text, fakePrefs.getString("text", null))
            assertEquals(0L, fakePrefs.getLong("position_ms", -1L))
            assertEquals(0, fakePrefs.getInt("character_offset", -1))
        }
    }

    @Test
    fun stress_concurrentLoadTextEmissions_multiThreaded() {
        val threadCount = 8
        val emissionsPerThread = 50
        val totalCalls = threadCount * emissionsPerThread
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (threadIndex in 0 until threadCount) {
            executor.submit {
                try {
                    startLatch.await()
                    for (c in 0 until emissionsPerThread) {
                        val text = "T${threadIndex}-E${c}: Conteúdo partilhado concorrente"
                        ReaderState.loadText(fakeContext, text)
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Fire all threads simultaneously
        startLatch.countDown()
        val completed = doneLatch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("All concurrent loadText workers must complete within timeout", completed)
        assertEquals("Zero errors during concurrent loadText execution", 0, errors.get())

        // Validate final state invariants
        val finalSnapshot = ReaderState.snapshot.value
        assertEquals("Final status must be IDLE", ReadingStatus.IDLE, finalSnapshot.status)
        assertEquals("Final positionMs must be 0L", 0L, finalSnapshot.positionMs)
        assertEquals("Final durationMs must be 0L", 0L, finalSnapshot.durationMs)
        assertEquals("Final characterOffset must be 0", 0, finalSnapshot.characterOffset)
        assertNull("Final error must be null", finalSnapshot.error)
        assertTrue("Final text must be non-empty", finalSnapshot.text.isNotEmpty())
    }

    @Test
    fun stress_loadTextEventSubscriptionAndDelivery() = runBlocking {
        val received = mutableListOf<String>()
        val job = launch {
            ReaderState.loadTextEvent.collect { received.add(it) }
        }

        // Give collector a brief moment to attach
        delay(50)

        val testTexts = listOf(
            "Primeiro artigo emitido",
            "Segundo artigo emitido",
            "Terceiro artigo emitido",
            "Quarto artigo emitido",
            "Quinto artigo emitido"
        )

        for (t in testTexts) {
            ReaderState.loadText(fakeContext, t)
            delay(10)
        }

        job.cancel()

        assertEquals("All 5 emitted items must be received by the subscriber", testTexts, received)
    }

    @Test
    fun stress_loadTextBlankInputPreservesExistingState() {
        // Set an existing snapshot with progress
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto original em reprodução",
            status = ReadingStatus.PLAYING,
            positionMs = 15_000L,
            durationMs = 60_000L,
            characterOffset = 120
        )
        fakePrefs.edit().putString("text", "Texto original em reprodução").apply()

        // Calling loadText with blank/whitespace strings must be a no-op
        ReaderState.loadText(fakeContext, "")
        ReaderState.loadText(fakeContext, "   ")
        ReaderState.loadText(fakeContext, "\t\n\r")

        val stateAfter = ReaderState.snapshot.value
        assertEquals("Texto original em reprodução", stateAfter.text)
        assertEquals(ReadingStatus.PLAYING, stateAfter.status)
        assertEquals(15_000L, stateAfter.positionMs)
        assertEquals(120, stateAfter.characterOffset)
    }

    @Test
    fun stress_loadTextResetsActivePlayingStatusToIdle() {
        // Simulate active playback in progress
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Capítulo anterior em reprodução",
            status = ReadingStatus.PLAYING,
            positionMs = 45_000L,
            durationMs = 120_000L,
            characterOffset = 350
        )

        val newSharedText = "Notícia recente que interrompe a leitura anterior"
        ReaderState.loadText(fakeContext, newSharedText)

        val updated = ReaderState.snapshot.value
        assertEquals("Incoming share must reset PLAYING status to IDLE", ReadingStatus.IDLE, updated.status)
        assertEquals("Incoming share must reset positionMs to 0", 0L, updated.positionMs)
        assertEquals("Incoming share must reset durationMs to 0", 0L, updated.durationMs)
        assertEquals("Incoming share must reset characterOffset to 0", 0, updated.characterOffset)
        assertEquals(newSharedText, updated.text)
    }

    @Test
    fun stress_loadTextResetsPreparingStatusToIdle() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Capítulo em preparação",
            status = ReadingStatus.PREPARING,
            positionMs = 5_000L,
            characterOffset = 40
        )

        val newSharedText = "Nova partilha em momento de preparação"
        ReaderState.loadText(fakeContext, newSharedText)

        val updated = ReaderState.snapshot.value
        assertEquals("Incoming share must reset PREPARING status to IDLE", ReadingStatus.IDLE, updated.status)
        assertEquals(0L, updated.positionMs)
        assertEquals(0, updated.characterOffset)
        assertEquals(newSharedText, updated.text)
    }

    // =========================================================================
    // SECTION 5: PIPELINE INTEGRATION WITH CHUNKING & CACHE
    // =========================================================================

    @Test
    fun stress_pipeline_extractionToChunkingWithAccentedText() {
        val sharedArticle = "A inteligência artificial na educação em Portugal e nos PALOP: desafios e horizontes. " +
            "O acesso democrático a ferramentas de síntese vocal offline permite uma experiência de leitura acessível. " +
            "Mais de 500 escolas adotaram sistemas inovadores com acompanhamento pedagógico contínuo."

        val extracted = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = sharedArticle
        )
        assertNotNull(extracted)

        val chunks = ReadingChunks.split(extracted!!)
        assertTrue("Must be chunked into readable segments", chunks.isNotEmpty())
        assertTrue("All chunk lengths must be <= 280 chars", chunks.all { it.text.length <= 280 })
        assertEquals(sharedArticle.trim(), chunks.joinToString(" ") { it.text }.trim())
    }

    @Test
    fun stress_pipeline_cacheMissGuaranteedForNewSharedText() {
        val cacheDir = tempFolder.newFolder("audio_cache_test")
        File(cacheDir, "0.wav").writeBytes(ByteArray(256))

        val oldText = "Notícia antiga lida anteriormente"
        ReaderAudioCache.put(
            text = oldText,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk(oldText, 0)),
            durations = listOf(2000L),
            audioDir = cacheDir
        )

        // Verify cache hit for old text
        assertNotNull(ReaderAudioCache.get(oldText, "pt-PT", 1.0f))

        // New text arrives via Share Sheet
        val newText = "Notícia partilhada de última hora"
        val extracted = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = newText
        )
        assertNotNull(extracted)

        // Cache must miss for new text
        assertNull("Cache must be clean miss for incoming share", ReaderAudioCache.get(extracted!!, "pt-PT", 1.0f))
    }

    @Test
    fun stress_safeIntentNullHandling() {
        val result = ShareIntentHandler.extractText(null as Intent?)
        assertNull("Null intent must be safely handled without throwing exception", result)
    }
}
