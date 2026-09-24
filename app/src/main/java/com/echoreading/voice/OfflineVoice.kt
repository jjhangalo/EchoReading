package com.echoreading.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VoiceOption(
    val id: String,
    val label: String,
    val modelFile: String,
    val sampleRate: Int,
    val url: String? = null,
    val sha256: String? = null,
    val fileSize: Long = 0,
)

/** One bundled Portuguese voice shared by the reader and Android's TTS service. */
object OfflineVoice {
    private const val ASSET_DIR = "vits-piper-pt_PT-tugao-medium"
    val voices = listOf(
        VoiceOption("pt-PT", "Português (Portugal) · Tugão", "pt_PT-tugao-medium.onnx", 22_050),
        VoiceOption(
            "pt-BR", "Português (Brasil) · Edresson", "pt_BR-edresson-low.onnx", 16_000,
            "https://huggingface.co/csukuangfj/vits-piper-pt_BR-edresson-low/resolve/ec462f904a89c8d3b6c16d2f2d24a97eabd33145/pt_BR-edresson-low.onnx",
            "912728fca27834ee8184eb9e597b85338105f14bf798906d94d8390d0e81bb62",
            63_104_660,
        ),
        VoiceOption(
            "en-US", "English (US) · Amy", "en_US-amy-low.onnx", 16_000,
            "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/76da6ca287517a49f58b943c4c4fdb0c1e94d61f/en_US-amy-low.onnx",
            "8275b02c37c4ce6483b26a91704807e6de0c3f0bf8068e5d3b3598ab0da4253e",
            63_104_657,
        ),
    )
    // ponytail: one model in memory serializes requests; use separate instances if accessibility latency suffers.
    private val lock = Any()
    private var engine: OfflineTts? = null
    private var loadedId: String? = null

    fun option(id: String): VoiceOption = voices.firstOrNull { it.id == id } ?: voices.first()

    fun isInstalled(context: Context, voice: VoiceOption): Boolean =
        voice.url == null || File(modelDirectory(context, voice), voice.modelFile).length() == voice.fileSize

    suspend fun install(context: Context, voice: VoiceOption, onProgress: (Long, Long) -> Unit) {
        if (voice.url == null || isInstalled(context, voice)) return
        withContext(Dispatchers.IO) {
            val directory = modelDirectory(context, voice).apply { mkdirs() }
            val target = File(directory, voice.modelFile)
            val partial = File(directory, "${voice.modelFile}.part")
            val connection = URL(voice.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            onProgress(copied, voice.fileSize)
                        }
                    }
                }
                val checksum = digest.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                check(copied == voice.fileSize && checksum == voice.sha256) {
                    "Voice download integrity check failed"
                }
                check(partial.renameTo(target))
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
    }

    fun synthesize(
        context: Context,
        text: String,
        voiceId: String = "pt-PT",
        speed: Float = 1f,
    ): GeneratedAudio = synchronized(lock) {
        val voice = option(voiceId)
        check(isInstalled(context, voice)) { "Voice not installed: ${voice.id}" }
        if (engine == null || loadedId != voice.id) {
            engine?.release()
            engine = null
            loadedId = null
            engine = open(context.applicationContext, voice)
            loadedId = voice.id
        }
        val tts = checkNotNull(engine)
        tts.generate(text, speed = speed)
    }

    private fun modelDirectory(context: Context, voice: VoiceOption) =
        File(context.noBackupFilesDir, "voices/${voice.id}")

    private fun open(context: Context, voice: VoiceOption): OfflineTts {
        val phonemes = File(context.noBackupFilesDir, "voice-tugao-v1/espeak-ng-data")
        val ready = File(phonemes.parentFile, "ready")
        if (!ready.isFile) {
            phonemes.deleteRecursively()
            copyAssetTree(context, "$ASSET_DIR/espeak-ng-data", phonemes)
            ready.writeText("ready")
        }
        val bundled = voice.url == null
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = if (bundled) "$ASSET_DIR/${voice.modelFile}"
                    else File(modelDirectory(context, voice), voice.modelFile).absolutePath,
                    tokens = if (bundled) "$ASSET_DIR/tokens.txt"
                    else File(context.noBackupFilesDir, "voices/tokens.txt").also { tokens ->
                        if (!tokens.isFile) {
                            tokens.parentFile?.mkdirs()
                            context.assets.open("optional-piper-tokens.txt").use { input ->
                                tokens.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }.absolutePath,
                    dataDir = phonemes.absolutePath,
                ),
                numThreads = 2,
            ),
        )
        return OfflineTts(if (bundled) context.assets else null, config)
    }

    private fun copyAssetTree(context: Context, assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            check(destination.mkdirs() || destination.isDirectory)
            children.forEach { copyAssetTree(context, "$assetPath/$it", File(destination, it)) }
        } else {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
