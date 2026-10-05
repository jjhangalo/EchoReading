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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class VoiceOption(
    val id: String,
    val label: String,
    val modelFile: String,
    val sampleRate: Int,
    val url: String? = null,
    val sha256: String? = null,
    val fileSize: Long = 0,
    val md5: String? = null,
    val languageCode: String? = null,
    val isDiscovery: Boolean = false,
)

/** One bundled Portuguese voice shared by the reader and Android's TTS service. */
object OfflineVoice {
    private const val ASSET_DIR = "vits-piper-pt_PT-tugao-medium"
    val voices = listOf(
        VoiceOption(
            id = "pt-PT",
            label = "Português (Portugal) · Tugão",
            modelFile = "pt_PT-tugao-medium.onnx",
            sampleRate = 22_050,
            url = "https://huggingface.co/csukuangfj/vits-piper-pt_PT-tugao-medium/resolve/main/pt_PT-tugao-medium.onnx",
            sha256 = "0d922da6f6fd87f981bb05fa8f698a1af6fc5c9366c212cdeb36a0f04c3c056d",
            fileSize = 63_201_425,
            languageCode = "pt_PT",
        ),
        VoiceOption(
            id = "pt-BR",
            label = "Português (Brasil) · Edresson",
            modelFile = "pt_BR-edresson-low.onnx",
            sampleRate = 16_000,
            url = "https://huggingface.co/csukuangfj/vits-piper-pt_BR-edresson-low/resolve/ec462f904a89c8d3b6c16d2f2d24a97eabd33145/pt_BR-edresson-low.onnx",
            sha256 = "912728fca27834ee8184eb9e597b85338105f14bf798906d94d8390d0e81bb62",
            fileSize = 63_104_660,
            languageCode = "pt_BR",
        ),
        VoiceOption(
            id = "en-US",
            label = "English (US) · Amy",
            modelFile = "en_US-amy-low.onnx",
            sampleRate = 16_000,
            url = "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-low/resolve/76da6ca287517a49f58b943c4c4fdb0c1e94d61f/en_US-amy-low.onnx",
            sha256 = "8275b02c37c4ce6483b26a91704807e6de0c3f0bf8068e5d3b3598ab0da4253e",
            fileSize = 63_104_657,
            languageCode = "en_US",
        ),
    )
    // ponytail: one model in memory serializes requests; use separate instances if accessibility latency suffers.
    private val lock = Any()
    private val installMutex = Mutex()
    private var engine: OfflineTts? = null
    private var loadedId: String? = null

    @Volatile
    private var discoveryCache: List<VoiceOption>? = null

    @Synchronized
    fun invalidateDiscoveryCache() {
        discoveryCache = null
    }

