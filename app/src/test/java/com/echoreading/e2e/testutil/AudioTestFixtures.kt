package com.echoreading.e2e.testutil

import android.net.Uri
import kotlin.math.PI
import kotlin.math.sin

/**
 * Audio waveform synthesis utilities, DSP test helpers, and contract simulators
 * for EchoReading 2.0 E2E testing track (Requirements R1–R5).
 */
object AudioTestFixtures {

    const val DEFAULT_SAMPLE_RATE = 16000
    const val FRAME_SIZE_MS = 30
    const val DEFAULT_FRAME_SIZE = (DEFAULT_SAMPLE_RATE * 0.03).toInt() // 480 samples

    /**
     * Generates a clean synthetic speech-like waveform with alternating voice activity
     * and silence to simulate human speech cadence.
     * Guaranteed: peak amplitude in safe range [0.3f, 0.7f], speechRatio > 20%, SNR > 15 dB.
     */
    fun createCleanSpeechAudio(
        durationMs: Int = 1000,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        frequency: Float = 220f,
        amplitude: Float = 0.5f,
    ): FloatArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(480)
        val samples = FloatArray(totalSamples)
        val frameSize = (sampleRate * 0.03).toInt()

        for (i in 0 until totalSamples) {
            val frameIndex = i / frameSize
            // Alternate 2 voiced frames, 1 unvoiced/quiet frame
            val isVoiced = (frameIndex % 3) != 2
            if (isVoiced) {
                val t = i.toDouble() / sampleRate
                // Fundamental + harmonic
                val wave = 0.7 * sin(2.0 * PI * frequency * t) + 0.3 * sin(2.0 * PI * frequency * 2.0 * t)
                samples[i] = (wave * amplitude).toFloat().coerceIn(-0.95f, 0.95f)
            } else {
                // Background quiet floor
                samples[i] = 0.001f
            }
        }
        return samples
    }

    /**
     * Generates complete silence (all zeros).
     */
    fun createSilentAudio(
        durationMs: Int = 1000,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): FloatArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(480)
        return FloatArray(totalSamples)
    }

    /**
     * Generates low-volume inaudible audio with peak amplitude below the 0.01f threshold.
     */
    fun createLowVolumeAudio(
        durationMs: Int = 1000,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        maxAmp: Float = 0.005f,
    ): FloatArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(480)
        val samples = FloatArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            samples[i] = (sin(2.0 * PI * 440.0 * t) * maxAmp).toFloat()
        }
        return samples
    }

    /**
     * Generates an audio buffer with a controlled percentage of clipped/saturated samples.
     * @param clipRatio Fraction of samples (0.0 to 1.0) clamped to +/- 1.0f
     */
    fun createClippedAudio(
        durationMs: Int = 1000,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        clipRatio: Float = 0.15f,
    ): FloatArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(480)
        val samples = FloatArray(totalSamples)
        val clipCount = (totalSamples * clipRatio).toInt()

        for (i in 0 until totalSamples) {
            if (i < clipCount) {
                samples[i] = if (i % 2 == 0) 1.0f else -1.0f
            } else {
                val t = i.toDouble() / sampleRate
                samples[i] = (sin(2.0 * PI * 300.0 * t) * 0.4f).toFloat()
            }
        }
        return samples
    }

    /**
     * Generates noisy audio where ambient noise overwhelms speech (SNR < 5.0 dB).
     */
    fun createNoisyAudio(
        durationMs: Int = 1000,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ): FloatArray {
        val totalSamples = (sampleRate * (durationMs / 1000.0)).toInt().coerceAtLeast(480)
        val samples = FloatArray(totalSamples)
        // High continuous noise floor across every frame
        var seed = 123456789L
        for (i in 0 until totalSamples) {
            seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
            val noise = (seed.toFloat() / 0x7fffffffL) * 2f - 1f // Uniform noise [-1, 1]
            samples[i] = noise * 0.35f
        }
        return samples
    }

    /**
     * Generates audio shorter than a single 30ms analysis frame.
     */
    fun createShortAudio(sampleCount: Int = 100): FloatArray {
        return FloatArray(sampleCount) { 0.1f }
    }

    // =========================================================================
    // NAVIGATION & ADAPTIVE LAYOUT CONTRACTS (R3, R4)
    // =========================================================================

    data class NavTabSpec(
        val id: String,
        val route: String,
        val label: String,
        val iconName: String,
    )

    val EXPECTED_NAVIGATION_TABS = listOf(
        NavTabSpec("home", "home", "Leitura", "VolumeUp"),
        NavTabSpec("transcribe", "transcribe", "Transcrever", "Mic"),
        NavTabSpec("history", "history", "Biblioteca", "MenuBook"),
        NavTabSpec("settings", "settings", "Definições", "Settings"),
    )

    enum class OrientationMode {
        PORTRAIT,
        LANDSCAPE,
    }

    enum class NavigationUiComponent {
        BOTTOM_BAR,   // NavigationBar
        SIDE_BAR,     // NavigationRail
    }

    fun resolveNavigationComponent(orientation: OrientationMode): NavigationUiComponent {
        return when (orientation) {
            OrientationMode.PORTRAIT -> NavigationUiComponent.BOTTOM_BAR
            OrientationMode.LANDSCAPE -> NavigationUiComponent.SIDE_BAR
        }
    }

    // =========================================================================
    // STATE PERSISTENCE SIMULATION CONTRACTS (R4)
    // =========================================================================

    data class AppScreenState(
        val activeRoute: String,
        val readerDraftText: String,
        val readerCharOffset: Int,
        val transcriberText: String,
        val transcriberStatus: String,
        val isRecording: Boolean,
    )

    fun simulateOrientationChange(before: AppScreenState): AppScreenState {
        // Pure state preservation invariant: orientation change must preserve all fields intact
        return before.copy()
    }

    fun createTestUri(uriString: String = "content://com.android.providers.media/audio/12345"): Uri =
        android.net.TestUri(uriString)
}
