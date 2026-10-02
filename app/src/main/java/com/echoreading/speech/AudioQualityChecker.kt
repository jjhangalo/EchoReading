package com.echoreading.speech

import kotlin.math.log10
import kotlin.math.max

data class AudioQualityReport(
    val snrDb: Float,               // Signal-to-Noise Ratio em dB
    val speechRatio: Float,         // Proporção do áudio que contém fala (0.0–1.0)
    val peakAmplitude: Float,       // Amplitude máxima normalizada
    val isSilent: Boolean,          // Áudio quase completamente silencioso
    val isTranscribable: Boolean,   // Veredicto final
    val rejectionReason: String?,   // Razão de rejeição (se aplicável)
)

object AudioQualityChecker {
    private const val MIN_SNR_DB = 5.0f          // SNR mínimo aceitável (tolerando ruído ambiente moderado)
    private const val MIN_SPEECH_RATIO = 0.05f  // Pelo menos 5% do áudio deve conter fala
    private const val SILENCE_THRESHOLD = 0.005f // RMS abaixo disto = silêncio
    private const val MAX_CLIPPING_RATIO = 0.05f // Mais de 5% saturado = distorção/clipping

    fun analyze(samples: FloatArray, sampleRate: Int = 16000): AudioQualityReport {
        if (samples.isEmpty()) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = 0f,
                peakAmplitude = 0f,
                isSilent = true,
                isTranscribable = false,
                rejectionReason = "transcribe_error_silent"
            )
        }

        val frameSize = (sampleRate * 0.03).toInt() // 30ms frames
        if (frameSize == 0 || samples.size < frameSize) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = 0f,
                peakAmplitude = 0f,
                isSilent = true,
                isTranscribable = false,
                rejectionReason = "transcribe_error_silent"
            )
        }

        var peakAmplitude = 0f
        var clippedSamples = 0
        val rmsList = mutableListOf<Float>()

        // Process frames
        for (i in samples.indices step frameSize) {
            val end = minOf(i + frameSize, samples.size)
            var sumSquares = 0f
            for (j in i until end) {
                val sample = samples[j]
                val absSample = if (sample < 0) -sample else sample
                if (absSample > peakAmplitude) {
                    peakAmplitude = absSample
                }
                if (absSample >= 0.99f) {
                    clippedSamples++
                }
                sumSquares += sample * sample
            }
            val rms = kotlin.math.sqrt(sumSquares / (end - i))
            rmsList.add(rms)
        }

        if (peakAmplitude < 0.001f) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = 0f,
                peakAmplitude = peakAmplitude,
                isSilent = true,
                isTranscribable = false,
                rejectionReason = "transcribe_error_silent"
            )
        }

        if (peakAmplitude < 0.01f) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = 0f,
                peakAmplitude = peakAmplitude,
                isSilent = true,
                isTranscribable = false,
                rejectionReason = "transcribe_error_low_volume"
            )
        }

        val clippingRatio = clippedSamples.toFloat() / samples.size
        if (clippingRatio > MAX_CLIPPING_RATIO) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = 0f,
                peakAmplitude = peakAmplitude,
                isSilent = false,
                isTranscribable = false,
                rejectionReason = "transcribe_error_clipped"
            )
        }

        val maxRms = rmsList.maxOrNull() ?: 0f
        val threshold = max(SILENCE_THRESHOLD, maxRms * 0.1f) // VAD threshold

        var speechFrames = 0
        var speechRmsSum = 0f
        var noiseRmsSum = 0f

        for (rms in rmsList) {
            if (rms > threshold) {
                speechFrames++
                speechRmsSum += rms * rms
            } else {
                noiseRmsSum += rms * rms
            }
        }

        val noiseFrames = rmsList.size - speechFrames
        val speechRatio = speechFrames.toFloat() / rmsList.size

        if (speechRatio < MIN_SPEECH_RATIO) {
            return AudioQualityReport(
                snrDb = 0f,
                speechRatio = speechRatio,
                peakAmplitude = peakAmplitude,
                isSilent = false,
                isTranscribable = false,
                rejectionReason = "transcribe_error_no_speech"
            )
        }

        val speechPower = if (speechFrames > 0) speechRmsSum / speechFrames else 0f
        val noisePower = if (noiseFrames > 0) noiseRmsSum / noiseFrames else 1e-10f

        val snrDb = if (noisePower > 0) 10f * log10(speechPower / noisePower) else 100f

        if (snrDb < MIN_SNR_DB) {
            return AudioQualityReport(
                snrDb = snrDb,
                speechRatio = speechRatio,
                peakAmplitude = peakAmplitude,
                isSilent = false,
                isTranscribable = false,
                rejectionReason = "transcribe_error_noisy"
            )
        }

        return AudioQualityReport(
            snrDb = snrDb,
            speechRatio = speechRatio,
            peakAmplitude = peakAmplitude,
            isSilent = false,
            isTranscribable = true,
            rejectionReason = null
        )
    }
}