    @Synchronized
    fun discoveryVoices(context: Context): List<VoiceOption> {
        discoveryCache?.let { return it }
        val voicesDir = File(context.noBackupFilesDir, "voices")
        if (!voicesDir.isDirectory) return emptyList()
        OnnxMetadata.repairAllInstalledVoices(context)
        val hardcodedIds = voices.map { it.id }.toSet()
        val result = voicesDir.listFiles()?.filter { dir ->
            dir.isDirectory && dir.name !in hardcodedIds &&
                dir.listFiles()?.any { it.name.endsWith(".onnx") && !it.name.endsWith(".onnx.json") } == true
        }?.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.isFile) return@mapNotNull null
            try {
                val meta = org.json.JSONObject(metaFile.readText())
                VoiceOption(
                    id = meta.getString("id"),
                    label = meta.getString("label"),
                    modelFile = meta.getString("modelFile"),
                    sampleRate = meta.getInt("sampleRate"),
                    url = meta.getString("url"),
                    fileSize = meta.getLong("fileSize"),
                    md5 = if (meta.has("md5") && !meta.isNull("md5")) meta.getString("md5") else null,
                    languageCode = if (meta.has("languageCode") && !meta.isNull("languageCode")) meta.getString("languageCode") else null,
                    isDiscovery = true,
                )
            } catch (_: Exception) {
                null
            }
        }.orEmpty()
        discoveryCache = result
        return result
    }

    /** All voices: hardcoded + discovery-installed (deduplicating models that overlap with hardcoded ones). */
    fun allVoices(context: Context): List<VoiceOption> {
        val discovery = discoveryVoices(context)
        val hardcodedModelFiles = voices.map { it.modelFile }.toSet()
        val uniqueDiscovery = discovery.filter { it.modelFile !in hardcodedModelFiles }
        return voices + uniqueDiscovery
    }

    fun option(id: String): VoiceOption = voices.firstOrNull { it.id == id } ?: voices.first()

    fun option(context: Context, id: String): VoiceOption =
        allVoices(context).firstOrNull { it.id == id } ?: voices.first()

    fun isInstalled(context: Context, voice: VoiceOption): Boolean {
        val assetExists = try {
            context.assets?.list(ASSET_DIR)?.contains(voice.modelFile) == true
        } catch (_: Throwable) {
            false
        }
        if (assetExists) return true
        val minSize = if (voice.fileSize > 0) voice.fileSize else 1L
        val directFile = File(modelDirectory(context, voice), voice.modelFile)
        if (directFile.isFile && directFile.length() >= minSize) return true
        // Also check if installed under discovery key or alternate folder with matching model file
        val voicesDir = File(context.noBackupFilesDir, "voices")
        if (voicesDir.isDirectory) {
            val matching = voicesDir.listFiles()?.any { dir ->
                dir.isDirectory && File(dir, voice.modelFile).let { it.isFile && it.length() >= minSize }
            } == true
            if (matching) return true
        }
        return false
    }

    suspend fun install(context: Context, voice: VoiceOption, onProgress: (Long, Long) -> Unit) {
        if (voice.url == null || isInstalled(context, voice)) return
        installMutex.withLock {
            if (isInstalled(context, voice)) return
            withContext(Dispatchers.IO) {
                val directory = modelDirectory(context, voice).apply { mkdirs() }
                val target = File(directory, voice.modelFile)
                downloadFile(
                    url = voice.url,
                    target = target,
                    expectedSize = voice.fileSize,
                    md5Expected = voice.md5,
                    sha256Expected = voice.sha256,
                    onProgress = onProgress,
                )
                OnnxMetadata.repairModelFile(
                    onnxFile = target,
                    fallbackSampleRate = voice.sampleRate,
                    fallbackLanguage = voice.languageCode ?: "",
                )
                ensureEspeakData(context, voice)
                invalidateDiscoveryCache()
            }
        }
    }

    /**
     * Install a voice from the remote catalog.
     * Downloads both the .onnx model and .onnx.json config,
     * extracts sample_rate, and writes a meta.json for future listing.
     */
    suspend fun installFromCatalog(
        context: Context,
        catalogVoice: CatalogVoice,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val voiceDir = File(context.noBackupFilesDir, "voices/${catalogVoice.key}").apply { mkdirs() }
        val modelFile = catalogVoice.onnxFilePath.substringAfterLast('/')

        // 1. Download .onnx.json config first (small, ~4KB)
        val configFileName = catalogVoice.configFilePath.substringAfterLast('/')
        val configTarget = File(voiceDir, configFileName)
        if (!configTarget.isFile) {
            downloadFile(
                url = VoiceCatalog.downloadUrl(catalogVoice.configFilePath),
                target = configTarget,
                expectedSize = catalogVoice.configSizeBytes,
                onProgress = { _, _ -> },
            )
        }

        // 2. Parse sample_rate and phoneme_id_map from config
        val configJson = org.json.JSONObject(configTarget.readText())
        val sampleRate = configJson.optJSONObject("audio")?.optInt("sample_rate", 22050) ?: 22050
        val phonemeIdMap = configJson.optJSONObject("phoneme_id_map")
        if (phonemeIdMap != null) {
            val tokensFile = File(voiceDir, "tokens.txt")
            val tokensBuilder = StringBuilder()
            val keys = phonemeIdMap.keys()
            while (keys.hasNext()) {
                val sym = keys.next()
                val ids = phonemeIdMap.optJSONArray(sym)
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        tokensBuilder.append(sym).append(' ').append(ids.getInt(i)).append('\n')
                    }
                }
            }
            tokensFile.writeText(tokensBuilder.toString())
        }

        // 3. Download .onnx model (large, ~60MB)
        val onnxTarget = File(voiceDir, modelFile)
        if (!onnxTarget.isFile || onnxTarget.length() < catalogVoice.onnxSizeBytes) {
            downloadFile(
                url = VoiceCatalog.downloadUrl(catalogVoice.onnxFilePath),
                target = onnxTarget,
                expectedSize = catalogVoice.onnxSizeBytes,
                md5Expected = catalogVoice.onnxMd5,
                onProgress = onProgress,
            )
        }

        // Inject missing Sherpa-ONNX metadata from companion config
        OnnxMetadata.repairModelFile(
            onnxFile = onnxTarget,
            configFile = configTarget,
            fallbackSampleRate = sampleRate,
            fallbackLanguage = catalogVoice.languageCode,
        )

        // 4. Write meta.json for discovery listing
        val label = buildString {
            val langName = catalogVoice.languageNative.ifEmpty { catalogVoice.languageEnglish }
            append(langName)
            if (catalogVoice.countryEnglish.isNotEmpty()) {
                append(" (${catalogVoice.countryEnglish})")
            }
            append(" · ${catalogVoice.name.replaceFirstChar { it.uppercase() }}")
            append(" (${catalogVoice.quality})")
        }
        val meta = org.json.JSONObject().apply {
            put("id", catalogVoice.key)
            put("label", label)
            put("modelFile", modelFile)
            put("sampleRate", sampleRate)
            put("url", VoiceCatalog.downloadUrl(catalogVoice.onnxFilePath))
            put("md5", catalogVoice.onnxMd5)
            put("fileSize", onnxTarget.length())
            put("languageCode", catalogVoice.languageCode)
        }
        File(voiceDir, "meta.json").writeText(meta.toString(2))
        ensureEspeakData(context, option(context, catalogVoice.key))
        invalidateDiscoveryCache()
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        expectedSize: Long,
        md5Expected: String? = null,
        sha256Expected: String? = null,
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
            val contentLength = conn.contentLengthLong
            val totalExpected = if (contentLength > 0) contentLength else expectedSize
            val digest = when {
                md5Expected != null -> MessageDigest.getInstance("MD5")
                sha256Expected != null -> MessageDigest.getInstance("SHA-256")
                else -> null
            }
            var copied = 0L
            conn.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest?.update(buffer, 0, count)
                        copied += count
                        onProgress(copied, totalExpected)
                    }
                }
            }
            if (digest != null) {
                val checksum = digest.digest().joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                }
                val expected = md5Expected ?: sha256Expected
                check(checksum.equals(expected, ignoreCase = true)) {
                    "Checksum mismatch: $checksum != $expected"
                }
            }
            if (contentLength > 0) {
                check(copied == contentLength) { "Size mismatch: $copied != $contentLength" }
            } else if (expectedSize > 0 && md5Expected == null && sha256Expected == null) {
                check(copied == expectedSize) { "Size mismatch: $copied != $expectedSize" }
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

    fun synthesize(
        context: Context,
        text: String,
        voiceId: String = "pt-PT",
        speed: Float = 1f,
    ): GeneratedAudio = synchronized(lock) {
        var voice = option(context, voiceId)
        if (!isInstalled(context, voice)) {
            val installedVoices = allVoices(context).filter { isInstalled(context, it) }
            val sameLang = installedVoices.firstOrNull {
                val targetLang = voice.languageCode?.take(2) ?: voice.id.take(2)
                val itLang = it.languageCode?.take(2) ?: it.id.take(2)
                targetLang.equals(itLang, ignoreCase = true)
            }
            val installed = sameLang ?: installedVoices.firstOrNull()
            if (installed != null) {
                voice = installed
            }
        }
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

    fun isEspeakDataValid(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        val phontab = File(dir, "phontab")
        val phonindex = File(dir, "phonindex")
        val phondata = File(dir, "phondata")
        return phontab.isFile && phontab.length() > 0 &&
            phonindex.isFile && phonindex.length() > 0 &&
            phondata.isFile && phondata.length() > 0
    }

    fun resolveEspeakDir(dir: File?): File? {
        if (dir == null || !dir.isDirectory) return null
        if (isEspeakDataValid(dir)) return dir
        val nested = File(dir, "espeak-ng-data")
        if (isEspeakDataValid(nested)) return nested
        return null
    }

    fun findInstalledEspeakDir(context: Context, voice: VoiceOption? = null): File? {
        val candidates = mutableListOf<File>()
        candidates.add(File(context.noBackupFilesDir, "voice-tugao-v1/espeak-ng-data"))
        candidates.add(File(context.noBackupFilesDir, "voice-tugao-v1"))
        candidates.add(File(context.noBackupFilesDir, "espeak-ng-data"))
        voice?.let {
            candidates.add(File(modelDirectory(context, it), "espeak-ng-data"))
        }
        val voicesDir = File(context.noBackupFilesDir, "voices")
        if (voicesDir.isDirectory) {
            voicesDir.listFiles()?.filter { it.isDirectory }?.forEach {
                candidates.add(File(it, "espeak-ng-data"))
            }
        }
        candidates.add(File(context.filesDir, "espeak-ng-data"))
        candidates.add(File(context.filesDir, "voice-tugao-v1/espeak-ng-data"))

        for (candidate in candidates) {
            val resolved = resolveEspeakDir(candidate)
            if (resolved != null) return resolved
        }
        return null
    }

    fun prewarmEspeakData(context: Context) {
        try {
            ensureEspeakData(context)
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun ensureEspeakData(context: Context, voice: VoiceOption? = null): File {
        val existing = findInstalledEspeakDir(context, voice)
        if (existing != null) return existing

        val phonemes = File(context.noBackupFilesDir, "voice-tugao-v1/espeak-ng-data")
        val ready = File(phonemes.parentFile, "ready")
        val tempDir = File(phonemes.parentFile, "espeak-ng-data-temp")

        try {
            tempDir.deleteRecursively()
            tempDir.mkdirs()

            // 1. Try directory from assets (e.g. "espeak-ng-data", "$ASSET_DIR/espeak-ng-data", or ASSET_DIR)
            val assetCandidates = listOf(
                "espeak-ng-data",
                "$ASSET_DIR/espeak-ng-data",
                ASSET_DIR,
            )
            for (assetPath in assetCandidates) {
                try {
                    val hasPhontab = try {
                        context.assets?.open("$assetPath/phontab")?.use { true } ?: false
                    } catch (_: Throwable) {
                        false
                    }
                    val hasEntries = if (!hasPhontab) {
                        try {
                            context.assets?.list(assetPath)?.isNotEmpty() == true
                        } catch (_: Throwable) {
                            false
                        }
                    } else false

                    if (hasPhontab || hasEntries) {
                        copyAssetDirectory(context, assetPath, tempDir)
                        if (isEspeakDataValid(tempDir)) break
                    }
                } catch (_: Throwable) {
                }
            }

            // 2. Try zip asset if directory wasn't found or was incomplete
            if (!isEspeakDataValid(tempDir)) {
                val zipCandidates = listOf(
                    "espeak-ng-data.zip",
                    "$ASSET_DIR/espeak-ng-data.zip",
                )
                for (zipName in zipCandidates) {
                    try {
                        context.assets?.open(zipName)?.use { input ->
                            java.util.zip.ZipInputStream(input.buffered()).use { zipIn ->
                                var entry = zipIn.nextEntry
                                while (entry != null) {
                                    val clean = entry.name.removePrefix("espeak-ng-data/").removePrefix("espeak-ng-data\\")
                                    if (clean.isNotEmpty()) {
                                        val outFile = File(tempDir, clean)
                                        if (entry.isDirectory) {
                                            outFile.mkdirs()
                                        } else {
                                            outFile.parentFile?.mkdirs()
                                            outFile.outputStream().buffered().use { out ->
                                                zipIn.copyTo(out)
                                            }
                                        }
                                    }
                                    zipIn.closeEntry()
                                    entry = zipIn.nextEntry
                                }
                            }
                        }
                        if (isEspeakDataValid(tempDir)) break
                    } catch (_: Throwable) {
                    }
                }
            }

            if (isEspeakDataValid(tempDir)) {
                phonemes.deleteRecursively()
                phonemes.parentFile?.mkdirs()
                if (!tempDir.renameTo(phonemes)) {
                    tempDir.copyRecursively(phonemes, overwrite = true)
                    tempDir.deleteRecursively()
                }
                try {
                    ready.parentFile?.mkdirs()
                    ready.writeText("ready")
                } catch (_: Throwable) {
                }
                return phonemes
            }
        } finally {
            if (tempDir.exists()) tempDir.deleteRecursively()
        }

        return resolveEspeakDir(phonemes) ?: phonemes
    }

    private fun open(context: Context, voice: VoiceOption): OfflineTts {
        val phonemes = ensureEspeakData(context, voice)
        val minSize = if (voice.fileSize > 0) voice.fileSize else 1L
        val direct = File(modelDirectory(context, voice), voice.modelFile)
        val hasDirectModel = direct.isFile && direct.length() >= minSize
        val voicesDir = File(context.noBackupFilesDir, "voices")
        val altModel = if (!hasDirectModel && voicesDir.isDirectory) {
            voicesDir.listFiles()?.firstOrNull { dir ->
                dir.isDirectory && File(dir, voice.modelFile).let { it.isFile && it.length() >= minSize }
            }?.let { File(it, voice.modelFile) }
        } else null
        val diskModelFile = if (hasDirectModel) direct else altModel
        val assetExists = try {
            context.assets?.list(ASSET_DIR)?.contains(voice.modelFile) == true
        } catch (_: Throwable) {
            false
        }
        val bundled = assetExists && diskModelFile == null
        val modelPath = if (bundled) {
            "$ASSET_DIR/${voice.modelFile}"
        } else {
            diskModelFile?.absolutePath ?: direct.absolutePath
        }

        if (!bundled) {
            val modelFile = File(modelPath)
            if (modelFile.isFile && !OnnxMetadata.hasSampleRate(modelFile)) {
                OnnxMetadata.repairModelFile(
                    onnxFile = modelFile,
                    fallbackSampleRate = voice.sampleRate,
                    fallbackLanguage = voice.languageCode ?: "",
                )
            }
        }

        val tokensPath = if (bundled) {
            "$ASSET_DIR/tokens.txt"
        } else {
            val voiceDir = File(modelPath).parentFile
            val voiceTokens = voiceDir?.let { File(it, "tokens.txt") }
            if (voiceTokens != null && voiceTokens.isFile) {
                if (voiceTokens.readText().contains("\r")) {
                    voiceTokens.writeText(voiceTokens.readText().replace("\r\n", "\n").replace("\r", "\n"))
                }
                voiceTokens.absolutePath
            } else {
                // Try generating tokens.txt from .onnx.json if present
                val onnxJson = voiceDir?.listFiles()?.firstOrNull { it.name.endsWith(".onnx.json") }
                if (voiceTokens != null && onnxJson != null && onnxJson.isFile) {
                    try {
                        val config = org.json.JSONObject(onnxJson.readText())
                        val pMap = config.optJSONObject("phoneme_id_map")
                        if (pMap != null) {
                            val sb = StringBuilder()
                            val keys = pMap.keys()
                            while (keys.hasNext()) {
                                val sym = keys.next()
                                val ids = pMap.optJSONArray(sym)
                                if (ids != null) {
                                    for (i in 0 until ids.length()) {
                                        sb.append(sym).append(' ').append(ids.getInt(i)).append('\n')
                                    }
                                }
                            }
                            voiceTokens.writeText(sb.toString())
                        }
                    } catch (_: Exception) {}
                }
                if (voiceTokens != null && voiceTokens.isFile) {
                    if (voiceTokens.readText().contains("\r")) {
                        voiceTokens.writeText(voiceTokens.readText().replace("\r\n", "\n").replace("\r", "\n"))
                    }
                    voiceTokens.absolutePath
                } else {
                    File(context.noBackupFilesDir, "voices/tokens.txt").also { tokens ->
                        if (!tokens.isFile || tokens.readText().contains("\r")) {
                            tokens.parentFile?.mkdirs()
                            context.assets.open("optional-piper-tokens.txt").bufferedReader().use { reader ->
                                tokens.writeText(reader.readText().replace("\r\n", "\n").replace("\r", "\n"))
                            }
                        }
                    }.absolutePath
                }
            }
        }

        val hasPhonemes = isEspeakDataValid(phonemes)
        check(hasPhonemes) {
            "eSpeak-NG phoneme data is invalid or missing at ${phonemes.absolutePath}. Required files (phontab, phonindex, phondata) not found."
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath,
                    tokens = tokensPath,
                    dataDir = phonemes.absolutePath,
                ),
                numThreads = 2,
            ),
        )
        return OfflineTts(if (bundled) context.assets else null, config)
    }

    fun copyAssetDirectory(context: Context, assetDir: String, destination: File): Boolean {
        destination.mkdirs()
        val items = try {
            context.assets?.list(assetDir).orEmpty()
        } catch (_: Throwable) {
            emptyArray()
        }
        if (items.isEmpty()) return false

        for (item in items) {
            val itemPath = if (assetDir.isEmpty()) item else "$assetDir/$item"
            val targetFile = File(destination, item)
            var isFile = false
            try {
                context.assets?.open(itemPath)?.use { input ->
                    targetFile.parentFile?.mkdirs()
                    targetFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
                isFile = true
            } catch (_: Throwable) {
                // Not a file or could not open, treat as subdirectory
            }

            if (!isFile) {
                copyAssetDirectory(context, itemPath, targetFile)
            }
        }
        return true
    }
}
