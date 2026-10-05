package com.echoreading.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SpeechModelFile(
    val name: String,
    val size: Long,
    val sha256: String,
)

data class SpeechModelOption(
    val id: String,
    val label: String,
    val revision: String,
    val files: List<SpeechModelFile>,
    val recommended: Boolean = false,
) {
    val downloadBytes: Long = files.sumOf { it.size }
    val repository: String = "csukuangfj/sherpa-onnx-whisper-$id"
}

object OfflineSpeech {
    const val DEFAULT_MODEL_ID = "base"

    val models = listOf(
        SpeechModelOption(
            id = "tiny",
            label = "Whisper Tiny",
            revision = "65176e2deb88badc814a94058666cadccc29b61c",
            files = listOf(
                SpeechModelFile("tiny-encoder.int8.onnx", 12_937_772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
                SpeechModelFile("tiny-decoder.int8.onnx", 89_855_401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
                SpeechModelFile("tiny-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
        ),
        SpeechModelOption(
            id = "base",
            label = "Whisper Base",
            revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
            recommended = true,
            files = listOf(
                SpeechModelFile("base-encoder.int8.onnx", 29_120_534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
                SpeechModelFile("base-decoder.int8.onnx", 130_672_026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
                SpeechModelFile("base-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
        ),
        SpeechModelOption(
            id = "small",
            label = "Whisper Small",
            revision = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            files = listOf(
                SpeechModelFile("small-encoder.int8.onnx", 112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
                SpeechModelFile("small-decoder.int8.onnx", 262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
                SpeechModelFile("small-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
        ),
    )

    private val lock = Any()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerModelId: String? = null

    fun option(modelId: String): SpeechModelOption =
        models.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("Modelo de transcrição desconhecido: $modelId")

    private fun modelsDirectory(context: Context) = File(context.noBackupFilesDir, "stt-models")

    fun modelDirectory(context: Context, modelId: String) = File(modelsDirectory(context), modelId)

    private fun marker(context: Context, modelId: String) = File(modelDirectory(context, modelId), ".installed")

    fun isModelInstalled(context: Context, modelId: String): Boolean {
        val model = option(modelId)
        val dir = modelDirectory(context, modelId)
        return marker(context, modelId).isFile && model.files.all { file ->
            File(dir, file.name).let { it.isFile && it.length() == file.size }
        }
    }

    fun isModelInstalled(context: Context): Boolean = selectedModelId(context) != null

    fun installedModels(context: Context): List<SpeechModelOption> =
        models.filter { isModelInstalled(context, it.id) }

    fun selectedModelId(context: Context): String? {
        val stored = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
            .getString("selected_model", null)
        if (stored != null && models.any { it.id == stored } && isModelInstalled(context, stored)) {
            return stored
        }
        return listOf(DEFAULT_MODEL_ID, "tiny", "small").firstOrNull {
            isModelInstalled(context, it)
        }
    }

    fun selectModel(context: Context, modelId: String): Boolean {
        if (!isModelInstalled(context, modelId)) return false
        context.getSharedPreferences("speech", Context.MODE_PRIVATE)
            .edit().putString("selected_model", modelId).apply()
        return true
    }

    fun getModelSizeBytes(context: Context, modelId: String? = null): Long {
        val dir = modelId?.let { modelDirectory(context, it) } ?: modelsDirectory(context)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun deleteModel(context: Context, modelId: String): Boolean = synchronized(lock) {
        if (recognizerModelId == modelId) releaseLocked()
        val deleted = modelDirectory(context, modelId).deleteRecursively()
        val selected = context.getSharedPreferences("speech", Context.MODE_PRIVATE)
            .getString("selected_model", null)
        if (selected == modelId) {
            val fallback = listOf(DEFAULT_MODEL_ID, "tiny", "small").firstOrNull {
                it != modelId && isModelInstalled(context, it)
            }
            context.getSharedPreferences("speech", Context.MODE_PRIVATE).edit().apply {
                if (fallback == null) remove("selected_model") else putString("selected_model", fallback)
            }.apply()
        }
        deleted
    }

    fun deleteModel(context: Context): Boolean {
        release()
        val results = models.map { modelDirectory(context, it.id).deleteRecursively() }
        context.getSharedPreferences("speech", Context.MODE_PRIVATE)
            .edit().remove("selected_model").apply()
        return results.all { it }
    }

    suspend fun installModel(context: Context, onProgress: (Long, Long) -> Unit) =
        installModel(context, DEFAULT_MODEL_ID, onProgress)

    suspend fun installModel(
        context: Context,
        modelId: String,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val model = option(modelId)
        if (isModelInstalled(context, modelId)) {
            onProgress(model.downloadBytes, model.downloadBytes)
            return@withContext
        }

        val dir = modelDirectory(context, modelId).apply { mkdirs() }
        val completedBytes = model.files.sumOf { spec ->
            val target = File(dir, spec.name)
            if (target.isFile && target.length() == spec.size && sha256(target) == spec.sha256) spec.size else 0L
        }
        val remaining = model.downloadBytes - completedBytes
        check(dir.usableSpace > remaining + MIN_FREE_SPACE_BYTES) {
            "Espaço insuficiente para descarregar ${model.label}"
        }

        var completed = 0L
        model.files.forEach { spec ->
            currentCoroutineContext().ensureActive()
            val target = File(dir, spec.name)
            if (target.isFile && target.length() == spec.size && sha256(target) == spec.sha256) {
                completed += spec.size
                onProgress(completed, model.downloadBytes)
                return@forEach
            }
            if (target.exists()) target.delete()
            val url = "https://huggingface.co/${model.repository}/resolve/${model.revision}/${spec.name}"
            downloadFile(url, target, spec) { copied ->
                onProgress((completed + copied).coerceAtMost(model.downloadBytes), model.downloadBytes)
            }
            completed += spec.size
        }

        val markerFile = marker(context, modelId)
        val temporaryMarker = File(dir, ".installed.tmp")
        temporaryMarker.writeText("${model.id}\n${model.revision}\n")
        if (markerFile.exists()) markerFile.delete()
        check(temporaryMarker.renameTo(markerFile)) { "Não foi possível finalizar a instalação do modelo" }
        onProgress(model.downloadBytes, model.downloadBytes)
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        spec: SpeechModelFile,
        onProgress: (Long) -> Unit,
    ) {
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.length() > spec.size) partial.delete()
        var offset = partial.length()
        var connection = openConnection(url, offset)

        if (offset > 0 && connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            partial.delete()
            offset = 0
            connection = openConnection(url, 0)
        }

        val validResponse = connection.responseCode == HttpURLConnection.HTTP_OK ||
            connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!validResponse) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("Falha no download (HTTP $code)")
        }

        try {
            RandomAccessFile(partial, "rw").use { output ->
                output.seek(offset)
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        check(copied <= spec.size) { "O servidor enviou um ficheiro maior do que o esperado" }
                        onProgress(copied)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        check(partial.length() == spec.size) { "Download incompleto de ${spec.name}" }
        check(sha256(partial) == spec.sha256) { "Verificação de integridade falhou para ${spec.name}" }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Não foi possível guardar ${spec.name}" }
    }

    private fun openConnection(url: String, offset: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }

    internal suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun transcribe(
        context: Context,
        modelId: String,
        samples: FloatArray,
        sampleRate: Int = 16_000,
    ): String = synchronized(lock) {
        check(isModelInstalled(context, modelId)) { "Modelo de transcrição não instalado" }
        if (recognizer == null || recognizerModelId != modelId) {
            releaseLocked()
            val model = option(modelId)
            val dir = modelDirectory(context, modelId)
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, model.files[0].name).absolutePath,
                        decoder = File(dir, model.files[1].name).absolutePath,
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = File(dir, model.files[2].name).absolutePath,
                    numThreads = 2,
                    modelType = "whisper",
                )
            )
            recognizer = OfflineRecognizer(config = config)
            recognizerModelId = modelId
        }

        val stt = checkNotNull(recognizer)
        val stream = stt.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            stt.decode(stream)
            stt.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        recognizer?.release()
        recognizer = null
        recognizerModelId = null
    }

    private const val MIN_FREE_SPACE_BYTES = 32L * 1024L * 1024L
}
