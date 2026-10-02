package com.echoreading.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object OfflineSpeech {
    private const val WHISPER_ENCODER_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx"
    private const val WHISPER_DECODER_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx"
    private const val WHISPER_TOKENS_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt"

    private const val MODEL_DIR_NAME = "stt-models/whisper-small-int8"

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null

    private fun modelDirectory(context: Context) =
        File(context.noBackupFilesDir, MODEL_DIR_NAME)

    fun isModelInstalled(context: Context): Boolean {
        val dir = modelDirectory(context)
        val encoder = File(dir, "small-encoder.int8.onnx")
        val decoder = File(dir, "small-decoder.int8.onnx")
        val tokens = File(dir, "small-tokens.txt")
        return encoder.isFile && decoder.isFile && tokens.isFile
    }

    fun getModelSizeBytes(context: Context): Long {
        val dir = modelDirectory(context)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteModel(context: Context): Boolean = synchronized(lock) {
        release()
        val dir = modelDirectory(context)
        dir.deleteRecursively()
    }

    suspend fun installModel(context: Context, onProgress: (Long, Long) -> Unit) {
        if (isModelInstalled(context)) return

        withContext(Dispatchers.IO) {
            val dir = modelDirectory(context).apply { mkdirs() }

            val files = listOf(
                WHISPER_ENCODER_URL to File(dir, "small-encoder.int8.onnx"),
                WHISPER_DECODER_URL to File(dir, "small-decoder.int8.onnx"),
                WHISPER_TOKENS_URL to File(dir, "small-tokens.txt")
            )

            // Approximate sizes for progress
            val totalSize = 150_000_000L
            var downloaded = 0L

            for ((url, target) in files) {
                downloadFile(
                    url = url,
                    target = target,
                    onProgress = { current, expected ->
                        // Just an approximation for overall progress
                        onProgress(downloaded + current, totalSize)
                    }
                )
                downloaded += target.length()
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.exists()) partial.delete()

        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirects = 0

        while (redirects < 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                responseCode == 307 || responseCode == 308
            ) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty()) throw java.io.IOException("Redirect without location")
                currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                redirects++
                continue
            }
            connection = conn
            break
        }

        val conn = connection ?: throw java.io.IOException("Too many redirects: $url")

        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "Download failed with HTTP ${conn.responseCode}" }
            val expectedSize = conn.contentLengthLong
            var copied = 0L
            conn.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, expectedSize)
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            check(target.exists()) { "Failed to save downloaded file to target" }
        } finally {
            conn.disconnect()
            if (partial.exists()) partial.delete()
        }
    }

    fun transcribe(context: Context, samples: FloatArray, sampleRate: Int = 16000): String = synchronized(lock) {
        if (!isModelInstalled(context)) {
            throw IllegalStateException("Model not installed")
        }

        if (recognizer == null) {
            val dir = modelDirectory(context)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "small-encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "small-decoder.int8.onnx").absolutePath,
                        language = "", // auto-detect
                    ),
                    tokens = File(dir, "small-tokens.txt").absolutePath,
                    numThreads = 2,
                    modelType = "whisper",
                )
            )
            recognizer = OfflineRecognizer(config)
        }

        val stt = checkNotNull(recognizer)
        val stream = stt.createStream()
        
        try {
            stream.acceptWaveform(samples, sampleRate)
            stt.decode(stream)
            val result = stt.getResult(stream)
            return result.text
        } finally {
            stream.release()
        }
    }

    fun release() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
    }
}
