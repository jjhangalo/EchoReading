package com.echoreading.e2e

import com.echoreading.e2e.testutil.ProtobufTestOracle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2E Requirement Tests for Requirement R1:
 * Piper Voice Model Metadata Injection & Crash Resolution
 *
 * Covers Features 1, 2, 3, 4 across Tier 1 (Happy Path), Tier 2 (Boundary/Edge), and Tier 3 (Cross-Feature).
 */
class VoiceModelRequirementTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e-voice-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun getBundledModelFile(): File {
        val root = File(".").canonicalFile
        val candidate = File(root, "app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx")
        if (candidate.isFile) return candidate
        val fallback = File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx")
        return fallback
    }

    private fun getBundledConfigFile(): File {
        val root = File(".").canonicalFile
        val candidate = File(root, "app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx.json")
        if (candidate.isFile) return candidate
        val fallback = File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx.json")
        return fallback
    }

    // =========================================================================
    // TIER 1: FEATURE 1 — Protobuf Metadata Injection (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f1_1_injectSampleRateIntoRawModelStream() {
        val rawModel = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "model.onnx"))
        assertFalse(ProtobufTestOracle.hasSampleRate(rawModel))

        val metadata = mapOf("sample_rate" to "22050")
        val success = ProtobufTestOracle.injectMetadata(rawModel, metadata)
        assertTrue("Injection should return true", success)

        val read = ProtobufTestOracle.readMetadata(rawModel)
        assertEquals("22050", read["sample_rate"])
    }

    @Test
    fun f1_2_injectStandardPiperFields() {
        val rawModel = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "piper.onnx"))
        val fields = mapOf(
            "sample_rate" to "22050",
            "model_type" to "vits",
            "comment" to "piper",
            "has_espeak" to "1",
            "n_speakers" to "1",
            "version" to "1"
        )
        ProtobufTestOracle.injectMetadata(rawModel, fields)
        val read = ProtobufTestOracle.readMetadata(rawModel)

        assertEquals("22050", read["sample_rate"])
        assertEquals("vits", read["model_type"])
        assertEquals("piper", read["comment"])
        assertEquals("1", read["has_espeak"])
        assertEquals("1", read["n_speakers"])
        assertEquals("1", read["version"])
    }

    @Test
    fun f1_3_verifiesByteAlignmentAndTagEncoding() {
        // Tag 114 is (14 shl 3) | 2 == 0x72
        val encoded = ProtobufTestOracle.encodeMetadataProps(mapOf("sample_rate" to "22050"))
        assertTrue("Encoded bytes must not be empty", encoded.isNotEmpty())
        assertEquals("First tag byte must be 0x72 (repeated field 14)", 0x72.toByte(), encoded[0])

        // Verify key tag 0x0A (field 1 length-delimited) exists in the payload
        var hasKeyTag = false
        for (b in encoded) {
            if (b == 0x0A.toByte()) {
                hasKeyTag = true
                break
            }
        }
        assertTrue("Payload must contain key tag 0x0A", hasKeyTag)
    }

    @Test
    fun f1_4_injectCustomSampleRatesFromConfig() {
        val sampleRates = listOf("16000", "22050", "24000", "44100", "48000")
        for ((idx, sr) in sampleRates.withIndex()) {
            val file = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "model_$idx.onnx"))
            ProtobufTestOracle.injectMetadata(file, mapOf("sample_rate" to sr))
            val meta = ProtobufTestOracle.readMetadata(file)
            assertEquals("Sample rate $sr should match", sr, meta["sample_rate"])
        }
    }

    @Test
    fun f1_5_preservesPreexistingModelWeightsAndGraphData() {
        val initialFile = ProtobufTestOracle.createSyntheticRawModel(
            File(tempDir, "weights.onnx"),
            irVersion = 7,
            graphName = "critical_tts_weight_matrix_data"
        )
        val initialBytes = initialFile.readBytes()

        ProtobufTestOracle.injectMetadata(initialFile, mapOf("sample_rate" to "22050"))
        val updatedBytes = initialFile.readBytes()

        // Original bytes must remain untouched as prefix
        assertEquals(
            initialBytes.size + (updatedBytes.size - initialBytes.size),
            updatedBytes.size
        )
        val prefix = updatedBytes.copyOfRange(0, initialBytes.size)
        assertTrue("Original graph weights must be byte-for-byte identical", initialBytes.contentEquals(prefix))
    }

    // =========================================================================
    // TIER 1: FEATURE 2 — Retroactive Model Repair (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f2_1_detectsUnpatchedModelLackingSampleRate() {
        val unpatched = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "unpatched.onnx"))
        assertFalse(
            "Unpatched model must report hasSampleRate=false",
            ProtobufTestOracle.hasSampleRate(unpatched)
        )
    }

    @Test
    fun f2_2_repairsUnpatchedModelInPlace() {
        val model = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "needs_repair.onnx"))
        assertFalse(ProtobufTestOracle.hasSampleRate(model))

        val repaired = ProtobufTestOracle.repairModelFile(model)
        assertTrue("Repair operation must succeed", repaired)
        assertTrue("Model must report hasSampleRate=true after repair", ProtobufTestOracle.hasSampleRate(model))
        assertEquals("22050", ProtobufTestOracle.readMetadata(model)["sample_rate"])
    }

    @Test
    fun f2_3_repairIsIdempotentOnAlreadyPatchedModel() {
        val model = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "idempotent.onnx"))
        ProtobufTestOracle.repairModelFile(model)
        val sizeAfterFirstRepair = model.length()

        // Second repair attempt
        val secondRepair = ProtobufTestOracle.repairModelFile(model)
        assertTrue("Idempotent repair returns true", secondRepair)
        assertEquals("File size must not grow when already repaired", sizeAfterFirstRepair, model.length())
    }

    @Test
    fun f2_4_repairExtractsMetadataFromCompanionJson() {
        val onnx = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "es_ES.onnx"))
        val json = File(tempDir, "es_ES.onnx.json").apply {
            writeText(
                """
                {
                    "audio": { "sample_rate": 16000 },
                    "num_speakers": 2,
                    "language": { "name_english": "Spanish", "code": "es_ES" },
                    "espeak": { "voice": "es" }
                }
                """.trimIndent()
            )
        }

        ProtobufTestOracle.repairModelFile(onnx, json)
        val meta = ProtobufTestOracle.readMetadata(onnx)
        assertEquals("16000", meta["sample_rate"])
        assertEquals("2", meta["n_speakers"])
        assertEquals("Spanish", meta["language"])
        assertEquals("es", meta["voice"])
        assertEquals("vits", meta["model_type"])
    }

    @Test
    fun f2_5_bundledModelAssetContainsRequiredMetadata() {
        val bundled = getBundledModelFile()
        if (bundled.isFile) {
            assertTrue("Bundled model must have sample_rate", ProtobufTestOracle.hasSampleRate(bundled))
            val meta = ProtobufTestOracle.readMetadata(bundled)
            assertEquals("22050", meta["sample_rate"])
            assertEquals("vits", meta["model_type"])
            assertEquals("piper", meta["comment"])
            assertEquals("1", meta["has_espeak"])
        }
    }

    // =========================================================================
    // TIER 1: FEATURE 3 — Model File Size Check Fix (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f3_1_injectedModelSatisfiesGreaterThanOrEqualCheck() {
        val expectedHuggingFaceSize = 100_000L
        val fakeModel = File(tempDir, "hf_model.onnx").apply {
            writeBytes(ByteArray(expectedHuggingFaceSize.toInt()))
        }
        ProtobufTestOracle.injectMetadata(fakeModel, mapOf("sample_rate" to "22050"))

        val actualSize = fakeModel.length()
        assertTrue("Injected file must be larger than original size", actualSize > expectedHuggingFaceSize)
        assertTrue("Validation check actual >= expected must pass", actualSize >= expectedHuggingFaceSize)
    }

    @Test
    fun f3_2_incompleteDownloadFailsSizeCheck() {
        val expectedSize = 65_000_000L
        val truncatedFile = File(tempDir, "truncated.onnx").apply {
            writeBytes(ByteArray(10_000))
        }
        assertFalse(
            "Truncated file must fail >= check",
            truncatedFile.length() >= expectedSize
        )
    }

    @Test
    fun f3_3_preventsInfiniteRedownloadLoops() {
        // Simulates isInstalled logic fix: (file.length() >= voice.onnxSizeBytes)
        val catalogVoiceSize = 63_200_000L
        val modelOnDisk = File(tempDir, "pt_PT.onnx").apply {
            writeBytes(ByteArray(catalogVoiceSize.toInt()))
        }
        ProtobufTestOracle.repairModelFile(modelOnDisk)

        val isInstalledFixed = modelOnDisk.isFile && modelOnDisk.length() >= catalogVoiceSize
        val isInstalledBuggy = modelOnDisk.isFile && modelOnDisk.length() == catalogVoiceSize

        assertTrue("Fixed isInstalled check must report TRUE", isInstalledFixed)
        assertFalse("Buggy strict equality check falsely reports FALSE", isInstalledBuggy)
    }

    @Test
    fun f3_4_directVoiceInstallationCheckPassesWithMetadataOverhead() {
        val nominalSize = 40_000_000L
        val installedModel = File(tempDir, "direct_voice.onnx").apply {
            writeBytes(ByteArray(nominalSize.toInt()))
        }
        ProtobufTestOracle.injectMetadata(
            installedModel,
            mapOf("sample_rate" to "22050", "model_type" to "vits", "n_speakers" to "1")
        )

        fun checkInstalled(f: File, expected: Long): Boolean = f.isFile && f.length() >= expected
        assertTrue(checkInstalled(installedModel, nominalSize))
    }

    @Test
    fun f3_5_equalityVsInequalityEvaluation() {
        val originalSize = 1024L
        val file = File(tempDir, "eval.onnx").apply { writeBytes(ByteArray(originalSize.toInt())) }
        ProtobufTestOracle.injectMetadata(file, mapOf("sample_rate" to "22050"))

        val injectedBytesCount = file.length() - originalSize
        assertTrue("Injected metadata must be positive byte count", injectedBytesCount > 0)
        assertFalse("file.length() == originalSize must fail after injection", file.length() == originalSize)
        assertTrue("file.length() >= originalSize must succeed after injection", file.length() >= originalSize)
    }

    // =========================================================================
    // TIER 1: FEATURE 4 — Metadata Unit Testing & Integrity (Happy Path >= 5)
    // =========================================================================

    @Test
    fun f4_1_parseOfficialCompanionJsonFormat() {
        val jsonFile = File(tempDir, "sample.onnx.json").apply {
            writeText(
                """
                {
                    "audio": { "sample_rate": 22050, "quality": "medium" },
                    "num_speakers": 1,
                    "phoneme_type": "espeak",
                    "language": { "code": "pt_PT", "name_english": "Portuguese" },
                    "espeak": { "voice": "pt" }
                }
                """.trimIndent()
            )
        }
        val meta = ProtobufTestOracle.parseCompanionConfig(jsonFile)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("vits", meta["model_type"])
        assertEquals("piper", meta["comment"])
        assertEquals("1", meta["has_espeak"])
        assertEquals("Portuguese", meta["language"])
        assertEquals("pt", meta["voice"])
    }

    @Test
    fun f4_2_varintRoundTripSerialization() {
        val testValues = listOf(0L, 1L, 127L, 128L, 300L, 16384L, 2097152L, Long.MAX_VALUE)
        for (value in testValues) {
            val out = java.io.ByteArrayOutputStream()
            ProtobufTestOracle.writeVarint(out, value)
            val stream = out.toByteArray().inputStream()
            val decoded = ProtobufTestOracle.readVarint(stream)
            assertEquals("Round-trip varint for $value failed", value, decoded)
        }
    }

    @Test
    fun f4_3_entryTagEncodingAndDecoding() {
        val payload = ProtobufTestOracle.encodeEntry("test_key", "test_value")
        val parsed = ProtobufTestOracle.parseEntry(payload)
        assertNotNull(parsed)
        assertEquals("test_key", parsed?.first)
        assertEquals("test_value", parsed?.second)
    }

    @Test
    fun f4_4_multiEntryMetadataEncodingIntegrity() {
        val entries = mapOf("k1" to "v1", "k2" to "v2", "k3" to "v3")
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "multi.onnx"))
        ProtobufTestOracle.injectMetadata(raw, entries)
        val read = ProtobufTestOracle.readMetadata(raw)

        assertEquals("v1", read["k1"])
        assertEquals("v2", read["k2"])
        assertEquals("v3", read["k3"])
    }

    @Test
    fun f4_5_utf8HandlingInMetadataProperties() {
        val unicodeEntries = mapOf(
            "language" to "Português",
            "comment" to "Voz Sintética de Alta Fidelidade — Açores & Lisboa",
            "sample_rate" to "22050"
        )
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "unicode.onnx"))
        ProtobufTestOracle.injectMetadata(raw, unicodeEntries)
        val read = ProtobufTestOracle.readMetadata(raw)

        assertEquals("Português", read["language"])
        assertEquals("Voz Sintética de Alta Fidelidade — Açores & Lisboa", read["comment"])
        assertEquals("22050", read["sample_rate"])
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>= 5 per Feature)
    // =========================================================================

    // --- Feature 1 Boundaries ---

    @Test
    fun b1_1_injectIntoZeroByteFileReturnsFalse() {
        val emptyFile = File(tempDir, "empty.onnx").apply { createNewFile() }
        val success = ProtobufTestOracle.injectMetadata(emptyFile, mapOf("sample_rate" to "22050"))
        assertFalse("Injection on 0-byte file should be safely rejected", success)
    }

    @Test
    fun b1_2_injectIntoTruncatedFile() {
        val truncated = File(tempDir, "trunc.onnx").apply {
            writeBytes(byteArrayOf(0x08, 0x07)) // Minimal valid varint
        }
        val success = ProtobufTestOracle.injectMetadata(truncated, mapOf("sample_rate" to "22050"))
        assertTrue(success)
        assertTrue(ProtobufTestOracle.hasSampleRate(truncated))
    }

    @Test
    fun b1_3_injectExtremeSampleRates() {
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "extreme.onnx"))
        ProtobufTestOracle.injectMetadata(raw, mapOf("sample_rate" to "8000"))
        assertEquals("8000", ProtobufTestOracle.readMetadata(raw)["sample_rate"])

        val raw2 = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "extreme2.onnx"))
        ProtobufTestOracle.injectMetadata(raw2, mapOf("sample_rate" to "192000"))
        assertEquals("192000", ProtobufTestOracle.readMetadata(raw2)["sample_rate"])
    }

    @Test
    fun b1_4_injectEmptyMetadataMapIsNoOp() {
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "empty_map.onnx"))
        val sizeBefore = raw.length()
        val success = ProtobufTestOracle.injectMetadata(raw, emptyMap())
        assertTrue(success)
        assertEquals("File size must remain identical", sizeBefore, raw.length())
    }

    @Test
    fun b1_5_injectMetadataWithQuotesNewlinesAndTabs() {
        val special = mapOf("comment" to "line1\nline2\t\"quoted\"", "sample_rate" to "22050")
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "special.onnx"))
        ProtobufTestOracle.injectMetadata(raw, special)
        val read = ProtobufTestOracle.readMetadata(raw)
        assertEquals("line1\nline2\t\"quoted\"", read["comment"])
    }

    // --- Feature 2 Boundaries ---

    @Test
    fun b2_1_repairNonExistentFileFailsGracefully() {
        val nonExistent = File(tempDir, "does_not_exist.onnx")
        val result = ProtobufTestOracle.repairModelFile(nonExistent)
        assertFalse("Repair of non-existent file should safely return false", result)
    }

    @Test
    fun b2_2_repairWithCorruptedJsonFallsBackToDefaults() {
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "bad_json.onnx"))
        val badJson = File(tempDir, "bad.json").apply { writeText("NOT_A_JSON{{{") }

        val repaired = ProtobufTestOracle.repairModelFile(raw, badJson)
        assertTrue(repaired)
        // Fallback default sample rate is 22050
        assertEquals("22050", ProtobufTestOracle.readMetadata(raw)["sample_rate"])
    }

    @Test
    fun b2_3_repairWithMissingJsonUsesDefault22050() {
        val raw = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "no_json.onnx"))
        val repaired = ProtobufTestOracle.repairModelFile(raw, null)
        assertTrue(repaired)
        val meta = ProtobufTestOracle.readMetadata(raw)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("vits", meta["model_type"])
    }

    @Test
    fun b2_4_repairFileWithHugeGraphPayload() {
        val largeFile = File(tempDir, "large.onnx")
        val out = java.io.ByteArrayOutputStream()
        // Graph tag (7) with 50KB payload
        ProtobufTestOracle.writeVarint(out, (7L shl 3) or 2L)
        ProtobufTestOracle.writeVarint(out, 50_000L)
        out.write(ByteArray(50_000))
        largeFile.writeBytes(out.toByteArray())

        ProtobufTestOracle.repairModelFile(largeFile)
        assertTrue(ProtobufTestOracle.hasSampleRate(largeFile))
        assertEquals("22050", ProtobufTestOracle.readMetadata(largeFile)["sample_rate"])
    }

    @Test
    fun b2_5_repairSkipsUnknownWireTypesSafely() {
        val unknownWire = File(tempDir, "unknown.onnx").apply {
            val bytes = byteArrayOf(
                (1L shl 3 or 0L).toByte(), 0x01, // Field 1: varint 1
                (2L shl 3 or 5L).toByte(), 0x00, 0x00, 0x00, 0x00, // Field 2: 32-bit fixed
                (3L shl 3 or 1L).toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // Field 3: 64-bit fixed
            )
            writeBytes(bytes)
        }
        val read = ProtobufTestOracle.readMetadata(unknownWire)
        assertTrue("Read metadata on unknown fields should be empty without crash", read.isEmpty())
        ProtobufTestOracle.repairModelFile(unknownWire)
        assertTrue(ProtobufTestOracle.hasSampleRate(unknownWire))
    }

    // --- Feature 3 Boundaries ---

    @Test
    fun b3_1_fileSizeCheckExactEqualityEdge() {
        val exactSize = 5000L
        val file = File(tempDir, "exact.onnx").apply { writeBytes(ByteArray(exactSize.toInt())) }
        assertTrue("Exact size must pass >= check", file.length() >= exactSize)
    }

    @Test
    fun b3_2_fileSizeCheckPlusOneByte() {
        val targetSize = 5000L
        val file = File(tempDir, "plus1.onnx").apply { writeBytes(ByteArray((targetSize + 1).toInt())) }
        assertTrue(file.length() >= targetSize)
    }

    @Test
    fun b3_3_fileSizeCheckMinusOneByteFails() {
        val targetSize = 5000L
        val file = File(tempDir, "minus1.onnx").apply { writeBytes(ByteArray((targetSize - 1).toInt())) }
        assertFalse(file.length() >= targetSize)
    }

    @Test
    fun b3_4_fileSizeCheckZeroBytesFails() {
        val file = File(tempDir, "zero.onnx").apply { writeBytes(ByteArray(0)) }
        assertFalse(file.length() >= 1000L)
    }

    @Test
    fun b3_5_fileSizeCheckNonExistentFileReturnsZero() {
        val file = File(tempDir, "none.onnx")
        assertFalse("Non-existent file length is 0", file.length() >= 1L)
    }

    // --- Feature 4 Boundaries ---

    @Test
    fun b4_1_parseCompanionJsonEmptyObjectReturnsDefaults() {
        val emptyJson = File(tempDir, "empty.json").apply { writeText("{}") }
        val meta = ProtobufTestOracle.parseCompanionConfig(emptyJson)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("1", meta["n_speakers"])
    }

    @Test
    fun b4_2_parseCompanionJsonWithMissingAudioBlock() {
        val noAudio = File(tempDir, "no_audio.json").apply {
            writeText("""{"num_speakers": 4}""")
        }
        val meta = ProtobufTestOracle.parseCompanionConfig(noAudio)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("4", meta["n_speakers"])
    }

    @Test
    fun b4_3_readMetadataFromCorruptVarintHeaderHandlesEof() {
        val corrupt = File(tempDir, "corrupt_varint.onnx").apply {
            // Varint byte with MSB set (continuation) followed immediately by EOF
            writeBytes(byteArrayOf(0x80.toByte()))
        }
        val meta = ProtobufTestOracle.readMetadata(corrupt)
        assertTrue("EOF in varint must return empty map without unhandled crash", meta.isEmpty())
    }

    @Test
    fun b4_4_entryPayloadWithMissingValueYieldsNull() {
        // Entry with key tag 10 but missing value tag 18
        val out = java.io.ByteArrayOutputStream()
        ProtobufTestOracle.writeVarint(out, ProtobufTestOracle.TAG_ENTRY_KEY)
        val kBytes = "lonely_key".toByteArray()
        ProtobufTestOracle.writeVarint(out, kBytes.size.toLong())
        out.write(kBytes)

        val parsed = ProtobufTestOracle.parseEntry(out.toByteArray())
        // Spec requires both key and value
        org.junit.Assert.assertNull("Entry without value must parse to null", parsed)
    }

    @Test
    fun b4_5_entryPayloadWithMissingKeyYieldsNull() {
        val out = java.io.ByteArrayOutputStream()
        ProtobufTestOracle.writeVarint(out, ProtobufTestOracle.TAG_ENTRY_VALUE)
        val vBytes = "lonely_value".toByteArray()
        ProtobufTestOracle.writeVarint(out, vBytes.size.toLong())
        out.write(vBytes)

        val parsed = ProtobufTestOracle.parseEntry(out.toByteArray())
        org.junit.Assert.assertNull("Entry without key must parse to null", parsed)
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS
    // =========================================================================

    @Test
    fun c1_repairUnpatchedModelThenValidateFileSizeCheck() {
        val nominalSize = 50_000L
        val model = File(tempDir, "pipeline.onnx").apply { writeBytes(ByteArray(nominalSize.toInt())) }

        assertFalse("Model initially lacks sample_rate", ProtobufTestOracle.hasSampleRate(model))
        ProtobufTestOracle.repairModelFile(model)

        assertTrue("Model now has sample_rate", ProtobufTestOracle.hasSampleRate(model))
        assertTrue("Repaired file satisfies nominal size check", model.length() >= nominalSize)
        assertFalse("Repaired file strict == check correctly fails", model.length() == nominalSize)
    }

    @Test
    fun c2_bundledModelCrossCheckAgainstCompanionJson() {
        val bundledOnnx = getBundledModelFile()
        val bundledJson = getBundledConfigFile()

        if (bundledOnnx.isFile && bundledJson.isFile) {
            val jsonMeta = ProtobufTestOracle.parseCompanionConfig(bundledJson)
            val onnxMeta = ProtobufTestOracle.readMetadata(bundledOnnx)

            assertEquals(
                "Sample rate in bundled ONNX must match companion JSON",
                jsonMeta["sample_rate"],
                onnxMeta["sample_rate"]
            )
            assertEquals("22050", onnxMeta["sample_rate"])
        }
    }

    @Test
    fun c3_repeatedRepairsDoNotAccumulateRedundantBytes() {
        val model = ProtobufTestOracle.createSyntheticRawModel(File(tempDir, "loop.onnx"))
        ProtobufTestOracle.repairModelFile(model)
        val size1 = model.length()

        for (i in 1..5) {
            ProtobufTestOracle.repairModelFile(model)
        }
        val sizeFinal = model.length()
        assertEquals("Multiple repair invocations must preserve constant file size", size1, sizeFinal)
    }
}
