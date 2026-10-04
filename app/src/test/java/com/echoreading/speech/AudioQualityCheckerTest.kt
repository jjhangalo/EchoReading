package com.echoreading.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class AudioQualityCheckerTest {

    private val sampleRate = 16000
    private val frameSize = (sampleRate * 0.03).toInt() // 480 samples

    @Test
    fun testEmptyAudio_rejectedAsSilent() {
        val samples = FloatArray(0)
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun testTooShortAudio_rejectedAsSilent() {
        val samples = FloatArray(frameSize - 1) { 0.5f }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun testAllZeros_rejectedAsSilent() {
        val samples = FloatArray(frameSize * 5) { 0f }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_silent", report.rejectionReason)
    }

    @Test
    fun testInaudibleLowVolume_rejectedAsLowVolume() {
        // Peak amplitude is 0.005f (< 0.01f threshold)
        val samples = FloatArray(frameSize * 10) { i ->
            0.005f * sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
        }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertTrue(report.isSilent)
        assertEquals("transcribe_error_low_volume", report.rejectionReason)
    }

    @Test
    fun testDigitalClipping_rejectedAsClipped() {
        // Generate audio where 10% of samples are clipped (>= 0.99f)
        val totalSamples = frameSize * 10
        val samples = FloatArray(totalSamples) { i ->
            if (i < totalSamples * 0.10) {
                1.0f // Clipped sample
            } else {
                0.3f * sin(2.0 * Math.PI * 300.0 * i / sampleRate).toFloat()
            }
        }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertFalse(report.isSilent)
        assertEquals("transcribe_error_clipped", report.rejectionReason)
    }

    @Test
    fun testNegativeSamplesClippingDetection() {
        // Digital clipping also occurs at negative saturation (<= -0.99f)
        val totalSamples = frameSize * 10
        val samples = FloatArray(totalSamples) { i ->
            if (i < totalSamples * 0.08) {
                -0.995f // Negative saturation
            } else {
                0.25f * sin(2.0 * Math.PI * 250.0 * i / sampleRate).toFloat()
            }
        }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertEquals("transcribe_error_clipped", report.rejectionReason)
    }

    @Test
    fun testBoundaryClipping_belowFivePercent_acceptedIfSpeechValid() {
        // 3% clipped samples (below 5% threshold) with clean speech
        val totalSamples = frameSize * 20
        val samples = FloatArray(totalSamples) { i ->
            if (i < totalSamples * 0.03) {
                1.0f
            } else {
                0.35f * sin(2.0 * Math.PI * 400.0 * i / sampleRate).toFloat()
            }
        }
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertTrue("Should be transcribable when clipping <= 5%", report.isTranscribable)
        assertNull(report.rejectionReason)
    }

    @Test
    fun testNoSpeech_rejectedAsNoSpeech() {
        // Audio with 100 frames total, but only 2 frames have speech (speechRatio = 2% < 5%)
        val totalFrames = 100
        val samples = FloatArray(totalFrames * frameSize)
        // Set speech only in the first 2 frames
        for (i in 0 until (2 * frameSize)) {
            samples[i] = 0.4f * sin(2.0 * Math.PI * 500.0 * i / sampleRate).toFloat()
        }
        // Remaining 98 frames are silence (0f)
        val report = AudioQualityChecker.analyze(samples, sampleRate)

        assertFalse(report.isTranscribable)
        assertFalse(report.isSilent)
        assertEquals("transcribe_error_no_speech", report.rejectionReason)
        assertTrue(report.speechRatio < 0.05f)
    }

    @Test
    fun testDeafeningNoise_lowSnr_rejectedAsNoisy() {
        // 1 frame with peak 0.5f to set VAD threshold = 0.05f
        // 100 frames of speech-classified signal just above threshold (RMS ~ 0.051f)
        // 50 frames of noise-classified signal just below threshold (RMS ~ 0.048f)
        // Resulting in SNR ~ 3.4 dB (< 5.0 dB)
        val frame1 = FloatArray(frameSize) { 0.5f }
        val speechLevel = (0.051f * Math.sqrt(2.0)).toFloat()
        val speechFrames = FloatArray(100 * frameSize) { i ->
            speechLevel * sin(2.0 * Math.PI * 300.0 * i / sampleRate).toFloat()
        }
        val noiseLevel = (0.048f * Math.sqrt(2.0)).toFloat()
        val noiseFrames = FloatArray(50 * frameSize) { i ->
            noiseLevel * sin(2.0 * Math.PI * 700.0 * i / sampleRate).toFloat()
        }

        val allSamples = frame1 + speechFrames + noiseFrames
        val report = AudioQualityChecker.analyze(allSamples, sampleRate)

        assertFalse(report.isTranscribable)
        assertEquals("transcribe_error_noisy", report.rejectionReason)
        assertTrue("SNR should be < 5.0 dB", report.snrDb < 5.0f)
    }

    @Test
    fun testModerateBackgroundNoise_accepted() {
        // Speech with moderate background noise where SNR >= 5.0 dB
        // 1 frame of 0.5f sets VAD threshold = 0.05f
        // 40 frames of speech at RMS ~ 0.06f (above 0.05f)
        // 20 frames of background noise at RMS ~ 0.048f (below 0.05f)
        // Resulting SNR ~ 6.2 dB (>= 5.0 dB)
        val frame1 = FloatArray(frameSize) { 0.5f }
        val speechLevel = (0.060f * Math.sqrt(2.0)).toFloat()
        val speechFrames = FloatArray(40 * frameSize) { i ->
            speechLevel * sin(2.0 * Math.PI * 350.0 * i / sampleRate).toFloat()
        }
        val noiseLevel = (0.048f * Math.sqrt(2.0)).toFloat()
        val noiseFrames = FloatArray(20 * frameSize) { i ->
            noiseLevel * sin(2.0 * Math.PI * 800.0 * i / sampleRate).toFloat()
        }

        val allSamples = frame1 + speechFrames + noiseFrames
        val report = AudioQualityChecker.analyze(allSamples, sampleRate)

        assertTrue("Moderate noise with SNR >= 5.0 dB should pass", report.isTranscribable)
        assertNull(report.rejectionReason)
        assertTrue("SNR should be >= 5.0 dB", report.snrDb >= 5.0f)
    }

    @Test
    fun testCleanSpeech_accepted() {
        // 10 frames of clear speech followed by 10 frames of quiet pause
        val speechFrames = FloatArray(10 * frameSize) { i ->
            0.35f * sin(2.0 * Math.PI * 440.0 * i / sampleRate).toFloat()
        }
        val silenceFrames = FloatArray(10 * frameSize) { 0f }
        val allSamples = speechFrames + silenceFrames

        val report = AudioQualityChecker.analyze(allSamples, sampleRate)

        assertTrue("Clean speech should be transcribable", report.isTranscribable)
        assertNull(report.rejectionReason)
        assertTrue("Clean speech should have high speech ratio", report.speechRatio >= 0.4f)
        assertTrue("Clean speech should have high SNR", report.snrDb >= 20.0f)
    }

    @Test
    fun testTranscriberStateStateFlowsInitialValuesAndReset() {
        SpeechToTextState.reset()

        assertFalse(SpeechToTextState.isRecording.value)
        assertEquals(0, SpeechToTextState.recordingDurationSec.value)
        assertEquals(0f, SpeechToTextState.recordingAmplitude.value, 0.001f)
        assertEquals(TranscriptionStatus.IDLE, SpeechToTextState.snapshot.value.status)
        assertNull(SpeechToTextState.pendingAudioUri.value)
    }
}
