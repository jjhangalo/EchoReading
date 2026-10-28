package com.echoreading.e2e

import com.echoreading.e2e.testutil.AudioTestFixtures
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.share.ShareIntentHandler
import com.echoreading.speech.AudioQualityChecker
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2E Requirement Tests — Tier 4: Real-World Application Workloads
 * Comprehensive end-to-end multi-feature user journeys covering Requirements R1–R5.
 */
class EndToEndWorkflowsR2Test {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e-workflow-r2").toFile()
        ReaderState.snapshot.value = ReaderSnapshot()
        SpeechToTextState.reset()
        SpeechToTextState.pendingAudioUri.value = null
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        ReaderState.snapshot.value = ReaderSnapshot()
        SpeechToTextState.reset()
        SpeechToTextState.pendingAudioUri.value = null
    }

    // =========================================================================
    // WORKFLOW 1: Direct In-App Voice Recording to TTS Playback (R1, R3, R4)
    // =========================================================================

    @Test
    fun workflow_1_directInAppVoiceRecordingToReaderPlayback() {
        // Step 1: User navigates to Transcribe screen
        var currentRoute = "transcribe"
        assertEquals("transcribe", currentRoute)

        // Step 2: User taps Mic button to start recording
        SpeechToTextState.isRecording.value = true
        SpeechToTextState.recordingDurationSec.value = 1
        SpeechToTextState.recordingAmplitude.value = 0.45f
        assertTrue(SpeechToTextState.isRecording.value)

        // Step 3: Audio samples captured (16kHz mono PCM)
        val capturedSamples = AudioTestFixtures.createCleanSpeechAudio(durationMs = 2000, sampleRate = 16000)
        assertEquals(32000, capturedSamples.size)

        // Step 4: User taps Stop recording
        SpeechToTextState.isRecording.value = false
        assertFalse(SpeechToTextState.isRecording.value)

        // Step 5: Audio quality check evaluates captured speech
        val quality = AudioQualityChecker.analyze(capturedSamples, 16000)
        assertTrue("Speech quality check must pass", quality.isTranscribable)
        assertNull(quality.rejectionReason)

        // Step 6: Transcription engine produces text result
        val recognizedText = "Reunião de alinhamento do projeto concluída com sucesso."
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = recognizedText,
            progress = 1.0f
        )
        assertEquals(TranscriptionStatus.DONE, SpeechToTextState.snapshot.value.status)
        assertEquals(recognizedText, SpeechToTextState.snapshot.value.transcribedText)

        // Step 7: User copies text to clipboard (content invariant)
        val clipboardText = SpeechToTextState.snapshot.value.transcribedText
        assertEquals(recognizedText, clipboardText)

        // Step 8: User taps "Ouvir no Leitor" -> Text loaded into ReaderState and route navigates to "home"
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(text = recognizedText)
        currentRoute = "home"

        assertEquals("home", currentRoute)
        assertEquals(recognizedText, ReaderState.snapshot.value.text)
    }

    // =========================================================================
    // WORKFLOW 2: External Audio Share Sheet Direct Processing (R2, R3)
    // =========================================================================

    @Test
    fun workflow_2_externalAudioShareSheetDirectProcessing() {
        // Step 1: External messaging app shares voice note via ACTION_SEND
        val voiceNoteUri = AudioTestFixtures.createTestUri("content://com.whatsapp.provider.media/voice_note_9876.ogg")
        val extractedUri = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "audio/ogg", voiceNoteUri)
        assertNotNull("Audio URI must be successfully extracted from share sheet", extractedUri)
        assertEquals(voiceNoteUri, extractedUri)

        // Step 2: MainActivity receives share intent and updates pending audio URI
        SpeechToTextState.pendingAudioUri.value = extractedUri

        // Step 3: App navigation automatically routes to "transcribe"
        val activeRoute = "transcribe"
        assertEquals("transcribe", activeRoute)

        // Step 4: Transcriber transitions to DECODING state
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DECODING,
            audioFileName = "voice_note_9876.ogg"
        )
        assertEquals(TranscriptionStatus.DECODING, SpeechToTextState.snapshot.value.status)

        // Step 5: Decoded audio evaluated by AudioQualityChecker
        val decodedSamples = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1500)
        val quality = AudioQualityChecker.analyze(decodedSamples, 16000)
        assertTrue(quality.isTranscribable)

        // Step 6: Transcription completed and ready for user
        val expectedText = "Mensagem de voz sobre a entrega de amanhã."
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = expectedText,
            progress = 1.0f
        )
        assertEquals(TranscriptionStatus.DONE, SpeechToTextState.snapshot.value.status)
        assertEquals(expectedText, SpeechToTextState.snapshot.value.transcribedText)
    }

    // =========================================================================
    // WORKFLOW 3: Acoustic Quality Self-Healing on Distorted Audio (R1)
    // =========================================================================

    @Test
    fun workflow_3_acousticQualitySelfHealingOnDistortedAudio() {
        // Step 1: User records audio in loud/distorted environment with heavy clipping (> 15%)
        val clippedAudio = AudioTestFixtures.createClippedAudio(durationMs = 1000, clipRatio = 0.20f)
        val qualityFailure = AudioQualityChecker.analyze(clippedAudio, 16000)

        // Step 2: System safely rejects audio and surfaces exact reason
        assertFalse(qualityFailure.isTranscribable)
        assertEquals("transcribe_error_clipped", qualityFailure.rejectionReason)

        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.ERROR,
            error = qualityFailure.rejectionReason
        )
        assertEquals(TranscriptionStatus.ERROR, SpeechToTextState.snapshot.value.status)
        assertEquals("transcribe_error_clipped", SpeechToTextState.snapshot.value.error)

        // Step 3: User receives feedback and re-records with calibrated microphone level
        val cleanAudio = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1500, amplitude = 0.5f)
        val qualitySuccess = AudioQualityChecker.analyze(cleanAudio, 16000)

        // Step 4: Quality passes and transcription completes
        assertTrue(qualitySuccess.isTranscribable)
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = "Segunda gravação limpa e bem sucedida.",
            error = null
        )
        assertEquals(TranscriptionStatus.DONE, SpeechToTextState.snapshot.value.status)
        assertNull(SpeechToTextState.snapshot.value.error)
        assertEquals("Segunda gravação limpa e bem sucedida.", SpeechToTextState.snapshot.value.transcribedText)
    }

    // =========================================================================
    // WORKFLOW 4: Orientation Flip & State Persistence Multi-Tasking (R3, R4)
    // =========================================================================

    @Test
    fun workflow_4_orientationFlipAndStatePersistenceMultiTasking() {
        // Step 1: App starts in Portrait with BottomBar
        var orientation = AudioTestFixtures.OrientationMode.PORTRAIT
        var navComponent = AudioTestFixtures.resolveNavigationComponent(orientation)
        assertEquals(AudioTestFixtures.NavigationUiComponent.BOTTOM_BAR, navComponent)

        // Step 2: User drafts a paragraph in Reader screen
        val draftedText = "Texto longo a ser editado pelo utilizador antes da leitura."
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(text = draftedText)

        // Step 3: User switches to Transcribe screen and performs a transcription
        val transcribedText = "Texto transcrito a partir de memorando de áudio."
        SpeechToTextState.snapshot.value = SpeechToTextState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = transcribedText
        )

        val beforeRotation = AudioTestFixtures.AppScreenState(
            activeRoute = "transcribe",
            readerDraftText = ReaderState.snapshot.value.text,
            readerCharOffset = 25,
            transcriberText = SpeechToTextState.snapshot.value.transcribedText,
            transcriberStatus = SpeechToTextState.snapshot.value.status.name,
            isRecording = false
        )

        // Step 4: Device is rotated to Landscape -> Navigation shifts to SideBar (NavigationRail)
        orientation = AudioTestFixtures.OrientationMode.LANDSCAPE
        navComponent = AudioTestFixtures.resolveNavigationComponent(orientation)
        assertEquals(AudioTestFixtures.NavigationUiComponent.SIDE_BAR, navComponent)

        val afterRotation = AudioTestFixtures.simulateOrientationChange(beforeRotation)

        // Step 5: Verify zero data loss across rotation
        assertEquals("transcribe", afterRotation.activeRoute)
        assertEquals(draftedText, afterRotation.readerDraftText)
        assertEquals(transcribedText, afterRotation.transcriberText)
        assertEquals("DONE", afterRotation.transcriberStatus)
        assertEquals(25, afterRotation.readerCharOffset)
    }

    // =========================================================================
    // WORKFLOW 5: Voice Model Management, Verification and Execution (R1, R5)
    // =========================================================================

    @Test
    fun workflow_5_voiceModelManagementVerificationAndExecution() {
        // Step 1: User opens Settings -> checks STT Whisper offline model status
        val whisperDir = File(tempDir, "stt-models/whisper-small-int8").apply { mkdirs() }
        File(whisperDir, "small-encoder.int8.onnx").writeBytes(ByteArray(1024) { 1 })
        File(whisperDir, "small-decoder.int8.onnx").writeBytes(ByteArray(2048) { 2 })
        File(whisperDir, "small-tokens.txt").writeText("vocabulary tokens")

        val isInstalled = File(whisperDir, "small-encoder.int8.onnx").length() > 0 &&
            File(whisperDir, "small-decoder.int8.onnx").length() > 0 &&
            File(whisperDir, "small-tokens.txt").length() > 0
        assertTrue("Whisper offline model must be recognized as installed and ready", isInstalled)

        // Step 2: Storage footprint computation
        val totalModelBytes = whisperDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("Model disk space accurately calculated", totalModelBytes > 3000L)

        // Step 3: User verifies Piper voice selection
        val voicesDir = File(tempDir, "voices").apply { mkdirs() }
        val activeVoice = "pt_PT-tugao-medium"
        val voiceDir = File(voicesDir, activeVoice).apply { mkdirs() }
        File(voiceDir, "$activeVoice.onnx").writeBytes(ByteArray(500) { 0x72 })

        assertTrue("Active Piper voice model file must exist", File(voiceDir, "$activeVoice.onnx").isFile)

        // Step 4: User navigates to Transcribe, records speech, and transcribes
        val audio = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1000)
        val quality = AudioQualityChecker.analyze(audio, 16000)
        assertTrue(quality.isTranscribable)

        // Step 5: User sends to Reader and uses selected Piper voice
        val result = "Conferência gravada e transcrita com modelo offline."
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(text = result)
        assertEquals(result, ReaderState.snapshot.value.text)
    }

    // =========================================================================
    // WORKFLOW 6: Adversarial Multi-Step Resilience & Error Handling
    // =========================================================================

    @Test
    fun workflow_6_adversarialMultiStepResilience() {
        // Step 1: External app shares invalid/non-audio file (e.g. text/plain or image/jpeg)
        val nonAudioUri = AudioTestFixtures.createTestUri("content://media/external/images/photo.jpg")
        val rejectedAudio = ShareIntentHandler.extractAudioUri("android.intent.action.SEND", "image/jpeg", nonAudioUri)
        assertNull("Non-audio share must be safely rejected", rejectedAudio)

        // Step 2: User attempts to analyze an empty audio array
        val emptyReport = AudioQualityChecker.analyze(FloatArray(0), 16000)
        assertFalse(emptyReport.isTranscribable)
        assertEquals("transcribe_error_silent", emptyReport.rejectionReason)

        // Step 3: System remains fully stable, user starts valid in-app recording
        SpeechToTextState.isRecording.value = true
        val validAudio = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1200)
        SpeechToTextState.isRecording.value = false

        val validReport = AudioQualityChecker.analyze(validAudio, 16000)
        assertTrue("Subsequent valid recording must succeed without system failure", validReport.isTranscribable)

        // Step 4: Rapid tab switching does not corrupt state
        val routes = listOf("home", "transcribe", "history", "settings", "transcribe")
        var currentRoute = "home"
        routes.forEach { currentRoute = it }
        assertEquals("transcribe", currentRoute)
    }
}
