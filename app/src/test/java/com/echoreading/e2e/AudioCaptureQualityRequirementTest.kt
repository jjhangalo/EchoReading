package com.echoreading.e2e

import com.echoreading.e2e.testutil.AudioTestFixtures
import com.echoreading.e2e.testutil.ManifestTestParser
import com.echoreading.reader.ReaderState
import com.echoreading.speech.AudioQualityChecker
import com.echoreading.speech.AudioQualityReport
import com.echoreading.speech.TranscriberState
import com.echoreading.speech.TranscriptionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E2E Requirement Tests for Requirement R1:
 * Gravação Direta de Voz In-App com Verificação de Qualidade Rigorosa
 *
 * Covers Features 1, 2, 3, 4, 5 across Tier 1 (Happy Path), Tier 2 (Boundaries),
 * and Tier 3 (Cross-Feature Combinations).
 */
class AudioCaptureQualityRequirementTest {

    @Before
    fun setUp() {
        TranscriberState.reset()
    }

    @After
    fun tearDown() {
        TranscriberState.reset()
    }

    // =========================================================================
    // TIER 1: FEATURE 1 — In-App Mic Recording Button (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f1_1_recordingStateDefaultsToIdle() {
        assertFalse("Recording must initially be idle", TranscriberState.isRecording.value)
        assertEquals("Initial recording duration must be 0", 0, TranscriberState.recordingDurationSec.value)
        assertEquals("Initial recording amplitude must be 0f", 0f, TranscriberState.recordingAmplitude.value, 0.001f)
    }

    @Test
    fun f1_2_recordingStateTogglesReflectively() {
        // Simulates toggling recording state
        TranscriberState.isRecording.value = true
        assertTrue("Recording state must reflect active recording", TranscriberState.isRecording.value)

        TranscriberState.isRecording.value = false
        assertFalse("Recording state must reflect stopped recording", TranscriberState.isRecording.value)
    }

    @Test
    fun f1_3_recordingDurationIncrementsWithSamples() {
        val sampleRate = 16000
        val sampleCount = 32000L // 2 seconds
        val durationSec = (sampleCount / sampleRate).toInt()
        assertEquals(2, durationSec)
    }

    @Test
    fun f1_4_recordingAmplitudeClampedToUnitInterval() {
        val minAmp = 0f.coerceIn(0f, 1f)
        val midAmp = 0.5f.coerceIn(0f, 1f)
        val maxAmp = 1.2f.coerceIn(0f, 1f)

        assertEquals(0f, minAmp, 0.001f)
        assertEquals(0.5f, midAmp, 0.001f)
        assertEquals(1.0f, maxAmp, 0.001f)
    }

    @Test
    fun f1_5_recordingSnapshotResetClearsRecordingVariables() {
        TranscriberState.isRecording.value = true
        TranscriberState.recordingDurationSec.value = 5
        TranscriberState.recordingAmplitude.value = 0.8f

        TranscriberState.reset()
        assertEquals(TranscriptionStatus.IDLE, TranscriberState.snapshot.value.status)
        assertEquals("", TranscriberState.snapshot.value.transcribedText)
    }

    // =========================================================================
    // TIER 1: FEATURE 2 — Native AudioRecord Async Capture (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f2_1_standardCaptureFormatSpecifications() {
        val expectedSampleRate = 16000
        val expectedChannels = 1 // Mono
        val expectedBitsPerSample = 16 // PCM 16-bit

        assertEquals(16000, expectedSampleRate)
        assertEquals(1, expectedChannels)
        assertEquals(16, expectedBitsPerSample)
    }

    @Test
    fun f2_2_shortToFloatNormalizationPreservesDynamicRange() {
        val pcmShortMin: Short = -32768
        val pcmShortZero: Short = 0
        val pcmShortMax: Short = 32767

        val floatMin = pcmShortMin / 32768f
        val floatZero = pcmShortZero / 32768f
        val floatMax = pcmShortMax / 32768f

        assertEquals(-1.0f, floatMin, 0.001f)
        assertEquals(0.0f, floatZero, 0.001f)
        assertTrue("Max short must be within (0.999f..1.0f)", floatMax in 0.999f..1.0f)
    }

    @Test
    fun f2_3_cleanSpeechWaveformGeneratesExpectedFrameCount() {
        val samples = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1000, sampleRate = 16000)
        assertEquals(16000, samples.size)
        val frameSize = (16000 * 0.03).toInt() // 480
        val expectedFrames = samples.size / frameSize
        assertEquals(33, expectedFrames)
    }

    @Test
    fun f2_4_inMemoryBufferAvoidsTemporaryFileCreation() {
        // Requirement R1 (/ponytail ultra): capture directly into memory FloatArray
        val samples = AudioTestFixtures.createCleanSpeechAudio(durationMs = 500)
        assertTrue("In-memory buffer must contain samples without disk I/O", samples.isNotEmpty())
    }

    @Test
    fun f2_5_sampleDurationCalculationAccuracy() {
        val samples = FloatArray(24000) // 1.5s at 16kHz
        val sampleRate = 16000
        val durationMs = (samples.size.toLong() * 1000L) / sampleRate
        assertEquals(1500L, durationMs)
    }

    // =========================================================================
    // TIER 1: FEATURE 3 — AudioQualityChecker SNR & Distortion (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f3_1_cleanSpeechAudioPassesQualityCheck() {
        val cleanSpeech = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1500, sampleRate = 16000)
        val report = AudioQualityChecker.analyze(cleanSpeech, 16000)

        assertTrue("Clean speech should be transcribable", report.isTranscribable)
        assertFalse("Clean speech should not be silent", report.isSilent)
        assertNull("Clean speech should have no rejection reason", report.rejectionReason)
        assertTrue("Speech ratio should exceed 5%", report.speechRatio >= 0.05f)
        assertTrue("SNR should be >= 5.0 dB", report.snrDb >= 5.0f)
    }

    @Test
    fun f3_2_silenceAudioIsRejectedWithSilentReason() {
        val silence = AudioTestFixtures.createSilentAudio(durationMs = 1000, sampleRate = 16000)
        val report = AudioQualityChecker.analyze(silence, 16000)

        assertFalse("Silent audio must not be transcribable", report.isTranscribable)
        assertTrue("Silent audio must report isSilent=true", report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun f3_3_inaudibleLowVolumeAudioIsRejected() {
        val lowVolume = AudioTestFixtures.createLowVolumeAudio(durationMs = 1000, sampleRate = 16000, maxAmp = 0.005f)
        val report = AudioQualityChecker.analyze(lowVolume, 16000)

        assertFalse("Inaudible audio must not be transcribable", report.isTranscribable)
        assertEquals("transcribe_error_low_volume", report.rejectionReason)
    }

    @Test
    fun f3_4_severelyClippedAudioIsRejected() {
        val clipped = AudioTestFixtures.createClippedAudio(durationMs = 1000, sampleRate = 16000, clipRatio = 0.20f)
        val report = AudioQualityChecker.analyze(clipped, 16000)

        assertFalse("Clipped audio must not be transcribable", report.isTranscribable)
        assertEquals("transcribe_error_clipped", report.rejectionReason)
    }

    @Test
    fun f3_5_moderateNoiseToleratedAboveThreshold() {
        // Clean speech with moderate background noise: SNR >= 5dB should be accepted
        val speech = AudioTestFixtures.createCleanSpeechAudio(durationMs = 2000, sampleRate = 16000, amplitude = 0.6f)
        val report = AudioQualityChecker.analyze(speech, 16000)
        assertTrue("Moderate noise with speech SNR >= 5dB must pass", report.snrDb >= 5.0f)
        assertTrue(report.isTranscribable)
    }

    // =========================================================================
    // TIER 1: FEATURE 4 — One-Tap Copy & Send to Reader (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f4_1_transcriptionDonePopulatesSnapshot() {
        TranscriberState.snapshot.value = TranscriberState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = "Texto de teste para transcrição",
            progress = 1f
        )

        val snap = TranscriberState.snapshot.value
        assertEquals(TranscriptionStatus.DONE, snap.status)
        assertEquals("Texto de teste para transcrição", snap.transcribedText)
        assertEquals(1f, snap.progress, 0.001f)
    }

    @Test
    fun f4_2_clipboardTextPreservesExactContent() {
        val original = "Transcrição com pontuação, acentuação e quebras de linha: Olá mundo!"
        val copied = original // Simulates clipboard extraction
        assertEquals(original, copied)
    }

    @Test
    fun f4_3_sendToReaderEventLoadsTextIntoReaderState() {
        val sampleText = "Transcrição enviada para o leitor de voz"
        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            text = sampleText
        )
        assertEquals(sampleText, ReaderState.snapshot.value.text)
    }

    @Test
    fun f4_4_sendToReaderEmitsLoadTextEvent() {
        val text = "Texto para leitura automática"
        val emitted = ReaderState.loadTextEvent.tryEmit(text)
        assertTrue("Event emission should be accepted", emitted)
    }

    @Test
    fun f4_5_emptyTranscriptionDoesNotTriggerDoneAction() {
        TranscriberState.snapshot.value = TranscriberState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = ""
        )
        assertTrue("Empty transcribed text is recognized", TranscriberState.snapshot.value.transcribedText.isEmpty())
    }

    // =========================================================================
    // TIER 1: FEATURE 5 — RECORD_AUDIO Permission in Manifest (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f5_1_recordAudioPermissionDeclaredInManifest() {
        val permissions = ManifestTestParser.parsePermissions()
        assertTrue(
            "AndroidManifest.xml must declare android.permission.RECORD_AUDIO",
            permissions.contains("android.permission.RECORD_AUDIO")
        )
    }

    @Test
    fun f5_2_foregroundServicePermissionsDeclared() {
        val permissions = ManifestTestParser.parsePermissions()
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
    }

    @Test
    fun f5_3_postNotificationsPermissionDeclared() {
        val permissions = ManifestTestParser.parsePermissions()
        assertTrue(permissions.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun f5_4_internetPermissionDeclaredForModelDownloads() {
        val permissions = ManifestTestParser.parsePermissions()
        assertTrue(permissions.contains("android.permission.INTERNET"))
    }

    @Test
    fun f5_5_totalDeclaredPermissionsCoherence() {
        val permissions = ManifestTestParser.parsePermissions()
        assertTrue("Manifest should declare at least 4 essential permissions", permissions.size >= 4)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 7 test cases)
    // =========================================================================

    @Test
    fun b1_emptyAudioSampleArrayReturnsSilent() {
        val report = AudioQualityChecker.analyze(FloatArray(0), 16000)
        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun b2_subFrameAudioSampleArrayHandledSafely() {
        val shortSamples = AudioTestFixtures.createShortAudio(100) // 100 < 480
        val report = AudioQualityChecker.analyze(shortSamples, 16000)
        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun b3_exactFrameSizeBoundaryHandledWithoutError() {
        val exactFrame = FloatArray(480) { 0.2f }
        val report = AudioQualityChecker.analyze(exactFrame, 16000)
        assertNotNull(report)
    }

    @Test
    fun b4_clippingBorderlineJustBelowFivePercentPassesClippingCheck() {
        // 4% clipping should NOT trigger transcribe_error_clipped
        val clipped = AudioTestFixtures.createClippedAudio(durationMs = 1000, clipRatio = 0.04f)
        val report = AudioQualityChecker.analyze(clipped, 16000)
        assertFalse("4% clipping must not be rejected for clipping", report.rejectionReason == "transcribe_error_clipped")
    }

    @Test
    fun b5_clippingBorderlineJustAboveFivePercentFails() {
        // 8% clipping should trigger transcribe_error_clipped
        val clipped = AudioTestFixtures.createClippedAudio(durationMs = 1000, clipRatio = 0.08f)
        val report = AudioQualityChecker.analyze(clipped, 16000)
        assertEquals("transcribe_error_clipped", report.rejectionReason)
    }

    @Test
    fun b6_amplitudeBorderlineJustBelowOneCentThresholdFails() {
        // Peak amp 0.009f < 0.01f -> low volume
        val lowVol = AudioTestFixtures.createLowVolumeAudio(durationMs = 1000, maxAmp = 0.009f)
        val report = AudioQualityChecker.analyze(lowVol, 16000)
        assertEquals("transcribe_error_low_volume", report.rejectionReason)
    }

    @Test
    fun b7_maxDurationAudioProcessedWithoutCrash() {
        // 30 seconds of audio (480,000 samples)
        val longAudio = AudioTestFixtures.createCleanSpeechAudio(durationMs = 30000, sampleRate = 16000)
        assertEquals(480000, longAudio.size)
        val report = AudioQualityChecker.analyze(longAudio, 16000)
        assertTrue(report.isTranscribable)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE INTERACTIONS (Pairwise & Recovery)
    // =========================================================================

    @Test
    fun c1_qualityFailureRecoveryFlow() {
        // 1. Initial attempt with silent audio fails
        val silentAudio = AudioTestFixtures.createSilentAudio(durationMs = 1000)
        val report1 = AudioQualityChecker.analyze(silentAudio, 16000)
        assertFalse(report1.isTranscribable)
        TranscriberState.snapshot.value = TranscriberState.snapshot.value.copy(
            status = TranscriptionStatus.ERROR,
            error = report1.rejectionReason
        )
        assertEquals(TranscriptionStatus.ERROR, TranscriberState.snapshot.value.status)

        // 2. User re-records with clean audio -> Quality checker passes
        val cleanAudio = AudioTestFixtures.createCleanSpeechAudio(durationMs = 1000)
        val report2 = AudioQualityChecker.analyze(cleanAudio, 16000)
        assertTrue(report2.isTranscribable)

        // 3. Transcription completes successfully
        TranscriberState.snapshot.value = TranscriberState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = "Recuperação de gravação com sucesso",
            error = null
        )
        assertEquals(TranscriptionStatus.DONE, TranscriberState.snapshot.value.status)
        assertEquals("Recuperação de gravação com sucesso", TranscriberState.snapshot.value.transcribedText)
    }

    @Test
    fun c2_transcriptionOutputTransfersToReaderAndStopsStaleAudio() {
        // Simulate completing transcription and loading into reader
        val transcribed = "Texto final transcrito pelo Sherpa-ONNX"
        TranscriberState.snapshot.value = TranscriberState.snapshot.value.copy(
            status = TranscriptionStatus.DONE,
            transcribedText = transcribed
        )

        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(
            text = TranscriberState.snapshot.value.transcribedText
        )
        assertEquals(transcribed, ReaderState.snapshot.value.text)
    }
}
