package com.echoreading

import android.content.Context
import android.content.ContextWrapper
import com.echoreading.voice.CatalogVoice
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.OnnxMetadata
import com.echoreading.voice.VoiceOption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Adversarial Stress & Verification Test Suite
 *
 * Challenges:
 * 1. Retroactive model repair idempotency & zero file-size inflation over repeated invocations.
 * 2. Strict file size checks and installation state consistency across both
 *    OfflineVoice.kt (OfflineVoice.isInstalled) and VoiceDiscoveryScreen.kt (isVoiceInstalled).
 * 3. Graceful fallback, corruption handling, and binary payload preservation under adversarial inputs.
 */
class AdversarialModelRepairTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createSyntheticModel(
        file: File,
        irVersion: Long = 8,
        producer: String = "piper",
        graphSize: Int = 1024
    ): File {
        val out = ByteArrayOutputStream()
        // ir_version (field 1, wire 0)
        out.write(0x08)
        out.write(irVersion.toInt())

        // producer_name (field 2, wire 2)
        val prodBytes = producer.toByteArray(Charsets.UTF_8)
        out.write(0x12)
        out.write(prodBytes.size)
        out.write(prodBytes)

        // graph (field 7, wire 2)
        val graphPayload = ByteArray(graphSize) { (it xor 0x5A).toByte() }
        out.write(0x3A)
        // Varint write for graphSize
        var v = graphSize.toLong()
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
        out.write(graphPayload)

        file.writeBytes(out.toByteArray())
        return file
    }

    private fun createFakeContext(baseDir: File): Context {
        return object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = baseDir
        }
    }

    // =========================================================================
    // SECTION 1: Idempotency & Zero File-Size Inflation Stress Tests
    // =========================================================================

    @Test
    fun testRepairModelFile_100Iterations_zeroInflation() {
        val file = tempFolder.newFile("stress_idempotency.onnx")
        createSyntheticModel(file, graphSize = 4096)
        val initialSize = file.length()
        assertFalse("Model initially lacks sample_rate", OnnxMetadata.hasSampleRate(file))

        // First repair pass
        val firstResult = OnnxMetadata.repairModelFile(
            onnxFile = file,
            fallbackSampleRate = 22050,
            fallbackLanguage = "pt_BR"
        )
        assertTrue("First repair must succeed", firstResult)
        assertTrue("Model must now have sample_rate", OnnxMetadata.hasSampleRate(file))

        val sizeAfterFirstRepair = file.length()
        val hashAfterFirstRepair = sha256(file)
        assertTrue("First repair should have appended metadata", sizeAfterFirstRepair > initialSize)

        // Stress: 99 additional repair calls in a tight loop
        for (i in 2..100) {
            val result = OnnxMetadata.repairModelFile(
                onnxFile = file,
                fallbackSampleRate = 22050,
                fallbackLanguage = "pt_BR"
            )
            assertTrue("Subsequent repair call #$i must return true", result)
            assertEquals(
                "File size must not inflate on iteration #$i",
                sizeAfterFirstRepair,
                file.length()
            )
            assertEquals(
                "File hash must remain byte-for-byte identical on iteration #$i",
                hashAfterFirstRepair,
                sha256(file)
            )
        }
    }

    @Test
    fun testInjectMetadata_repeatedCalls_zeroInflation() {
        val file = tempFolder.newFile("inject_stress.onnx")
        createSyntheticModel(file)

        val metadata = mapOf(
            "sample_rate" to "16000",
            "model_type" to "vits",
            "comment" to "piper",
            "has_espeak" to "1"
        )

        // 1st injection
        assertTrue(OnnxMetadata.injectMetadata(file, metadata))
        val size1 = file.length()
        val hash1 = sha256(file)

        // Repeat 50 times with same map
        for (i in 2..50) {
            assertTrue(OnnxMetadata.injectMetadata(file, metadata))
            assertEquals("Length must remain constant on injection #$i", size1, file.length())
            assertEquals("Hash must remain constant on injection #$i", hash1, sha256(file))
        }

        // Inject a subset of already present keys
        val subset = mapOf("sample_rate" to "16000")
        assertTrue(OnnxMetadata.injectMetadata(file, subset))
        assertEquals("Length must remain constant on subset injection", size1, file.length())
        assertEquals("Hash must remain constant on subset injection", hash1, sha256(file))
    }

    @Test
    fun testRepairAllInstalledVoices_repeatedScans_zeroInflation() {
        val baseDir = tempFolder.newFolder("voice_root")
        val voicesDir = File(baseDir, "voices").apply { mkdirs() }
        val context = createFakeContext(baseDir)

        // Create 5 synthetic voices on disk lacking sample_rate
        val voiceFiles = (1..5).map { idx ->
            val vDir = File(voicesDir, "voice_$idx").apply { mkdirs() }
            val onnx = File(vDir, "voice_$idx.onnx")
            createSyntheticModel(onnx, graphSize = 2048)
            val metaJson = File(vDir, "meta.json")
            metaJson.writeText(
                JSONObject().apply {
                    put("id", "voice_$idx")
                    put("label", "Voice $idx")
                    put("modelFile", "voice_$idx.onnx")
                    put("sampleRate", 22050)
                    put("url", "https://example.com/v$idx.onnx")
                    put("fileSize", onnx.length())
                    put("languageCode", "pt_PT")
                }.toString(2)
            )
            onnx
        }

        // Initial scan: all 5 must be repaired
        val initialRepaired = OnnxMetadata.repairAllInstalledVoices(context)
        assertEquals("First scan must repair all 5 voices", 5, initialRepaired)

        // Record post-repair lengths and hashes
        val postRepairSizes = voiceFiles.map { it.length() }
        val postRepairHashes = voiceFiles.map { sha256(it) }

        // Stress: Run repairAllInstalledVoices 20 times repeatedly
        for (pass in 1..20) {
            val count = OnnxMetadata.repairAllInstalledVoices(context)
            assertEquals("Subsequent scan pass #$pass must repair 0 voices", 0, count)
            for (idx in voiceFiles.indices) {
                assertEquals(
                    "Model $idx size must not inflate on pass #$pass",
                    postRepairSizes[idx],
                    voiceFiles[idx].length()
                )
                assertEquals(
                    "Model $idx hash must not change on pass #$pass",
                    postRepairHashes[idx],
                    sha256(voiceFiles[idx])
                )
            }
        }
    }

    @Test
    fun testBundledModel_repairIsNoOp() {
        val candidates = listOf(
            File("src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx"),
            File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx"),
            File("../app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx")
        )
        val bundled = candidates.firstOrNull { it.isFile }
        assumeTrue("Bundled TTS model is provisioned outside Git", bundled != null)
        val original = checkNotNull(bundled)

        // Copy to temp file so we can safely test repair
        val testCopy = tempFolder.newFile("bundled_copy.onnx")
        testCopy.writeBytes(original.readBytes())
        val originalLength = testCopy.length()
        val originalHash = sha256(testCopy)

        assertTrue(OnnxMetadata.hasSampleRate(testCopy))

        // Running repair on a model that already has sample_rate must be a strict no-op
        for (i in 1..10) {
            assertTrue(OnnxMetadata.repairModelFile(testCopy))
            assertEquals(originalLength, testCopy.length())
            assertEquals(originalHash, sha256(testCopy))
        }
    }

    // =========================================================================
    // SECTION 2: Cross-Component Installation State Consistency
    //            (OfflineVoice.kt vs VoiceDiscoveryScreen.kt)
    // =========================================================================

    @Test
    fun testInstallationStateConsistency_unpatchedModelMatchesExpectedSize() {
        val baseDir = tempFolder.newFolder("unpatched_state")
        val context = createFakeContext(baseDir)
        val voiceKey = "es_ES-davefx-medium"
        val modelFileName = "es_ES-davefx-medium.onnx"

        val vDir = File(baseDir, "voices/$voiceKey").apply { mkdirs() }
        File(vDir, modelFileName).apply {
            writeBytes(ByteArray(1000))
        }

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "davefx",
            languageCode = "es_ES",
            languageFamily = "es",
            languageRegion = "ES",
            languageNative = "Español",
            languageEnglish = "Spanish",
            countryEnglish = "Spain",
            quality = "medium",
            numSpeakers = 1,
            onnxFilePath = "es/es_ES/davefx/medium/$modelFileName",
            onnxSizeBytes = 1000L,
            onnxMd5 = "hash",
            configFilePath = "es/es_ES/davefx/medium/$modelFileName.json",
            configSizeBytes = 2000L
        )

        val voiceOption = VoiceOption(
            id = voiceKey,
            label = "Spanish Dave",
            modelFile = modelFileName,
            sampleRate = 22050,
            url = "https://example.com/$modelFileName",
            fileSize = 1000L,
            languageCode = "es_ES",
            isDiscovery = true
        )

        // Both components must report installed == true
        assertTrue(
            "OfflineVoice.isInstalled must be true for exact unpatched size",
            OfflineVoice.isInstalled(context, voiceOption)
        )
        assertTrue(
            "VoiceDiscoveryScreen.isVoiceInstalled must be true for exact unpatched size",
            isVoiceInstalled(context, catalogVoice)
        )
    }

    @Test
    fun testInstallationStateConsistency_afterMetadataPatching() {
        val baseDir = tempFolder.newFolder("patched_state")
        val context = createFakeContext(baseDir)
        val voiceKey = "it_IT-riccardo-x_low"
        val modelFileName = "it_IT-riccardo-x_low.onnx"
        val originalSize = 5000L

        val vDir = File(baseDir, "voices/$voiceKey").apply { mkdirs() }
        val modelFile = File(vDir, modelFileName)
        createSyntheticModel(modelFile, graphSize = originalSize.toInt())
        val actualBaseSize = modelFile.length()

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "riccardo",
            languageCode = "it_IT",
            languageFamily = "it",
            languageRegion = "IT",
            languageNative = "Italiano",
            languageEnglish = "Italian",
            countryEnglish = "Italy",
            quality = "x_low",
            numSpeakers = 1,
            onnxFilePath = "it/it_IT/riccardo/x_low/$modelFileName",
            onnxSizeBytes = actualBaseSize,
            onnxMd5 = "hash",
            configFilePath = "it/it_IT/riccardo/x_low/$modelFileName.json",
            configSizeBytes = 1500L
        )

        val voiceOption = VoiceOption(
            id = voiceKey,
            label = "Italian Riccardo",
            modelFile = modelFileName,
            sampleRate = 22050,
            url = "https://example.com/$modelFileName",
            fileSize = actualBaseSize,
            languageCode = "it_IT",
            isDiscovery = true
        )

        // Patch model with metadata (+ ~200 bytes)
        assertTrue(OnnxMetadata.repairModelFile(modelFile))
        assertTrue("Patched file size must be strictly greater than original catalog size", modelFile.length() > actualBaseSize)

        // Both components must maintain isInstalled == true despite size increase
        assertTrue(
            "OfflineVoice.isInstalled must remain true after metadata patching",
            OfflineVoice.isInstalled(context, voiceOption)
        )
        assertTrue(
            "VoiceDiscoveryScreen.isVoiceInstalled must remain true after metadata patching",
            isVoiceInstalled(context, catalogVoice)
        )
    }

    @Test
    fun testInstallationStateConsistency_metaJsonUpdatedWithPatchedSize() {
        val baseDir = tempFolder.newFolder("meta_json_updated")
        val context = createFakeContext(baseDir)
        val voiceKey = "de_DE-eva-k"
        val modelFileName = "de_DE-eva-k.onnx"

        val vDir = File(baseDir, "voices/$voiceKey").apply { mkdirs() }
        val modelFile = File(vDir, modelFileName)
        createSyntheticModel(modelFile, graphSize = 2000)
        val rawSize = modelFile.length()

        // Patch model
        OnnxMetadata.repairModelFile(modelFile, fallbackSampleRate = 22050, fallbackLanguage = "de_DE")
        val patchedSize = modelFile.length()

        // Write meta.json using patched size
        val metaFile = File(vDir, "meta.json")
        metaFile.writeText(
            JSONObject().apply {
                put("id", voiceKey)
                put("label", "German Eva")
                put("modelFile", modelFileName)
                put("sampleRate", 22050)
                put("url", "https://example.com/$modelFileName")
                put("fileSize", patchedSize)
                put("languageCode", "de_DE")
            }.toString(2)
        )
        OfflineVoice.invalidateDiscoveryCache()

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "eva",
            languageCode = "de_DE",
            languageFamily = "de",
            languageRegion = "DE",
            languageNative = "Deutsch",
            languageEnglish = "German",
            countryEnglish = "Germany",
            quality = "low",
            numSpeakers = 1,
            onnxFilePath = "de/de_DE/eva/low/$modelFileName",
            onnxSizeBytes = rawSize,
            onnxMd5 = "hash",
            configFilePath = "de/de_DE/eva/low/$modelFileName.json",
            configSizeBytes = 1000L
        )

        // Retrieve discovered voice option from OfflineVoice.discoveryVoices
        val discovered = OfflineVoice.discoveryVoices(context).firstOrNull { it.id == voiceKey }
        assertNotNull("Discovery voice must be loaded from meta.json", discovered)
        val option = checkNotNull(discovered)

        assertEquals("Option fileSize must reflect patched size", patchedSize, option.fileSize)
        assertTrue(
            "OfflineVoice.isInstalled must be true when fileSize equals patched size",
            OfflineVoice.isInstalled(context, option)
        )
        assertTrue(
            "VoiceDiscoveryScreen.isVoiceInstalled must be true when meta.json has patched size",
            isVoiceInstalled(context, catalogVoice)
        )
    }

    @Test
    fun testInstallationStateConsistency_partialDownload_rejected() {
        val baseDir = tempFolder.newFolder("partial_state")
        val context = createFakeContext(baseDir)
        val voiceKey = "fr_FR-siwis-medium"
        val modelFileName = "fr_FR-siwis-medium.onnx"
        val expectedSize = 50_000_000L

        val vDir = File(baseDir, "voices/$voiceKey").apply { mkdirs() }
        // Truncated file (e.g. only 10 KB downloaded)
        File(vDir, modelFileName).writeBytes(ByteArray(10_000))

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "siwis",
            languageCode = "fr_FR",
            languageFamily = "fr",
            languageRegion = "FR",
            languageNative = "Français",
            languageEnglish = "French",
            countryEnglish = "France",
            quality = "medium",
            numSpeakers = 1,
            onnxFilePath = "fr/fr_FR/siwis/medium/$modelFileName",
            onnxSizeBytes = expectedSize,
            onnxMd5 = "hash",
            configFilePath = "fr/fr_FR/siwis/medium/$modelFileName.json",
            configSizeBytes = 2000L
        )

        val voiceOption = VoiceOption(
            id = voiceKey,
            label = "French Siwis",
            modelFile = modelFileName,
            sampleRate = 22050,
            url = "https://example.com/$modelFileName",
            fileSize = expectedSize,
            languageCode = "fr_FR",
            isDiscovery = true
        )

        // Both components MUST reject incomplete downloads
        assertFalse(
            "OfflineVoice.isInstalled must be false for partial download",
            OfflineVoice.isInstalled(context, voiceOption)
        )
        assertFalse(
            "VoiceDiscoveryScreen.isVoiceInstalled must be false for partial download",
            isVoiceInstalled(context, catalogVoice)
        )
    }

    @Test
    fun testInstallationStateConsistency_zeroByteFile_rejected() {
        val baseDir = tempFolder.newFolder("zero_byte_state")
        val context = createFakeContext(baseDir)
        val voiceKey = "en_GB-alan-low"
        val modelFileName = "en_GB-alan-low.onnx"

        val vDir = File(baseDir, "voices/$voiceKey").apply { mkdirs() }
        File(vDir, modelFileName).createNewFile() // 0-byte file

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "alan",
            languageCode = "en_GB",
            languageFamily = "en",
            languageRegion = "GB",
            languageNative = "English",
            languageEnglish = "English",
            countryEnglish = "United Kingdom",
            quality = "low",
            numSpeakers = 1,
            onnxFilePath = "en/en_GB/alan/low/$modelFileName",
            onnxSizeBytes = 40_000_000L,
            onnxMd5 = "hash",
            configFilePath = "en/en_GB/alan/low/$modelFileName.json",
            configSizeBytes = 1000L
        )

        val voiceOption = VoiceOption(
            id = voiceKey,
            label = "Alan",
            modelFile = modelFileName,
            sampleRate = 22050,
            url = "https://example.com/$modelFileName",
            fileSize = 40_000_000L,
            languageCode = "en_GB",
            isDiscovery = true
        )

        assertFalse(OfflineVoice.isInstalled(context, voiceOption))
        assertFalse(isVoiceInstalled(context, catalogVoice))
    }

    @Test
    fun testInstallationStateConsistency_missingFile_rejected() {
        val baseDir = tempFolder.newFolder("missing_state")
        val context = createFakeContext(baseDir)
        val voiceKey = "pt_BR-edresson-low"
        val modelFileName = "pt_BR-edresson-low.onnx"

        val catalogVoice = CatalogVoice(
            key = voiceKey,
            name = "edresson",
            languageCode = "pt_BR",
            languageFamily = "pt",
            languageRegion = "BR",
            languageNative = "Português",
            languageEnglish = "Portuguese",
            countryEnglish = "Brazil",
            quality = "low",
            numSpeakers = 1,
            onnxFilePath = "pt/pt_BR/edresson/low/$modelFileName",
            onnxSizeBytes = 63_104_660L,
            onnxMd5 = "hash",
            configFilePath = "pt/pt_BR/edresson/low/$modelFileName.json",
            configSizeBytes = 1000L
        )

        val voiceOption = OfflineVoice.option("pt-BR")

        assertFalse(OfflineVoice.isInstalled(context, voiceOption))
        assertFalse(isVoiceInstalled(context, catalogVoice))
    }

    @Test
    fun testInstallationStateConsistency_crossDirectoryCatalogVsHardcoded() {
        val baseDir = tempFolder.newFolder("cross_dir_state")
        val context = createFakeContext(baseDir)
        OfflineVoice.invalidateDiscoveryCache()

        val hardcodedOption = OfflineVoice.option("pt-BR") // points to voices/pt-BR/ normally, fileSize = 63_104_660L
        val catalogVoice = CatalogVoice(
            key = "pt_BR-edresson-low",
            name = "edresson",
            languageCode = "pt_BR",
            languageFamily = "pt",
            languageRegion = "BR",
            languageNative = "Português",
            languageEnglish = "Portuguese",
            countryEnglish = "Brazil",
            quality = "low",
            numSpeakers = 1,
            onnxFilePath = "pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx",
            onnxSizeBytes = hardcodedOption.fileSize,
            onnxMd5 = "hash",
            configFilePath = "pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx.json",
            configSizeBytes = 1000L
        )

        // 1. Voice is installed in catalog directory (voices/pt_BR-edresson-low/pt_BR-edresson-low.onnx)
        val catalogDir = File(baseDir, "voices/pt_BR-edresson-low").apply { mkdirs() }
        val catalogModel = File(catalogDir, "pt_BR-edresson-low.onnx")
        createSyntheticModel(catalogModel, graphSize = 2000)
        java.io.RandomAccessFile(catalogModel, "rw").use { it.setLength(hardcodedOption.fileSize + 500L) }
        OnnxMetadata.repairModelFile(catalogModel)

        // OfflineVoice must detect model even if located in catalog directory
        assertTrue(
            "OfflineVoice.isInstalled must find model in catalog folder",
            OfflineVoice.isInstalled(context, hardcodedOption)
        )
        assertTrue(
            "VoiceDiscoveryScreen.isVoiceInstalled must find model in catalog folder",
            isVoiceInstalled(context, catalogVoice)
        )

        // 2. Reverse: Voice is installed in hardcoded directory (voices/pt-BR/pt_BR-edresson-low.onnx)
        catalogDir.deleteRecursively()
        val hardcodedDir = File(baseDir, "voices/pt-BR").apply { mkdirs() }
        val hardcodedModel = File(hardcodedDir, "pt_BR-edresson-low.onnx")
        createSyntheticModel(hardcodedModel, graphSize = 2000)
        java.io.RandomAccessFile(hardcodedModel, "rw").use { it.setLength(hardcodedOption.fileSize + 500L) }
        OnnxMetadata.repairModelFile(hardcodedModel)

        assertTrue(
            "OfflineVoice.isInstalled must find model in hardcoded folder",
            OfflineVoice.isInstalled(context, hardcodedOption)
        )
        assertTrue(
            "VoiceDiscoveryScreen.isVoiceInstalled must find model installed under hardcoded folder",
            isVoiceInstalled(context, catalogVoice)
        )
    }

    // =========================================================================
    // SECTION 3: Retroactive Repair Edge Cases & Robustness
    // =========================================================================

    @Test
    fun testRetroactiveRepair_customSampleRateInOnnxJson() {
        val dir = tempFolder.newFolder("custom_sr")
        val onnx = File(dir, "model.onnx")
        createSyntheticModel(onnx)
        val json = File(dir, "model.onnx.json").apply {
            writeText(
                """
                {
                    "audio": { "sample_rate": 48000 },
                    "num_speakers": 2,
                    "language": { "name_english": "CustomLang", "code": "cl_XX" },
                    "espeak": { "voice": "cl" }
                }
                """.trimIndent()
            )
        }

        assertTrue(OnnxMetadata.repairModelFile(onnx, json))
        val meta = OnnxMetadata.readMetadata(onnx)
        assertEquals("48000", meta["sample_rate"])
        assertEquals("CustomLang", meta["language"])
        assertEquals("2", meta["n_speakers"])
        assertEquals("cl", meta["voice"])
    }

    @Test
    fun testRetroactiveRepair_corruptedCompanionJson_fallsBackToDefaults() {
        val dir = tempFolder.newFolder("corrupt_json")
        val onnx = File(dir, "model.onnx")
        createSyntheticModel(onnx)
        val json = File(dir, "model.onnx.json").apply {
            writeText("{{{NOT VALID JSON@@!!")
        }

        // Must not throw an unhandled exception, must fallback gracefully
        val repaired = OnnxMetadata.repairModelFile(
            onnxFile = onnx,
            configFile = json,
            fallbackSampleRate = 16000,
            fallbackLanguage = "it_IT"
        )
        assertTrue("Repair must succeed even with malformed companion JSON", repaired)
        val meta = OnnxMetadata.readMetadata(onnx)
        assertEquals("16000", meta["sample_rate"])
        assertEquals("it_IT", meta["language"])
        assertEquals("vits", meta["model_type"])
    }

    @Test
    fun testRetroactiveRepair_metaJsonFallbackWhenNoOnnxJson() {
        val dir = tempFolder.newFolder("meta_json_only")
        val onnx = File(dir, "model.onnx")
        createSyntheticModel(onnx)
        File(dir, "meta.json").writeText(
            """
            {
                "id": "meta-voice",
                "label": "Meta Voice",
                "modelFile": "model.onnx",
                "sampleRate": 24000,
                "languageCode": "ja_JP"
            }
            """.trimIndent()
        )

        assertTrue(OnnxMetadata.repairModelFile(onnx))
        val meta = OnnxMetadata.readMetadata(onnx)
        assertEquals("24000", meta["sample_rate"])
        assertEquals("ja_JP", meta["language"])
    }

    @Test
    fun testRetroactiveRepair_preservesOriginalGraphPayloadVerbatim() {
        val file = tempFolder.newFile("weights_preservation.onnx")
        createSyntheticModel(file, graphSize = 8192)

        // Read original raw bytes
        val originalBytes = file.readBytes()
        val originalDigest = MessageDigest.getInstance("SHA-256").digest(originalBytes)

        assertTrue(OnnxMetadata.repairModelFile(file, fallbackSampleRate = 22050))

        val repairedBytes = file.readBytes()
        assertTrue(repairedBytes.size > originalBytes.size)

        // The prefix of the repaired file must match the original bytes byte-for-byte
        val prefix = repairedBytes.copyOfRange(0, originalBytes.size)
        val prefixDigest = MessageDigest.getInstance("SHA-256").digest(prefix)
        assertTrue(
            "Original graph payload must remain byte-for-byte identical",
            originalDigest.contentEquals(prefixDigest)
        )
    }

    @Test
    fun testOnnxMetadata_corruptProtobufHandling() {
        // Truncated varint header
        val truncatedFile = tempFolder.newFile("truncated.onnx").apply {
            writeBytes(byteArrayOf(0x80.toByte(), 0x80.toByte())) // Incomplete varint continuation
        }
        assertFalse(OnnxMetadata.hasSampleRate(truncatedFile))
        assertTrue(OnnxMetadata.readMetadata(truncatedFile).isEmpty())

        // Random binary noise
        val noiseFile = tempFolder.newFile("noise.onnx").apply {
            writeBytes(ByteArray(256) { (it * 37 % 256).toByte() })
        }
        assertFalse(OnnxMetadata.hasSampleRate(noiseFile))
        // Should not crash
        val meta = OnnxMetadata.readMetadata(noiseFile)
        assertNotNull(meta)

        // Huge corrupted length (safeguard check > 65536)
        val hugeLengthDelimited = tempFolder.newFile("huge_len.onnx").apply {
            val out = ByteArrayOutputStream()
            out.write(0x72) // tag 14
            // write varint length 1,000,000
            var v = 1_000_000L
            while ((v and 0x7FL.inv()) != 0L) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write((v and 0x7F).toInt())
            out.write(ByteArray(100) { 0x00 })
            writeBytes(out.toByteArray())
        }
        assertFalse(OnnxMetadata.hasSampleRate(hugeLengthDelimited))
    }

    @Test
    fun testConcurrentRepairAndReadStress() {
        val file = tempFolder.newFile("concurrent_stress.onnx")
        createSyntheticModel(file, graphSize = 4096)
        OnnxMetadata.repairModelFile(file, fallbackSampleRate = 22050)

        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..64).map {
                Callable {
                    val hasSr = OnnxMetadata.hasSampleRate(file)
                    val meta = OnnxMetadata.readMetadata(file)
                    hasSr && meta["sample_rate"] == "22050"
                }
            }
            val results = executor.invokeAll(tasks)
            for (future in results) {
                assertTrue("Concurrent read/check must succeed", future.get())
            }
        } finally {
            executor.shutdown()
        }
    }
}
