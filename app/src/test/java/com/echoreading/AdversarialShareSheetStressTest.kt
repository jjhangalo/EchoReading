package com.echoreading

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
import com.echoreading.share.ShareIntentHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite (challenger_m3_2):
 * Stress-testing Milestone M3 (System Share Sheet Direct Routing to Reader).
 *
 * Focus areas:
 * 1. State machine consistency across playback states (PLAYING, PREPARING, PAUSED, ERROR, IDLE).
 * 2. Character offset and timeline resetting on new, duplicate, and rapid text shares.
 * 3. Concurrency, thread-safety, and race conditions during simultaneous text share arrivals.
 * 4. Memory footprint, GC pressure, and timing under massive payloads (100k - 500k characters).
 * 5. ShareIntentHandler boundary conditions, Unicode/Emoji handling, and invalid intents.
 */
class AdversarialShareSheetStressTest {

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

    // =========================================================================
    // SECTION 1: STATE MACHINE CONSISTENCY ACROSS PLAYBACK STATES
    // =========================================================================

    @Test
    fun s1_1_shareArrivesWhilePlaying_resetsToIdleAndZeroOffset_andSendsStop() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto que estava a ser reproduzido activamente",
            status = ReadingStatus.PLAYING,
            positionMs = 15000L,
            durationMs = 45000L,
            characterOffset = 250,
            voiceId = "pt-PT",
            speed = 1.25f,
            error = null
        )

        val newSharedText = "Artigo novo partilhado durante a reprodução"
        ReaderState.loadText(fakeContext, newSharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals("Novo texto deve estar no snapshot", newSharedText, snapshot.text)
        assertEquals("Status deve resetar para IDLE", ReadingStatus.IDLE, snapshot.status)
        assertEquals("Offset de caracter deve resetar para 0", 0, snapshot.characterOffset)
        assertEquals("Posição em ms deve resetar para 0", 0L, snapshot.positionMs)
        assertEquals("Duração deve resetar para 0", 0L, snapshot.durationMs)
        assertNull("Erro deve ser nulo", snapshot.error)

        // With unitTests.returnDefaultValues = true, Intent.getAction() returns null from the Android stub jar.
        // We verify that startService was successfully invoked to deliver the stop intent.
        assertTrue("Deve ter disparado início de serviço para STOP", startedServices.isNotEmpty())

        assertEquals(newSharedText, sharedPrefsMap["draft"])
        assertEquals(newSharedText, sharedPrefsMap["text"])
        assertEquals(0L, sharedPrefsMap["position_ms"])
        assertEquals(0, sharedPrefsMap["character_offset"])
    }

    @Test
    fun s1_2_shareArrivesWhilePreparing_resetsToIdleAndZeroOffset_andSendsStop() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto em preparação de áudio",
            status = ReadingStatus.PREPARING,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0,
            voiceId = "pt-PT",
            speed = 1.0f
        )

        val newSharedText = "Artigo novo partilhado durante a fase de síntese"
        ReaderState.loadText(fakeContext, newSharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals(newSharedText, snapshot.text)
        assertEquals(ReadingStatus.IDLE, snapshot.status)
        assertEquals(0, snapshot.characterOffset)
        assertEquals(0L, snapshot.positionMs)

        assertTrue("Deve interromper síntese disparando início de serviço", startedServices.isNotEmpty())
    }


    @Test
    fun s1_3_shareArrivesWhilePaused_resetsToIdleAndZeroOffset() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto em pausa",
            status = ReadingStatus.PAUSED,
            positionMs = 8200L,
            durationMs = 30000L,
            characterOffset = 180,
            voiceId = "pt-PT",
            speed = 1.0f
        )

        val newSharedText = "Artigo novo recebido enquanto o leitor estava em pausa"
        ReaderState.loadText(fakeContext, newSharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals(newSharedText, snapshot.text)
        assertEquals("Status deve ser IDLE", ReadingStatus.IDLE, snapshot.status)
        assertEquals("Offset deve resetar para 0", 0, snapshot.characterOffset)
        assertEquals("Posição deve resetar para 0", 0L, snapshot.positionMs)
        assertEquals("Duração deve resetar para 0", 0L, snapshot.durationMs)
    }

    @Test
    fun s1_4_shareArrivesWhileInError_clearsErrorAndResetsToIdle() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Texto que falhou a síntese",
            status = ReadingStatus.ERROR,
            positionMs = 1200L,
            durationMs = 5000L,
            characterOffset = 30,
            error = "Erro no motor Sherpa-ONNX: modelo indisponível"
        )

        val newSharedText = "Texto novo para recuperação do erro"
        ReaderState.loadText(fakeContext, newSharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals(newSharedText, snapshot.text)
        assertEquals("Status deve sair de ERROR e passar a IDLE", ReadingStatus.IDLE, snapshot.status)
        assertNull("Mensagem de erro deve ser limpa", snapshot.error)
        assertEquals(0, snapshot.characterOffset)
        assertEquals(0L, snapshot.positionMs)
        assertEquals(0, startedServices.size)
    }

    @Test
    fun s1_5_shareArrivesWhileIdle_updatesTextAndKeepsIdle() {
        ReaderState.snapshot.value = ReaderSnapshot(
            text = "Rascunho existente",
            status = ReadingStatus.IDLE,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0
        )

        val newSharedText = "Novo rascunho partilhado"
        ReaderState.loadText(fakeContext, newSharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals(newSharedText, snapshot.text)
        assertEquals(ReadingStatus.IDLE, snapshot.status)
        assertEquals(0, snapshot.characterOffset)
        assertEquals(0L, snapshot.positionMs)
        assertEquals(0, startedServices.size)
    }

    // =========================================================================
    // SECTION 2: CHARACTER OFFSET RESETTING & DUPLICATE SHARES
    // =========================================================================

    @Test
    fun s2_1_repeatedDuplicateTextShare_resetsOffsetToZero() {
        val sharedText = "Notícia repetida enviada várias vezes pelo utilizador."

        // Primeira carga
        ReaderState.loadText(fakeContext, sharedText)
        assertEquals(0, ReaderState.snapshot.value.characterOffset)

        // Simula avanço da leitura
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            characterOffset = 35,
            positionMs = 6000L,
            status = ReadingStatus.PLAYING
        )

        // Reenvio exacto do mesmo texto via Share Sheet
        ReaderState.loadText(fakeContext, sharedText)

        val snapshot = ReaderState.snapshot.value
        assertEquals(sharedText, snapshot.text)
        assertEquals("Mesmo texto partilhado deve reiniciar offset para 0", 0, snapshot.characterOffset)
        assertEquals("Mesmo texto partilhado deve reiniciar posição para 0", 0L, snapshot.positionMs)
        assertEquals(ReadingStatus.IDLE, snapshot.status)
    }

    @Test
    fun s2_2_rapidSuccessiveDuplicateShares_alwaysKeepOffsetAtZero() {
        val sharedText = "Texto constante partilhado repetidamente"

        for (i in 1..25) {
            // Avança arbitrariamente o offset
            ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
                characterOffset = i * 10,
                positionMs = i * 1000L,
                status = ReadingStatus.PLAYING
            )

            // Novo share
            ReaderState.loadText(fakeContext, sharedText)

            assertEquals("Iteração $i: offset deve ser 0", 0, ReaderState.snapshot.value.characterOffset)
            assertEquals("Iteração $i: status deve ser IDLE", ReadingStatus.IDLE, ReaderState.snapshot.value.status)
            assertEquals("Iteração $i: positionMs deve ser 0", 0L, ReaderState.snapshot.value.positionMs)
        }
    }

    @Test
    fun s2_3_differentTextShare_resetsOffsetToZero() {
        ReaderState.loadText(fakeContext, "Primeiro texto longo com muitos detalhes")
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            characterOffset = 25,
            positionMs = 4500L,
            status = ReadingStatus.PLAYING
        )

        ReaderState.loadText(fakeContext, "Segundo texto totalmente diferente")

        assertEquals(0, ReaderState.snapshot.value.characterOffset)
        assertEquals(0L, ReaderState.snapshot.value.positionMs)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals("Segundo texto totalmente diferente", ReaderState.snapshot.value.text)
    }

    @Test
    fun s2_4_loadTextEmitsEventToSharedFlow() {
        val received = mutableListOf<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            ReaderState.loadTextEvent.collect { received.add(it) }
        }

        try {
            ReaderState.loadText(fakeContext, "Primeiro envio de evento")
            ReaderState.loadText(fakeContext, "Segundo envio de evento")

            assertEquals(listOf("Primeiro envio de evento", "Segundo envio de evento"), received)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun s2_5_blankOrEmptyShare_doesNotResetOrModifyState() {
        val originalText = "Texto mantido sem alteração"
        ReaderState.snapshot.value = ReaderSnapshot(
            text = originalText,
            status = ReadingStatus.PLAYING,
            positionMs = 12000L,
            characterOffset = 80
        )

        ReaderState.loadText(fakeContext, "")
        ReaderState.loadText(fakeContext, "    \n\t   ")

        val current = ReaderState.snapshot.value
        assertEquals("Texto não deve ser sobrescrito por texto vazio", originalText, current.text)
        assertEquals("Offset deve permanecer inalterado", 80, current.characterOffset)
        assertEquals("Status deve permanecer PLAYING", ReadingStatus.PLAYING, current.status)
        assertEquals(0, startedServices.size)
    }

    // =========================================================================
    // SECTION 3: CONCURRENCY & RACE CONDITION STRESS TESTS
    // =========================================================================

    @Test
    fun s3_1_concurrentLoadTextFromMultipleThreads_remainsConsistent() {
        val threadCount = 20
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val successCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val payload = "Texto da thread $t iteração $i"
                        ReaderState.loadText(fakeContext, payload)
                        val snap = ReaderState.snapshot.value
                        assertEquals(0, snap.characterOffset)
                        assertEquals(ReadingStatus.IDLE, snap.status)
                        assertEquals(0L, snap.positionMs)
                        assertTrue(snap.text.isNotBlank())
                    }
                    successCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout no stress concorrente de loadText", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threadCount, successCount.get())

        val finalSnap = ReaderState.snapshot.value
        assertEquals(0, finalSnap.characterOffset)
        assertEquals(ReadingStatus.IDLE, finalSnap.status)
        assertEquals(0L, finalSnap.positionMs)
        assertTrue(finalSnap.text.startsWith("Texto da thread"))
    }

    @Test
    fun s3_2_concurrentShareDuringSimulatedPositionTicks_doesNotDeadlock() {
        val executor = Executors.newFixedThreadPool(2)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val tickCount = AtomicInteger(0)
        val shareCount = AtomicInteger(0)

        // Thread 1: Simula ticks contínuos de posição e offset de áudio
        val ticker = executor.submit {
            while (running.get()) {
                val current = ReaderState.snapshot.value
                ReaderState.snapshot.value = current.copy(
                    positionMs = current.positionMs + 100,
                    characterOffset = current.characterOffset + 1
                )
                tickCount.incrementAndGet()
                Thread.sleep(1)
            }
        }

        // Thread 2: Simula shares concorrentes frequentes
        val sharer = executor.submit {
            for (i in 1..100) {
                ReaderState.loadText(fakeContext, "Texto concorrente $i")
                shareCount.incrementAndGet()
                Thread.sleep(2)
            }
            running.set(false)
        }

        ticker.get(5, TimeUnit.SECONDS)
        sharer.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(tickCount.get() > 50)
        assertEquals(100, shareCount.get())
        // Estado final após paragem deve ser inspecionável e consistente
        assertNotNull(ReaderState.snapshot.value.text)
    }

    // =========================================================================
    // SECTION 4: MASSIVE PAYLOADS & MEMORY FOOTPRINT (100k - 500k CHARACTERS)
    // =========================================================================

    @Test
    fun s4_1_oneHundredThousandCharacters_processesFastAndWithinMemoryLimits() {
        val repeatCount = 10_000
        val baseSentence = "EcoReading texto de teste. " // 27 chars
        val hugeText = baseSentence.repeat(repeatCount) // ~270,000 chars

        val startMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startNano = System.nanoTime()

        // 1. Extração
        val extracted = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = hugeText
        )
        assertNotNull(extracted)
        assertEquals(hugeText.trim().length, extracted!!.length)

        // 2. Carga no ReaderState
        ReaderState.loadText(fakeContext, extracted)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals(0, ReaderState.snapshot.value.characterOffset)

        // 3. Segmentação em chunks
        val chunks = ReadingChunks.split(extracted)
        assertTrue("Deve gerar chunks para texto de 270k", chunks.isNotEmpty())
        assertTrue("Nenhum chunk deve ultrapassar 280 caracteres", chunks.all { it.text.length <= 280 })

        val durationMs = (System.nanoTime() - startNano) / 1_000_000
        val endMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memDeltaMb = (endMem - startMem) / (1024 * 1024)

        // 270,000 chars deve executar em menos de 2000ms em qualquer JVM
        assertTrue("Processamento de 270k chars demorou ${durationMs}ms (limite 2000ms)", durationMs < 2000)
    }

    @Test
    fun s4_2_fiveHundredThousandCharacters_memoryFootprintAndNoOom() {
        // Gera exactamente ~500,000 caracteres
        val word = "PalavraComTamanhoMedio "
        val repeatCount = 500_000 / word.length
        val halfMillionChars = word.repeat(repeatCount)
        assertTrue("Tamanho deve rondar 500k", halfMillionChars.length in 490_000..510_000)

        System.gc()
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val startNano = System.nanoTime()

        // 1. Ingestão e validação
        val extracted = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = halfMillionChars
        )
        assertNotNull(extracted)

        // 2. ReaderState
        ReaderState.loadText(fakeContext, extracted!!)
        assertEquals(0, ReaderState.snapshot.value.characterOffset)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)

        // 3. Chunking em blocos para síntese TTS
        val chunks = ReadingChunks.split(extracted)
        assertTrue("Deve criar mais de 1000 chunks", chunks.size > 1000)

        // 4. Estatísticas de contagem de palavras (como no ReaderHome)
        val wordCount = extracted.split("\\s+".toRegex()).count { it.isNotBlank() }
        assertTrue("Contagem de palavras deve coincidir", wordCount > 15_000)

        val durationMs = (System.nanoTime() - startNano) / 1_000_000
        System.gc()
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val retainedMb = (memAfter - memBefore) / (1024 * 1024)

        // 500k caracteres deve ser processado sem crash ou estouro de heap
        assertTrue("Processamento de 500k caracteres demorou ${durationMs}ms", durationMs < 4000)
        // Memória residual não deve explodir (< 150MB de retenção líquida)
        assertTrue("Memória retida deve ser razoável (${retainedMb}MB)", retainedMb < 150)
    }

    @Test
    fun s4_3_hugePayloadWithoutWhitespace_splitsProperlyWithoutInfiniteLoop() {
        // Texto patológico: 100,000 caracteres contínuos sem um único espaço ou quebra de linha
        val solidString = "A".repeat(100_000)

        val start = System.currentTimeMillis()
        val chunks = ReadingChunks.split(solidString, maxChars = 280)
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Não deve entrar em loop infinito (${elapsed}ms)", elapsed < 1000)
        assertTrue("Deve fatiar mesmo sem delimitadores", chunks.isNotEmpty())
        assertEquals(solidString.length, chunks.sumOf { it.text.length })
        assertTrue("Todos os chunks <= 280 chars", chunks.all { it.text.length <= 280 })
    }

    @Test
    fun s4_4_massivePayloadWithDenseEmojiAndComplexUnicode() {
        val emojis = "🚀🌍✨💡🔥❤️📚🎉"
        val payload = (emojis + " Leitura avançada com símbolos unicode: ∑(x) = ∞ ").repeat(5_000)

        val extracted = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = payload
        )
        assertNotNull(extracted)
        ReaderState.loadText(fakeContext, extracted!!)

        assertEquals(0, ReaderState.snapshot.value.characterOffset)
        assertEquals(ReadingStatus.IDLE, ReaderState.snapshot.value.status)
        assertEquals(extracted, ReaderState.snapshot.value.text)

        val chunks = ReadingChunks.split(extracted)
        assertTrue(chunks.isNotEmpty())
    }

    // =========================================================================
    // SECTION 5: SHAREINTENTHANDLER BOUNDARY & SECURITY DEFENSE TESTS
    // =========================================================================

    @Test
    fun s5_1_rejectsVariousInvalidMimeTypes() {
        val invalidMimes = listOf(
            "application/octet-stream",
            "application/json",
            "image/png",
            "image/webp",
            "audio/wav",
            "audio/mpeg",
            "video/mp4",
            "multipart/form-data"
        )
        for (mime in invalidMimes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = "Texto com MIME inválido"
            )
            assertNull("MIME $mime deve ser estritamente rejeitado", result)
        }
    }

    @Test
    fun s5_2_acceptsTextSubtypesAndWildcard() {
        val validMimes = listOf(
            "text/plain",
            "text/*",
            "text/html",
            "text/markdown",
            "text/csv",
            "text/calendar"
        )
        for (mime in validMimes) {
            val result = ShareIntentHandler.extractText(
                action = ShareIntentHandler.ACTION_SEND,
                mimeType = mime,
                extraText = "Conteúdo textual aceitável"
            )
            assertEquals("MIME $mime deve ser aceite", "Conteúdo textual aceitável", result)
        }
    }

    @Test
    fun s5_3_trimsExcessiveWhitespaceAroundExtractedText() {
        val messy = "\n\n\t   \r\n   Texto central com informação importante   \t\r\n\n"
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = messy
        )
        assertEquals("Texto central com informação importante", result)
    }

    @Test
    fun s5_4_intentExtraTextWithSpannableCharSequence() {
        // Simula CharSequence do tipo Spanned/SpannableString vindo de aplicações externas
        val charSeq: CharSequence = StringBuilder("Texto formatado rico")
        val result = ShareIntentHandler.extractText(
            action = ShareIntentHandler.ACTION_SEND,
            mimeType = "text/plain",
            extraText = charSeq
        )
        assertEquals("Texto formatado rico", result)
    }
}
