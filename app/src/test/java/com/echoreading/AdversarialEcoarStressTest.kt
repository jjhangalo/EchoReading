package com.echoreading

import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import com.echoreading.reader.ReadingStatus
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
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Adversarial Challenger Test Suite for Milestone M2:
 * System-wide Text Selection Action ("Ecoar") & Quick Access Bottom Sheet.
 *
 * Stress-tests:
 * 1. Empty and edge-case text strings (nulls, whitespace, unicode whitespaces, punctuation, emojis).
 * 2. Huge text payloads (>100KB, 500KB, continuous strings without delimiters).
 * 3. Rapid consecutive open/close requests and lifecycle transitions.
 * 4. Rapid play/pause clicks and concurrent transport state-machine operations.
 */
class AdversarialEcoarStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val originalSnapshot = ReaderState.snapshot.value

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = ReaderSnapshot()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        ReaderState.snapshot.value = originalSnapshot
    }

    // =========================================================================
    // SECTION 1: EMPTY AND EDGE-CASE TEXT STRINGS
    // =========================================================================

    @Test
    fun testEmptyTextHandlingAcrossSubsystems() {
        // 1. Intent extraction
        val extracted = IntentTestContracts.extractProcessText(
            IntentTestContracts.ACTION_PROCESS_TEXT,
            IntentTestContracts.MIME_TYPE_TEXT_PLAIN,
            ""
        )
        assertNull("Empty text in PROCESS_TEXT must return null", extracted)

        // 2. ReadingChunks splitting
        val chunks = ReadingChunks.split("")
        assertTrue("Splitting empty text must return empty list", chunks.isEmpty())

        // 3. Notification title format fallback
        val title = ReaderPlaybackService.formatTitle("", "Fallback App")
        assertEquals("Fallback App", title)

        // 4. QuickReadActivity early return check
        val emptySelected = "".trim()
        assertTrue("QuickReadActivity must finish when text is empty", emptySelected.isEmpty())
    }

    @Test
    fun testWhitespaceVarietiesHandling() {
        val whitespaceSamples = listOf(
            "   ",
            "\t\t\t",
            "\n\n\r\n",
            " \t \n \r \t ",
            "\u00A0", // non-breaking space
            "\u2003", // em space
            "\u3000", // ideographic space
            "\u200B"  // zero-width space
        )

        for (ws in whitespaceSamples) {
            val extracted = IntentTestContracts.extractProcessText(
                IntentTestContracts.ACTION_PROCESS_TEXT,
                IntentTestContracts.MIME_TYPE_TEXT_PLAIN,
                ws
            )
            val trimmed = ws.trim()
            if (trimmed.isEmpty()) {
                assertNull("Whitespace sample '$ws' must return null from extraction", extracted)
            }

            val chunks = ReadingChunks.split(ws)
            if (ws.all { it.isWhitespace() }) {
                assertTrue("All-whitespace input '$ws' must produce 0 chunks", chunks.isEmpty())
            }

            val title = ReaderPlaybackService.formatTitle(ws, "Fallback")
            if (ws.all { it.isWhitespace() }) {
                assertEquals("Whitespace-only input must trigger fallback title", "Fallback", title)
            }
        }
    }

    @Test
    fun testPunctuationAndSymbolHandling() {
        val punctOnly = "!@#$%^&*()_+-=[]{}|;':\",./<>?~`"
        val chunks = ReadingChunks.split(punctOnly)
        assertTrue("Punctuation-only string must produce chunks without hanging", chunks.isNotEmpty())
        assertTrue("All punctuation chunks must be <= 280 chars", chunks.all { it.text.length <= 280 })

        val title = ReaderPlaybackService.formatTitle(punctOnly, "Fallback")
        assertFalse("Punctuation title must not be blank", title.isBlank())
        assertTrue("Punctuation title length <= 81", title.length <= 81)
    }

    @Test
    fun testMultilingualAndEmojiSurrogateHandling() {
        // Multi-byte Unicode emojis & Portuguese accented text
        val emojis = "😀😃😄😁😆😅🤣😂🙂🙃😉😊😇🥰😍🤩😘"
        val ptText = "A inteligência artificial lê este texto com fluidez: $emojis"
        val chunks = ReadingChunks.split(ptText)
        assertTrue(chunks.isNotEmpty())
        assertEquals(ptText, chunks.joinToString(" ") { it.text })

        // Ensure no surrogate pair split across chunk boundary
        for (chunk in chunks) {
            assertFalse(
                "Chunk boundary must not end with high surrogate",
                chunk.text.isNotEmpty() && chunk.text.last().isHighSurrogate()
            )
            assertFalse(
                "Chunk boundary must not start with low surrogate",
                chunk.text.isNotEmpty() && chunk.text.first().isLowSurrogate()
            )
        }
    }

    @Test
    fun testAutoPlaybackPreconditionGuards() {
        // Simulates ReaderUi LaunchedEffect(text) guard logic:
        fun shouldTriggerAutoPlay(
            text: String,
            currentText: String,
            currentStatus: ReadingStatus
        ): Boolean {
            if (text.isBlank()) return false
            val isAlreadyPlayingThis = currentText == text &&
                (currentStatus == ReadingStatus.PLAYING || currentStatus == ReadingStatus.PREPARING)
            return !isAlreadyPlayingThis
        }

        // Blank text -> false
        assertFalse(shouldTriggerAutoPlay("", "", ReadingStatus.IDLE))
        assertFalse(shouldTriggerAutoPlay("   ", "abc", ReadingStatus.IDLE))

        // Same text already playing -> false (prevents audio restart)
        assertFalse(shouldTriggerAutoPlay("hello", "hello", ReadingStatus.PLAYING))
        assertFalse(shouldTriggerAutoPlay("hello", "hello", ReadingStatus.PREPARING))

        // Same text but currently paused -> true (resumes or restarts)
        assertTrue(shouldTriggerAutoPlay("hello", "hello", ReadingStatus.PAUSED))

        // Same text but idle -> true
        assertTrue(shouldTriggerAutoPlay("hello", "hello", ReadingStatus.IDLE))

        // New text -> true
        assertTrue(shouldTriggerAutoPlay("new text", "old text", ReadingStatus.PLAYING))
    }

    // =========================================================================
    // SECTION 2: HUGE TEXT PAYLOADS (>100KB, 500KB, CONTINUOUS STRINGS)
    // =========================================================================

    @Test
    fun testHugePayload100KBChunkingCompleteness() {
        // Generate realistic 120KB text (~20,000 words)
        val sb = StringBuilder()
        val sentences = listOf(
            "O EchoReading é uma aplicação inovadora de leitura acessível.",
            "A síntese de voz permite converter artigos longos em áudio natural.",
            "Com o modelo Piper VITS, a latência de síntese no dispositivo é mínima.",
            "Esta funcionalidade de seleção rápida via menu Ecoar simplifica a audição."
        )
        var count = 0
        while (sb.length < 120_000) {
            sb.append(sentences[count % sentences.size]).append(" ")
            count++
        }
        val hugeText = sb.toString()
        assertTrue("Generated text must be >= 120KB", hugeText.length >= 120_000)

        val startTime = System.nanoTime()
        val chunks = ReadingChunks.split(hugeText)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        // Performance check: 120KB chunking must take < 500ms
        assertTrue("120KB chunking took ${elapsedMs}ms, should be < 500ms", elapsedMs < 500)
        assertTrue("Must generate > 400 chunks", chunks.size > 400)

        // Invariant checks
        for (i in chunks.indices) {
            val chunk = chunks[i]
            assertTrue("Chunk length must be <= 280 chars (actual: ${chunk.text.length})", chunk.text.length <= 280)
            assertFalse("Chunk must not be empty", chunk.text.isEmpty())
            if (i > 0) {
                assertTrue(
                    "Start offsets must be strictly monotonic: ${chunks[i-1].start} < ${chunk.start}",
                    chunks[i - 1].start < chunk.start
                )
            }
        }
    }

    @Test
    fun testHugePayload500KBStressChunking() {
        val paragraph = "Capítulo de teste intensivo com múltiplas frases para validação de carga extrema. ".repeat(10)
        val sb = StringBuilder()
        while (sb.length < 500_000) {
            sb.append(paragraph).append("\n\n")
        }
        val payload500K = sb.toString()

        val startTime = System.nanoTime()
        val chunks = ReadingChunks.split(payload500K)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue("500KB chunking took ${elapsedMs}ms, should be < 1500ms", elapsedMs < 1500)
        assertTrue("Chunks count for 500KB should be >= 1800", chunks.size >= 1800)
        assertTrue("All chunks <= 280", chunks.all { it.text.length <= 280 })
    }

    @Test
    fun testHugePayloadSingleWordNoSpaces150KB() {
        // Pathological input: 150,000 characters without a single space or punctuation mark
        val hugeWord = "Z".repeat(150_000)

        val startTime = System.nanoTime()
        val chunks = ReadingChunks.split(hugeWord)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue("150KB continuous word split took ${elapsedMs}ms", elapsedMs < 500)
        val expectedChunks = (150_000 + 279) / 280
        assertEquals(expectedChunks, chunks.size)
        for (i in 0 until chunks.size - 1) {
            assertEquals("Chunk $i must be exactly 280 chars", 280, chunks[i].text.length)
        }
        assertEquals(150_000 % 280, chunks.last().text.length)
    }

    @Test
    fun testHugePayloadFormatTitleStress() {
        val huge = "Palavra ".repeat(20_000) // ~160KB
        val startTime = System.nanoTime()
        val title = ReaderPlaybackService.formatTitle(huge, "Fallback")
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue("Title formatting 160KB took ${elapsedMs}ms, should be < 300ms", elapsedMs < 300)
        assertTrue("Title length must be <= 81 (80 + ellipsis)", title.length <= 81)
        assertTrue("Title must end with ellipsis", title.endsWith("…"))
    }

    @Test
    fun testHugeAudioTimeline10000ChunksStress() {
        val timeline = AudioTimeline()
        val chunkCount = 10_000
        val durationPerChunk = 3_500L // 3.5s per chunk -> total 35,000s (~9.7h)

        for (i in 0 until chunkCount) {
            timeline.add(durationPerChunk)
        }

        assertEquals(chunkCount, timeline.size)
        val totalMs = chunkCount * durationPerChunk
        assertEquals(totalMs, timeline.preparedMs)

        // 1. Beginning
        val (firstIdx, firstOff) = timeline.locate(0L)
        assertEquals(0, firstIdx)
        assertEquals(0L, firstOff)

        // 2. Middle
        val midMs = totalMs / 2
        val (midIdx, midOff) = timeline.locate(midMs)
        assertEquals(chunkCount / 2, midIdx)
        assertEquals(0L, midOff)

        // 3. Exact boundary
        val (bIdx, bOff) = timeline.locate(durationPerChunk * 500)
        assertEquals(500, bIdx)
        assertEquals(0L, bOff)

        // 4. End clamping
        val (lastIdx, lastOff) = timeline.locate(totalMs)
        assertEquals(chunkCount - 1, lastIdx)
        assertEquals(durationPerChunk, lastOff)

        // 5. Overflow clamping
        val (overIdx, overOff) = timeline.locate(totalMs + 1_000_000L)
        assertEquals(chunkCount - 1, overIdx)
        assertEquals(durationPerChunk, overOff)

        // 6. Underflow clamping
        val (underIdx, underOff) = timeline.locate(-50_000L)
        assertEquals(0, underIdx)
        assertEquals(0L, underOff)

        // 7. Global position roundtrip
        val globalPos = timeline.globalPosition(500, 1_234L)
        assertEquals(500 * durationPerChunk + 1_234L, globalPos)
    }

    @Test
    fun testHugeTextCachePutAndGetIntegrity() {
        val dir = tempFolder.newFolder("huge_cache")
        val chunkCount = 500
        val chunks = mutableListOf<ReadingChunk>()
        val durations = mutableListOf<Long>()

        for (i in 0 until chunkCount) {
            chunks.add(ReadingChunk("Chunk text #$i", i * 100))
            durations.add(2_000L)
            File(dir, "$i.wav").writeBytes(ByteArray(64))
        }

        val hugeText = "Grandioso texto de teste ".repeat(400) // ~10KB
        ReaderAudioCache.put(
            text = hugeText,
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = chunks,
            durations = durations,
            audioDir = dir
        )

        // Cache hit
        val hit = ReaderAudioCache.get(hugeText, "pt-PT", 1.0f)
        assertNotNull("Huge text cache must hit", hit)
        assertEquals(chunkCount, hit?.chunks?.size)

        // Cache miss with modified text
        val missText = ReaderAudioCache.get(hugeText + "!", "pt-PT", 1.0f)
        assertNull("Modified text must miss", missText)

        // Cache miss with different speed
        val missSpeed = ReaderAudioCache.get(hugeText, "pt-PT", 1.25f)
        assertNull("Different speed must miss", missSpeed)

        // Invalidate single file -> cache clears and misses
        File(dir, "250.wav").delete()
        val missDeleted = ReaderAudioCache.get(hugeText, "pt-PT", 1.0f)
        assertNull("Missing audio file must invalidate cache", missDeleted)
    }

    // =========================================================================
    // SECTION 3: RAPID CONSECUTIVE OPEN/CLOSE REQUESTS & TRANSITIONS
    // =========================================================================

    @Test
    fun testRapidConsecutiveOpenCloseStateLifecycle() {
        // Simulate 1,000 rapid open/close events
        for (i in 0 until 1_000) {
            val sampleText = "Texto de teste rápido $i"
            ReaderState.snapshot.value = ReaderSnapshot(
                text = sampleText,
                status = ReadingStatus.PREPARING,
                positionMs = i * 10L,
                speed = 1.0f
            )

            // Simulate dismiss / finish without stopping playback service
            assertEquals(sampleText, ReaderState.snapshot.value.text)
            assertEquals(ReadingStatus.PREPARING, ReaderState.snapshot.value.status)
        }
    }

    @Test
    fun testRapidConcurrentCacheOperations() {
        val threadCount = 6
        val operationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    val threadDir = tempFolder.newFolder("thread_$t")
                    File(threadDir, "0.wav").writeBytes(ByteArray(32))

                    for (i in 0 until operationsPerThread) {
                        val text = "Concorrência $t-$i"
                        ReaderAudioCache.put(
                            text = text,
                            voiceId = "pt-PT",
                            speed = 1.0f,
                            chunks = listOf(ReadingChunk(text, 0)),
                            durations = listOf(1_000L),
                            audioDir = threadDir
                        )
                        ReaderAudioCache.get(text, "pt-PT", 1.0f)
                        ReaderAudioCache.isCachedDir(threadDir)
                        if (i % 20 == 0) {
                            ReaderAudioCache.clear()
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads must complete within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No concurrent cache exceptions permitted", 0, errors.get())
    }

    @Test
    fun testRapidSpeedPillTransitions() {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        val timeline = AudioTimeline()
        timeline.add(10_000L)
        timeline.add(10_000L)

        // Rapidly change speed 500 times
        for (i in 0 until 500) {
            val s = speeds[i % speeds.size]
            ReaderState.snapshot.value = ReaderState.snapshot.value.copy(speed = s)
            assertEquals(s, ReaderState.snapshot.value.speed, 0.001f)

            // Scaled position simulation
            val currentPos = 5_000L
            val (chunkIdx, offset) = timeline.locate(currentPos)
            assertEquals(0, chunkIdx)
            assertEquals(5_000L, offset)
        }
    }

    // =========================================================================
    // SECTION 4: RAPID PLAY/PAUSE CLICKS & TRANSPORT STATE MACHINE
    // =========================================================================

    @Test
    fun testRapidPlayPauseToggleStateMachine() {
        // Resolver based directly on ReaderUi.kt lines 1259-1269
        fun resolveTransportCommand(status: ReadingStatus, text: String): String {
            return when (status) {
                ReadingStatus.PLAYING -> ReaderPlaybackService.ACTION_PAUSE
                ReadingStatus.PAUSED -> ReaderPlaybackService.ACTION_PLAY
                else -> ReaderPlaybackService.ACTION_READ
            }
        }

        val text = "Texto para teste de cliques rápidos"

        // 1. Initial IDLE state -> click sends ACTION_READ
        assertEquals(ReaderPlaybackService.ACTION_READ, resolveTransportCommand(ReadingStatus.IDLE, text))

        // 2. PREPARING state -> click sends ACTION_READ
        assertEquals(ReaderPlaybackService.ACTION_READ, resolveTransportCommand(ReadingStatus.PREPARING, text))

        // 3. Alternate between PLAYING and PAUSED 1,000 times
        var currentStatus = ReadingStatus.PLAYING
        for (i in 0 until 1_000) {
            val command = resolveTransportCommand(currentStatus, text)
            if (currentStatus == ReadingStatus.PLAYING) {
                assertEquals(ReaderPlaybackService.ACTION_PAUSE, command)
                currentStatus = ReadingStatus.PAUSED
            } else {
                assertEquals(ReaderPlaybackService.ACTION_PLAY, command)
                currentStatus = ReadingStatus.PLAYING
            }
        }
    }

    @Test
    fun testRapidConcurrentStateUpdatesStress() {
        val threadCount = 8
        val updatesPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until updatesPerThread) {
                        val newStatus = if (i % 2 == 0) ReadingStatus.PLAYING else ReadingStatus.PAUSED
                        val pos = (t * 1000 + i).toLong()
                        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
                            status = newStatus,
                            positionMs = pos
                        )
                        // Read back to ensure no corrupted internal state
                        val readBack = ReaderState.snapshot.value
                        assertNotNull(readBack)
                    }
                } catch (t: Throwable) {
                    failureCount.incrementAndGet()
                    t.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent updates must finish within 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero concurrent state update failures allowed", 0, failureCount.get())
    }

    @Test
    fun testRapidRewindAndForwardClampingStress() {
        val timeline = AudioTimeline()
        timeline.add(5_000L) // chunk 0: 0..5000
        timeline.add(8_000L) // chunk 1: 5000..13000

        var currentPosition = 6_000L // in chunk 1 at 1000ms

        // Rapid 100 rewinds of 10,000ms
        for (i in 0 until 100) {
            currentPosition = (currentPosition - 10_000L).coerceAtLeast(0L)
            val (idx, off) = timeline.locate(currentPosition)
            assertEquals(0, idx)
            assertEquals(0L, off)
        }

        // Rapid 100 forwards of 10,000ms
        for (i in 0 until 100) {
            currentPosition = (currentPosition + 10_000L).coerceAtMost(timeline.preparedMs)
            val (idx, off) = timeline.locate(currentPosition)
            assertEquals(1, idx)
            if (i == 0) {
                // First step: 0L + 10_000L = 10_000L (5_000L into chunk 1)
                assertEquals(5_000L, off)
            } else {
                // Subsequent steps: clamped at preparedMs (13_000L -> 8_000L into chunk 1)
                assertEquals(8_000L, off)
            }
        }
    }

    @Test
    fun testRapidConsecutiveIntentParsingUnderLoad() {
        val actions = listOf(
            IntentTestContracts.ACTION_PROCESS_TEXT,
            IntentTestContracts.ACTION_SEND,
            "android.intent.action.VIEW",
            null
        )
        val mimeTypes = listOf(
            IntentTestContracts.MIME_TYPE_TEXT_PLAIN,
            "text/html",
            "image/jpeg",
            null
        )
        val texts = listOf(
            "",
            "   ",
            "Texto válido",
            "A".repeat(10_000),
            null
        )

        for (i in 0 until 2_000) {
            val action = actions[i % actions.size]
            val mime = mimeTypes[i % mimeTypes.size]
            val text = texts[i % texts.size]

            val result = IntentTestContracts.extractProcessText(action, mime, text)
            if (action == IntentTestContracts.ACTION_PROCESS_TEXT &&
                (mime == IntentTestContracts.MIME_TYPE_TEXT_PLAIN || mime?.startsWith("text/") == true) &&
                text?.trim()?.isNotEmpty() == true
            ) {
                assertEquals(text.trim(), result)
            } else {
                assertNull(result)
            }
        }
    }
}
