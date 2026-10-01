package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "",    // "pt" para Português, "" para auto-detect
    var task: String = "transcribe",
    var tailPaddings: Int = -1,
)

data class OfflineModelConfig(
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 2,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
)

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var decodingMethod: String = "greedy_search",
)

class OfflineRecognizerResult(val text: String, val tokens: Array<String>, val timestamps: FloatArray)

class OfflineStream(private var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) = acceptWaveformImpl(ptr, samples, sampleRate)

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    private external fun acceptWaveformImpl(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun delete(ptr: Long)
}

class OfflineRecognizer(config: OfflineRecognizerConfig) {
    private var ptr: Long = newFromFile(config)

    fun createStream(): OfflineStream {
        val streamPtr = createStreamImpl(ptr)
        return OfflineStream(streamPtr)
    }

    fun decode(stream: OfflineStream) {
        decodeImpl(ptr, getStreamPtr(stream))
    }

    fun getResult(stream: OfflineStream): OfflineRecognizerResult {
        return getResultImpl(ptr, getStreamPtr(stream))
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    private fun getStreamPtr(stream: OfflineStream): Long {
        val field = stream.javaClass.getDeclaredField("ptr")
        field.isAccessible = true
        return field.getLong(stream)
    }

    private external fun newFromFile(config: OfflineRecognizerConfig): Long
    private external fun createStreamImpl(ptr: Long): Long
    private external fun decodeImpl(ptr: Long, streamPtr: Long)
    private external fun getResultImpl(ptr: Long, streamPtr: Long): OfflineRecognizerResult
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
