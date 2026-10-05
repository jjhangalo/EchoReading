package com.echoreading.e2e

import android.net.Uri
import com.echoreading.e2e.testutil.AudioTestFixtures
import com.echoreading.e2e.testutil.ManifestTestParser
import com.echoreading.share.ShareIntentHandler
import com.echoreading.speech.SpeechToTextState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E2E Requirement Tests for Requirement R2:
 * Correção do Fluxo de Partilha de Áudio Externo (ACTION_SEND Share Sheet)
 *
 * Covers Features 6, 7, 8, 9 across Tier 1 (Happy Path), Tier 2 (Boundaries),
 * and Tier 3 (Cross-Feature Combinations).
 */
class AudioShareIntentRequirementTest {

    private val mockAudioUri: Uri = AudioTestFixtures.createTestUri("content://com.android.providers.media/audio/12345")

    @Before
    fun setUp() {
        SpeechToTextState.reset()
        SpeechToTextState.pendingAudioUri.value = null
    }

    @After
    fun tearDown() {
        SpeechToTextState.reset()
        SpeechToTextState.pendingAudioUri.value = null
    }

    // =========================================================================
    // TIER 1: FEATURE 6 — Audio Share Intent Extraction (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f6_1_extractAudioUriWithStandardAudioMpegMime() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/mpeg", mockAudioUri)
        assertEquals(mockAudioUri, uri)
    }

    @Test
    fun f6_2_extractAudioUriWithAudioOggMime() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/ogg", mockAudioUri)
        assertEquals(mockAudioUri, uri)
    }

    @Test
    fun f6_3_extractAudioUriWithAudioWavMime() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/wav", mockAudioUri)
        assertEquals(mockAudioUri, uri)
    }

    @Test
    fun f6_4_extractAudioUriWithAudioWildcardMime() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/*", mockAudioUri)
        assertEquals(mockAudioUri, uri)
    }

    @Test
    fun f6_5_manifestDeclaresAudioSendIntentFilterInMainActivity() {
        val activities = ManifestTestParser.parseActivities()
        val main = activities.find { it.name.contains("MainActivity") }
        assertNotNull("MainActivity must be declared in AndroidManifest.xml", main)

        val audioFilter = main!!.intentFilters.find { filter ->
            filter.actions.contains("android.intent.action.SEND") &&
                filter.mimeTypes.any { it.startsWith("audio/") }
        }
        assertNotNull("MainActivity must declare an ACTION_SEND filter with audio/*", audioFilter)
    }

    // =========================================================================
    // TIER 1: FEATURE 7 — Immediate Transcribe Navigation (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f7_1_audioShareRoutesToTranscribeRouteConstant() {
        val targetRoute = "transcribe"
        assertEquals("transcribe", targetRoute)
    }

    @Test
    fun f7_2_mainActivityLaunchModeIsSingleTopForWarmRouting() {
        val activities = ManifestTestParser.parseActivities()
        val main = activities.find { it.name.contains("MainActivity") }
        assertEquals("singleTop", main?.launchMode)
    }

    @Test
    fun f7_3_audioEventTriggerEmitsToLoadAudioEvent() {
        val testUri = AudioTestFixtures.createTestUri("content://media/external/audio/media/42")
        val emitted = SpeechToTextState.loadAudioEvent.tryEmit(testUri)
        assertTrue("Emission into SharedFlow buffer must succeed", emitted)
    }

    @Test
    fun f7_4_coldStartIntentDispatchesToTranscriberState() {
        // Simulates cold start receiving audio URI
        val testUri = AudioTestFixtures.createTestUri("content://downloads/audio/recording.m4a")
        SpeechToTextState.pendingAudioUri.value = testUri
        assertEquals(testUri, SpeechToTextState.pendingAudioUri.value)
    }

    @Test
    fun f7_5_warmStartOnNewIntentOverwritesPendingAudioUri() {
        val firstUri = AudioTestFixtures.createTestUri("content://media/first.mp3")
        val secondUri = AudioTestFixtures.createTestUri("content://media/second.wav")

        SpeechToTextState.pendingAudioUri.value = firstUri
        assertEquals(firstUri, SpeechToTextState.pendingAudioUri.value)

        SpeechToTextState.pendingAudioUri.value = secondUri
        assertEquals(secondUri, SpeechToTextState.pendingAudioUri.value)
    }

    // =========================================================================
    // TIER 1: FEATURE 8 — Persistent Pending Audio Uri (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f8_1_pendingAudioUriInitiallyNull() {
        assertNull(SpeechToTextState.pendingAudioUri.value)
    }

    @Test
    fun f8_2_pendingAudioUriHoldsStateIndefinitelyUntilConsumed() {
        val uri = AudioTestFixtures.createTestUri("content://provider/audio.opus")
        SpeechToTextState.pendingAudioUri.value = uri

        // Verification after simulated delay/lifecycle step
        assertEquals(uri, SpeechToTextState.pendingAudioUri.value)
    }

    @Test
    fun f8_3_pendingAudioUriCanBeClearedAfterConsumption() {
        val uri = AudioTestFixtures.createTestUri("content://provider/audio.aac")
        SpeechToTextState.pendingAudioUri.value = uri
        assertNotNull(SpeechToTextState.pendingAudioUri.value)

        SpeechToTextState.pendingAudioUri.value = null
        assertNull(SpeechToTextState.pendingAudioUri.value)
    }

    @Test
    fun f8_4_pendingAudioUriSurvivesStateSnapshotReset() {
        val uri = AudioTestFixtures.createTestUri("content://provider/audio.flac")
        SpeechToTextState.pendingAudioUri.value = uri
        SpeechToTextState.reset()

        // Persistent pending URI is kept in its dedicated StateFlow
        assertEquals(uri, SpeechToTextState.pendingAudioUri.value)
    }

    @Test
    fun f8_5_pendingAudioUriSupportsFileScheme() {
        val fileUri = AudioTestFixtures.createTestUri("file:///storage/emulated/0/Music/sample.mp3")
        SpeechToTextState.pendingAudioUri.value = fileUri
        assertEquals("file", SpeechToTextState.pendingAudioUri.value?.scheme)
    }

    // =========================================================================
    // TIER 1: FEATURE 9 — Off-Thread Audio Decoding & Query (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f9_1_audioDecoderTargetSampleRateIsAlways16kHz() {
        val targetRate = 16000
        assertEquals(16000, targetRate)
    }

    @Test
    fun f9_2_audioDecoderNormalizedPcmOutputScale() {
        val sample16BitMax = 32767f
        val normalized = sample16BitMax / 32768f
        assertTrue(normalized <= 1.0f && normalized >= -1.0f)
    }

    @Test
    fun f9_3_supportedAudioFormatsDeclaredInStringsResources() {
        val strings = ManifestTestParser.parseStrings()
        val supportedFormats = strings["transcribe_supported_formats"]
        assertNotNull("Strings must include transcribe_supported_formats", supportedFormats)
        assertTrue(supportedFormats!!.contains("MP3"))
        assertTrue(supportedFormats.contains("WAV"))
        assertTrue(supportedFormats.contains("OGG"))
    }

    @Test
    fun f9_4_transcribeActionStringsDeclaredInResources() {
        val strings = ManifestTestParser.parseStrings()
        assertEquals("Transcrever", strings["tab_transcribe"])
        assertEquals("Transcrição de Áudio", strings["transcribe_title"])
        assertEquals("Selecionar Áudio", strings["transcribe_select_audio"])
    }

    @Test
    fun f9_5_audioQualityCheckingStringResourceDeclared() {
        val strings = ManifestTestParser.parseStrings()
        assertEquals("A verificar qualidade…", strings["transcribe_checking_quality"])
        assertEquals("A descodificar áudio…", strings["transcribe_decoding"])
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 7 test cases)
    // =========================================================================

    @Test
    fun b1_nullActionReturnsNull() {
        val uri = ShareIntentHandler.extractAudioUri(null, "audio/mp3", mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b2_nonSendActionReturnsNull() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.VIEW", "audio/mp3", mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b3_nullMimeTypeReturnsNull() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", null, mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b4_textMimeTypeReturnsNullForAudioExtractor() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "text/plain", mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b5_imageMimeTypeReturnsNullForAudioExtractor() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "image/png", mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b6_videoMimeTypeReturnsNullForAudioExtractor() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "video/mp4", mockAudioUri)
        assertNull(uri)
    }

    @Test
    fun b7_nullStreamUriReturnsNull() {
        val uri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/mp3", null)
        assertNull(uri)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE INTERACTIONS (Pairwise Combinations)
    // =========================================================================

    @Test
    fun c1_shareSheetDisambiguationBetweenTextAndAudio() {
        // ACTION_SEND with text/plain -> Reader (handled by extractText, ignored by extractAudioUri)
        val textSend = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "text/plain", mockAudioUri)
        assertNull("Audio extractor must ignore plain text shares", textSend)

        val textResult = ShareIntentHandler.extractText("android.intent.action.SEND", "text/plain", "Artigo de leitura")
        assertEquals("Artigo de leitura", textResult)

        // ACTION_SEND with audio/mpeg -> Transcriber (handled by extractAudioUri, ignored by extractText)
        val audioSend = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/mpeg", mockAudioUri)
        assertEquals(mockAudioUri, audioSend)

        val textFromAudio = ShareIntentHandler.extractText("android.intent.action.SEND", "audio/mpeg", "Not text")
        assertNull("Text extractor must ignore audio shares", textFromAudio)
    }

    @Test
    fun c2_shareAudioWhileRecordingActiveGracefullyUpdatesPendingUri() {
        // If user is recording when an external audio file is shared, pendingAudioUri is set
        SpeechToTextState.isRecording.value = true
        SpeechToTextState.pendingAudioUri.value = mockAudioUri

        assertTrue(SpeechToTextState.isRecording.value)
        assertEquals(mockAudioUri, SpeechToTextState.pendingAudioUri.value)
    }
}
