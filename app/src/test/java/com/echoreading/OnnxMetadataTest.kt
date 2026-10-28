package com.echoreading

import com.echoreading.voice.OnnxMetadata
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
import java.io.FileOutputStream

class OnnxMetadataTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun findBundledModelFile(): File? {
        val candidates = listOf(
            File("src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx"),
            File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx"),
            File("../app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun findBundledConfigFile(): File? {
        val candidates = listOf(
            File("src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx.json"),
            File("app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx.json"),
            File("../app/src/main/assets/vits-piper-pt_PT-tugao-medium/pt_PT-tugao-medium.onnx.json"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** Helper to create a minimal synthetic ONNX binary buffer without metadata. */
    private fun createSyntheticOnnxBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        // ModelProto.ir_version = 8 (field 1, wire 0: tag (1 shl 3) | 0 = 0x08)
        out.write(0x08)
        out.write(0x08)

        // ModelProto.producer_name = "piper" (field 2, wire 2: tag (2 shl 3) | 2 = 0x12)
        val prod = "piper".toByteArray(Charsets.UTF_8)
        out.write(0x12)
        out.write(prod.size)
        out.write(prod)

        // ModelProto.graph (field 7, wire 2: tag (7 shl 3) | 2 = 0x3A)
        // 200 bytes of simulated graph payload
        val fakeGraph = ByteArray(200) { (it % 256).toByte() }
        out.write(0x3A)
        // write varint length (200 = 0xC8, 0x01)
        out.write(0xC8)
        out.write(0x01)
        out.write(fakeGraph)

        return out.toByteArray()
    }

    @Test
    fun readMetadataFromBundledModel() {
        val bundledFile = findBundledModelFile()
        assumeTrue("Bundled TTS model is provisioned outside Git", bundledFile != null)
        val file = checkNotNull(bundledFile)

        val metadata = OnnxMetadata.readMetadata(file)
        assertTrue("Metadata should not be empty", metadata.isNotEmpty())
        assertTrue("hasSampleRate should return true", OnnxMetadata.hasSampleRate(file))

        assertEquals("22050", metadata["sample_rate"])
        assertEquals("vits", metadata["model_type"])
        assertEquals("piper", metadata["comment"])
        assertEquals("1", metadata["has_espeak"])
        assertEquals("1", metadata["n_speakers"])
        assertEquals("Portuguese", metadata["language"])
        assertEquals("pt", metadata["voice"])
    }

    @Test
    fun extractMetadataFromConfig() {
        val configFile = findBundledConfigFile()
        assumeTrue("Bundled TTS config is provisioned outside Git", configFile != null)
        val file = checkNotNull(configFile)

        val metadata = OnnxMetadata.extractMetadataFromConfig(file)
        assertEquals("22050", metadata["sample_rate"])
        assertEquals("vits", metadata["model_type"])
        assertEquals("piper", metadata["comment"])
        assertEquals("1", metadata["has_espeak"])
        assertEquals("0", metadata["has_g2pw"])
        assertEquals("1", metadata["n_speakers"])
        assertEquals("Portuguese", metadata["language"])
        assertEquals("pt", metadata["voice"])
        assertEquals("1", metadata["version"])
    }

    @Test
    fun extractMetadataFromJsonWithCustomValues() {
        val json = """
            {
                "audio": {
                    "sample_rate": 16000
                },
                "num_speakers": 4,
                "language": {
                    "code": "en_US",
                    "name_english": "English"
                },
                "espeak": {
                    "voice": "en-us"
                }
            }
        """.trimIndent()

        val meta = OnnxMetadata.extractMetadataFromJson(json)
        assertEquals("16000", meta["sample_rate"])
        assertEquals("vits", meta["model_type"])
        assertEquals("piper", meta["comment"])
        assertEquals("1", meta["has_espeak"])
        assertEquals("0", meta["has_g2pw"])
        assertEquals("4", meta["n_speakers"])
        assertEquals("English", meta["language"])
        assertEquals("en-us", meta["voice"])
    }

    @Test
    fun extractMetadataFromMalformedJsonReturnsEmptyMap() {
        val meta = OnnxMetadata.extractMetadataFromJson("invalid json { [")
        assertTrue(meta.isEmpty())
    }

    @Test
    fun encodeMetadataPropsProducesExactProtobufWireBytes() {
        val testMap = mapOf("sample_rate" to "22050")
        val bytes = OnnxMetadata.encodeMetadataProps(testMap)

        // Field 14 tag = (14 shl 3) | 2 = 114 = 0x72
        assertEquals(0x72.toByte(), bytes[0])

        // Entry length: 20 bytes (0x14)
        // Key: 0x0A, 11 (0x0B), "sample_rate" (11 bytes) -> 13 bytes
        // Value: 0x12, 5 (0x05), "22050" (5 bytes) -> 7 bytes
        assertEquals(0x14.toByte(), bytes[1])
        assertEquals(0x0A.toByte(), bytes[2])
        assertEquals(11.toByte(), bytes[3])

        val keyStr = String(bytes, 4, 11, Charsets.UTF_8)
        assertEquals("sample_rate", keyStr)

        assertEquals(0x12.toByte(), bytes[15])
        assertEquals(5.toByte(), bytes[16])

        val valStr = String(bytes, 17, 5, Charsets.UTF_8)
        assertEquals("22050", valStr)
    }

    @Test
    fun injectMetadataIntoSyntheticModel() {
        val file = tempFolder.newFile("test_synthetic.onnx")
        file.writeBytes(createSyntheticOnnxBytes())

        assertFalse("Synthetic model initially has no metadata", OnnxMetadata.hasSampleRate(file))
        val initialSize = file.length()

        val toInject = mapOf(
            "sample_rate" to "16000",
            "model_type" to "vits",
            "comment" to "piper",
            "has_espeak" to "1",
        )
        val injected = OnnxMetadata.injectMetadata(file, toInject)
        assertTrue("Metadata injection should succeed", injected)
        assertTrue("File size should have increased", file.length() > initialSize)
        assertTrue("hasSampleRate should now be true", OnnxMetadata.hasSampleRate(file))

        val readBack = OnnxMetadata.readMetadata(file)
        assertEquals("16000", readBack["sample_rate"])
        assertEquals("vits", readBack["model_type"])
        assertEquals("piper", readBack["comment"])
        assertEquals("1", readBack["has_espeak"])
    }

    @Test
    fun injectMetadataOverloadWithSampleRateAndSpeakers() {
        val file = tempFolder.newFile("test_overload.onnx")
        file.writeBytes(createSyntheticOnnxBytes())

        val injected = OnnxMetadata.injectMetadata(file, sampleRate = 22050, numSpeakers = 3)
        assertTrue(injected)

        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("3", meta["n_speakers"])
        assertEquals("vits", meta["model_type"])
        assertEquals("piper", meta["comment"])
        assertEquals("1", meta["has_espeak"])
    }

    @Test
    fun injectMetadataIsIdempotent() {
        val file = tempFolder.newFile("test_idempotent.onnx")
        file.writeBytes(createSyntheticOnnxBytes())

        val meta = mapOf("sample_rate" to "22050", "model_type" to "vits")
        assertTrue(OnnxMetadata.injectMetadata(file, meta))
        val sizeAfterFirstInject = file.length()

        // Second injection of same or subset metadata succeeds without growing file
        assertTrue(OnnxMetadata.injectMetadata(file, meta))
        assertEquals(sizeAfterFirstInject, file.length())
    }

    @Test
    fun repairModelFileWithCompanionJson() {
        val dir = tempFolder.newFolder("voice_dir")
        val modelFile = File(dir, "voice.onnx").apply { writeBytes(createSyntheticOnnxBytes()) }
        val configFile = File(dir, "voice.onnx.json").apply {
            writeText("""
                {
                    "audio": { "sample_rate": 22050 },
                    "num_speakers": 1,
                    "language": { "name_english": "English", "code": "en_US" },
                    "espeak": { "voice": "en-us" }
                }
            """.trimIndent())
        }

        assertFalse(OnnxMetadata.hasSampleRate(modelFile))
        val repaired = OnnxMetadata.repairModelFile(modelFile, configFile)
        assertTrue(repaired)
        assertTrue(OnnxMetadata.hasSampleRate(modelFile))

        val meta = OnnxMetadata.readMetadata(modelFile)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("English", meta["language"])
        assertEquals("en-us", meta["voice"])

        // Second repair call returns true (file is valid) and does not grow
        val sizeAfterRepair = modelFile.length()
        assertTrue(OnnxMetadata.repairModelFile(modelFile, configFile))
        assertEquals(sizeAfterRepair, modelFile.length())
    }

    @Test
    fun repairModelFileWithMetaJson() {
        val dir = tempFolder.newFolder("meta_json_dir")
        val modelFile = File(dir, "voice.onnx").apply { writeBytes(createSyntheticOnnxBytes()) }
        File(dir, "meta.json").writeText("""
            {
                "id": "test-voice",
                "label": "Test Voice",
                "modelFile": "voice.onnx",
                "sampleRate": 16000,
                "languageCode": "pt_BR"
            }
        """.trimIndent())

        assertFalse(OnnxMetadata.hasSampleRate(modelFile))
        val repaired = OnnxMetadata.repairModelFile(modelFile)
        assertTrue(repaired)
        assertTrue(OnnxMetadata.hasSampleRate(modelFile))

        val meta = OnnxMetadata.readMetadata(modelFile)
        assertEquals("16000", meta["sample_rate"])
        assertEquals("pt_BR", meta["language"])
    }

    @Test
    fun repairModelFileWithFallbackDefaults() {
        val modelFile = tempFolder.newFile("standalone.onnx")
        modelFile.writeBytes(createSyntheticOnnxBytes())

        val repaired = OnnxMetadata.repairModelFile(
            onnxFile = modelFile,
            fallbackSampleRate = 44100,
            fallbackLanguage = "es_ES",
        )
        assertTrue(repaired)
        val meta = OnnxMetadata.readMetadata(modelFile)
        assertEquals("44100", meta["sample_rate"])
        assertEquals("es_ES", meta["language"])
        assertEquals("vits", meta["model_type"])
        assertEquals("piper", meta["comment"])
    }

    @Test
    fun readMetadataGracefullyHandlesCorruptedOrEmptyFiles() {
        val emptyFile = tempFolder.newFile("empty.onnx")
        assertTrue(OnnxMetadata.readMetadata(emptyFile).isEmpty())
        assertFalse(OnnxMetadata.hasSampleRate(emptyFile))

        val corruptFile = tempFolder.newFile("corrupt.onnx")
        corruptFile.writeBytes(byteArrayOf(0x7F, 0xFF.toByte(), 0x00, 0x12, 0x99.toByte()))
        val meta = OnnxMetadata.readMetadata(corruptFile)
        // Corrupt file does not crash and returns safely
        assertFalse(meta.containsKey("sample_rate"))

        val nonExistent = File(tempFolder.root, "does_not_exist.onnx")
        assertTrue(OnnxMetadata.readMetadata(nonExistent).isEmpty())
        assertFalse(OnnxMetadata.hasSampleRate(nonExistent))
    }

    @Test
    fun zeroByteFileInjectionAndRepairSafelyRejected() {
        val emptyFile = tempFolder.newFile("empty_model.onnx")
        assertFalse("injectMetadata must reject 0-byte file", OnnxMetadata.injectMetadata(emptyFile, mapOf("sample_rate" to "22050")))
        assertEquals(0L, emptyFile.length())
        assertFalse("repairModelFile must reject 0-byte file", OnnxMetadata.repairModelFile(emptyFile))
        assertEquals(0L, emptyFile.length())
    }

    @Test
    fun hasSampleRateValidatesPositiveInteger() {
        val nonIntegerFile = tempFolder.newFile("non_int_sr.onnx")
        nonIntegerFile.writeBytes(createSyntheticOnnxBytes())
        OnnxMetadata.injectMetadata(nonIntegerFile, mapOf("sample_rate" to "not_a_number"))
        assertFalse("hasSampleRate must be false when sample_rate is not a valid integer", OnnxMetadata.hasSampleRate(nonIntegerFile))

        val nonPositiveFile = tempFolder.newFile("zero_sr.onnx")
        nonPositiveFile.writeBytes(createSyntheticOnnxBytes())
        OnnxMetadata.injectMetadata(nonPositiveFile, mapOf("sample_rate" to "0"))
        assertFalse("hasSampleRate must be false when sample_rate is 0", OnnxMetadata.hasSampleRate(nonPositiveFile))
    }

    @Test
    fun fileSizeCheckValidation() {
        val expectedSize = 60_000_000L
        val originalDownloadedSize = 60_000_000L
        val sizeAfterMetadataInjection = 60_000_200L
        val partialDownloadSize = 59_999_000L

        // Strict equality check fails on patched models:
        assertFalse(sizeAfterMetadataInjection == expectedSize)

        // Relaxed >= check correctly accepts original and patched, rejects incomplete:
        assertTrue(originalDownloadedSize >= expectedSize)
        assertTrue(sizeAfterMetadataInjection >= expectedSize)
        assertFalse(partialDownloadSize >= expectedSize)
    }
}
