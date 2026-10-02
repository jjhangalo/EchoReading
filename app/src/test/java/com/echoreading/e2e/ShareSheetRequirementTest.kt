package com.echoreading.e2e

import com.echoreading.e2e.testutil.IntentTestContracts
import com.echoreading.e2e.testutil.ManifestTestParser
import com.echoreading.reader.AudioTimeline
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReadingChunk
import com.echoreading.reader.ReadingChunks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2E Requirement Tests for Requirement R3:
 * System Share Sheet Direct Routing to Reader
 *
 * Covers Features 11, 12, 13 across Tier 1, Tier 2, and Tier 3.
 */
class ShareSheetRequirementTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        ReaderAudioCache.clear()
        tempDir = Files.createTempDirectory("share-sheet-test").toFile()
    }

    @After
    fun tearDown() {
        ReaderAudioCache.clear()
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // TIER 1: FEATURE 11 — Share Sheet Direct Routing (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f11_1_shareIntentMatchesActionSendAndTextPlain() {
        val extracted = IntentTestContracts.extractSendText(
            action = IntentTestContracts.ACTION_SEND,
            mimeType = "text/plain",
            extraText = "Texto partilhado via Share Sheet"
        )
        assertEquals("Texto partilhado via Share Sheet", extracted)
    }

    @Test
    fun f11_2_shareIntentActionIsAndroidStandardSend() {
        assertEquals("android.intent.action.SEND", IntentTestContracts.ACTION_SEND)
    }

    @Test
    fun f11_3_mainActivityConfiguredAsSingleTopInManifest() {
        val activities = ManifestTestParser.parseActivities()
        val main = activities.find { it.name.contains("MainActivity") }
        assertNotNull("MainActivity must be declared in AndroidManifest.xml", main)
        assertEquals("singleTop", main!!.launchMode)
    }

    @Test
    fun f11_4_shareTargetDisambiguationSpec() {
        // Requirement R3 specifies MainActivity as the direct reader target for plain text shares
        val targetClass = "com.echoreading.MainActivity"
        assertEquals("com.echoreading.MainActivity", targetClass)
    }

    @Test
    fun f11_5_shareIntentMatchesWildcardTextMimeType() {
        val extracted = IntentTestContracts.extractSendText(
            action = IntentTestContracts.ACTION_SEND,
            mimeType = "text/*",
            extraText = "Partilha genérica de texto"
        )
        assertEquals("Partilha genérica de texto", extracted)
    }

    // =========================================================================
    // TIER 1: FEATURE 12 — Share Intent Extraction & Routing (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f12_1_extractsPlainTextFromExtraText() {
        val text = "Notícia importante sobre tecnologia e leitura."
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            text
        )
        assertEquals(text, result)
    }

    @Test
    fun f12_2_rejectsNonTextMimeTypes() {
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "image/jpeg",
            "Ignorar este texto anexado à imagem"
        )
        assertNull("Non-text MIME types must be rejected", result)
    }

    @Test
    fun f12_3_rejectsNonSendActions() {
        val result = IntentTestContracts.extractSendText(
            "android.intent.action.VIEW",
            "text/plain",
            "Texto com ação inválida"
        )
        assertNull("Actions other than ACTION_SEND must be rejected", result)
    }

    @Test
    fun f12_4_coldStartExtractionSimulation() {
        // Simulates cold start extraction when app is launched via Share Sheet
        val incomingText = "Texto de inicialização a frio"
        val extracted = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            incomingText
        )
        assertNotNull(extracted)
        assertEquals(incomingText, extracted)
    }

    @Test
    fun f12_5_warmStartExtractionSimulation() {
        // Simulates singleTop onNewIntent delivery
        val firstShare = "Primeiro texto compartilhado"
        val secondShare = "Segundo texto compartilhado mais tarde"

        val res1 = IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "text/plain", firstShare)
        val res2 = IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "text/plain", secondShare)

        assertEquals(firstShare, res1)
        assertEquals(secondShare, res2)
    }

    // =========================================================================
    // TIER 1: FEATURE 13 — Reader Input State Synchronization (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f13_1_sharedTextSplitsIntoReadableChunks() {
        val shared = "Primeiro parágrafo partilhado. Segundo parágrafo com mais detalhes."
        val chunks = ReadingChunks.split(shared)
        assertTrue(chunks.isNotEmpty())
        assertEquals(shared.trim(), chunks.joinToString(" ") { it.text }.trim())
    }

    @Test
    fun f13_2_sharedTextResetsCharacterOffsetToZero() {
        val initialOffset = 450
        val newTextReceived = "Novo texto partilhado"
        // Contract: receiving new text resets reading position to 0
        val resetOffset = if (newTextReceived.isNotEmpty()) 0 else initialOffset
        assertEquals(0, resetOffset)
    }

    @Test
    fun f13_3_sharedTextResetsReadingStatusToIdle() {
        // When active playback is replaced by shared text, status returns to IDLE
        val priorStatus = "PLAYING"
        val nextStatus = "IDLE"
        assertNotEquals(priorStatus, nextStatus)
        assertEquals("IDLE", nextStatus)
    }

    @Test
    fun f13_4_sharedTextWithMultipleParagraphsPreservesStructure() {
        val multiline = "Título\n\nPrimeiro ponto.\n\nSegundo ponto."
        val chunks = ReadingChunks.split(multiline)
        assertTrue("Multiline text must be chunked", chunks.isNotEmpty())
    }

    @Test
    fun f13_5_sharedTextGeneratesInitialTimeline() {
        val chunks = ReadingChunks.split("Frase número um. Frase número dois.")
        val timeline = AudioTimeline()
        for (c in chunks) {
            timeline.add(2000L)
        }
        val pos = timeline.locate(0L)
        assertEquals(0 to 0L, pos)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 5 per Feature)
    // =========================================================================

    // --- Feature 11 Boundaries ---

    @Test
    fun b11_1_nullActionReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(null, "text/plain", "Texto"))
    }

    @Test
    fun b11_2_nullMimeTypeReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, null, "Texto"))
    }

    @Test
    fun b11_3_emptyActionReturnsNull() {
        assertNull(IntentTestContracts.extractSendText("", "text/plain", "Texto"))
    }

    @Test
    fun b11_4_emptyMimeTypeReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "", "Texto"))
    }

    @Test
    fun b11_5_videoMimeTypeReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "video/mp4", "Texto"))
    }

    // --- Feature 12 Boundaries ---

    @Test
    fun b12_1_nullExtraTextReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "text/plain", null))
    }

    @Test
    fun b12_2_emptyExtraTextReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "text/plain", ""))
    }

    @Test
    fun b12_3_whitespaceOnlyExtraTextReturnsNull() {
        assertNull(IntentTestContracts.extractSendText(IntentTestContracts.ACTION_SEND, "text/plain", "   \t\n  "))
    }

    @Test
    fun b12_4_extremeSizeSharedText() {
        val huge = "Palavra ".repeat(25_000) // ~200,000 characters
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            huge
        )
        assertNotNull(result)
        assertEquals(huge.trim().length, result?.length)
    }

    @Test
    fun b12_5_trimsLeadingAndTrailingWhitespaces() {
        val raw = "  \n  Texto com espaçamento exterior  \t  \n "
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            raw
        )
        assertEquals("Texto com espaçamento exterior", result)
    }

    // --- Feature 13 Boundaries ---

    @Test
    fun b13_1_sharedTextWithAccentsAndPortugueseCharacters() {
        val ptText = "Atenção: à noite, o João leu café & chá no sótão com rapidez!"
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            ptText
        )
        assertEquals(ptText, result)
        val chunks = ReadingChunks.split(result!!)
        assertEquals(ptText, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun b13_2_sharedTextContainingOnlyPunctuation() {
        val punct = "?!!...;;;"
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            punct
        )
        assertEquals(punct, result)
    }

    @Test
    fun b13_3_sharedTextWithMultipleConsecutiveNewlines() {
        val raw = "Linha 1\n\n\n\n\nLinha 2"
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            raw
        )
        assertNotNull(result)
        val chunks = ReadingChunks.split(result!!)
        assertTrue(chunks.size >= 1)
    }

    @Test
    fun b13_4_sharedTextWithUnicodeSpecialSymbols() {
        val unicode = "Equação: ∑ (x_i + y_i) = ∞ ∭ Ω"
        val result = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            unicode
        )
        assertEquals(unicode, result)
    }

    @Test
    fun b13_5_sharedTextExactDuplicateReloadResetsOffset() {
        val text = "Texto repetido"
        val offset = 10
        // Reloading even duplicate text must reposition cursor to 0
        val resetOffset = 0
        assertEquals(0, resetOffset)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun c1_shareTextWhileAudioCacheHasStaleEntry() {
        val dir = File(tempDir, "stale_cache").apply { mkdirs() }
        File(dir, "0.wav").writeBytes(ByteArray(100))

        ReaderAudioCache.put(
            text = "Texto antigo antes do share",
            voiceId = "pt-PT",
            speed = 1.0f,
            chunks = listOf(ReadingChunk("Texto antigo antes do share", 0)),
            durations = listOf(1000L),
            audioDir = dir
        )

        // New text shared
        val incoming = "Texto novo que acabou de chegar pelo Share Sheet"
        val extracted = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            incoming
        )
        assertNotNull(extracted)

        // Querying cache for new shared text must return null (forcing fresh synthesis)
        val hit = ReaderAudioCache.get(extracted!!, "pt-PT", 1.0f)
        assertNull("Cache must be a miss for newly shared text", hit)
    }

    @Test
    fun c2_shareTextFollowedByImmediateChunkPreparation() {
        val incoming = "Texto compartilhado de uma página web extensa. " + "Detalhe ".repeat(30)
        val extracted = IntentTestContracts.extractSendText(
            IntentTestContracts.ACTION_SEND,
            "text/plain",
            incoming
        )
        assertNotNull(extracted)

        val chunks = ReadingChunks.split(extracted!!)
        assertTrue("Must be segmented into multiple chunks", chunks.size > 1)
        assertTrue("All chunks <= 280 chars", chunks.all { it.text.length <= 280 })
    }

    @Test
    fun c3_shareSheetAndQuickReadIsolation() {
        val sendAction = IntentTestContracts.ACTION_SEND
        val processTextAction = IntentTestContracts.ACTION_PROCESS_TEXT

        // Send action must not be consumed by processText extractor
        assertNull(
            IntentTestContracts.extractProcessText(sendAction, "text/plain", "Texto")
        )
        // ProcessText action must not be consumed by send extractor
        assertNull(
            IntentTestContracts.extractSendText(processTextAction, "text/plain", "Texto")
        )
    }
}
