package com.echoreading

import com.echoreading.e2e.testutil.ProtobufTestOracle
import com.echoreading.voice.OnnxMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * Adversarial stress harness targeting Protobuf wire format and OnnxMetadata edge cases.
 *
 * Verifies:
 * 1. Empty files and zero-byte models
 * 2. Corrupt headers and malformed Protobuf wire bytes
 * 3. Missing fields in metadata entries (e.g. missing value, missing key)
 * 4. Truncated Protobuf streams at various byte boundaries
 * 5. Duplicate metadata entries and overwrite semantics
 * 6. Behavioral divergence between OnnxMetadata and VoiceModelRequirementTest / ProtobufTestOracle
 */
class AdversarialWireStressTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun createMinimalOnnxHeader(): ByteArray {
        val out = ByteArrayOutputStream()
        // ModelProto.ir_version = 8 (field 1, wire 0: tag (1 shl 3) | 0 = 0x08)
        out.write(0x08)
        out.write(0x08)
        return out.toByteArray()
    }

    // =========================================================================
    // 1. EMPTY FILES & ZERO-BYTE MODELS
    // =========================================================================

    @Test
    fun test01_readMetadataOnZeroByteFileReturnsEmptyMap() {
        val emptyFile = tempFolder.newFile("empty_0b.onnx")
        val meta = OnnxMetadata.readMetadata(emptyFile)
        assertTrue("Zero-byte file must yield empty metadata", meta.isEmpty())
        assertFalse("hasSampleRate must be false on 0-byte file", OnnxMetadata.hasSampleRate(emptyFile))
    }

    @Test
    fun test02_injectMetadataOnZeroByteFileSafelyRejected() {
        val emptyFile = tempFolder.newFile("empty_inject.onnx")
        val injected = OnnxMetadata.injectMetadata(emptyFile, mapOf("sample_rate" to "22050"))
        assertFalse("injectMetadata must return false on 0-byte file", injected)
        assertEquals("0-byte file remains 0 bytes", 0L, emptyFile.length())
    }

    @Test
    fun test03_repairModelFileOnZeroByteFileReturnsFalse() {
        val emptyFile = tempFolder.newFile("empty_repair.onnx")
        val repaired = OnnxMetadata.repairModelFile(emptyFile)
        assertFalse("repairModelFile must return false on 0-byte file", repaired)
        assertEquals("0-byte file remains 0 bytes", 0L, emptyFile.length())
    }

    // =========================================================================
    // 2. CORRUPT HEADERS & MALFORMED PROTOBUF
    // =========================================================================

    @Test
    fun test04_invalidWireTypeGracefullyHandled() {
        val corrupt = tempFolder.newFile("invalid_wire_type.onnx")
        val out = ByteArrayOutputStream()
        // Tag with wire type 7 (reserved/invalid): field 1, wire type 7 -> (1 shl 3) | 7 = 0x0F
        out.write(0x0F)
        out.write(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        corrupt.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(corrupt)
        assertTrue("Invalid wire type should terminate parse without exception", meta.isEmpty())
    }

    @Test
    fun test05_infiniteVarintContinuationHandled() {
        val corrupt = tempFolder.newFile("infinite_varint.onnx")
        // 20 bytes of 0x80 (continuation bit always set)
        val infiniteVarint = ByteArray(20) { 0x80.toByte() }
        corrupt.writeBytes(infiniteVarint)

        val meta = OnnxMetadata.readMetadata(corrupt)
        assertTrue("Malformed varint (>64 bits) should be caught and return empty map", meta.isEmpty())
    }

    @Test
    fun test06_randomBinaryHeaderFuzzing() {
        val random = Random(42)
        for (i in 0 until 50) {
            val fuzzedFile = tempFolder.newFile("fuzzed_$i.onnx")
            val bytes = ByteArray(random.nextInt(2048) + 1)
            random.nextBytes(bytes)
            fuzzedFile.writeBytes(bytes)

            // Should never crash with unhandled exception
            val meta = OnnxMetadata.readMetadata(fuzzedFile)
            assertNotNull(meta)
        }
    }

    @Test
    fun test07_hugeLengthInField14SafelySkippedWithoutOOM() {
        val file = tempFolder.newFile("huge_field14.onnx")
        val out = ByteArrayOutputStream()
        // Tag 14 (0x72) with declared length 100,000 (exceeds 65536 safeguard)
        out.write(0x72)
        writeVarint(out, 100_000L)
        out.write(ByteArray(100))
        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertTrue("Field 14 exceeding 65536 safeguard should be skipped without OOM", meta.isEmpty())
    }

    @Test
    fun test08_declaredLengthExceedsFileLength() {
        val file = tempFolder.newFile("oversized_length.onnx")
        val out = ByteArrayOutputStream()
        out.write(0x72)
        writeVarint(out, 5000L) // declared 5000 bytes
        out.write("short".toByteArray()) // only 5 bytes provided
        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertTrue("Truncated entry within declared length should be discarded", meta.isEmpty())
    }

    // =========================================================================
    // 3. MISSING FIELDS IN METADATA ENTRIES
    // =========================================================================

    @Test
    fun test09_entryWithMissingValueSafelyDiscardedAndFailsHasSampleRate() {
        // StringStringEntryProto: field 1 key = "sample_rate", but NO field 2 value
        val file = tempFolder.newFile("missing_val.onnx")
        val out = ByteArrayOutputStream()

        val entryOut = ByteArrayOutputStream()
        entryOut.write(0x0A) // field 1, wire 2 (key)
        val kBytes = "sample_rate".toByteArray()
        writeVarint(entryOut, kBytes.size.toLong())
        entryOut.write(kBytes)
        // Field 2 (value) is completely omitted!

        val entryBytes = entryOut.toByteArray()
        out.write(0x72) // field 14
        writeVarint(out, entryBytes.size.toLong())
        out.write(entryBytes)

        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertFalse("Entry with missing value must be discarded", meta.containsKey("sample_rate"))
        assertFalse("hasSampleRate must be false when sample_rate value is missing or invalid", OnnxMetadata.hasSampleRate(file))
    }

    @Test
    fun test10_entryWithMissingKeyField() {
        // StringStringEntryProto: field 2 value = "22050", but NO field 1 key
        val file = tempFolder.newFile("missing_key.onnx")
        val out = ByteArrayOutputStream()

        val entryOut = ByteArrayOutputStream()
        entryOut.write(0x12) // field 2, wire 2 (value)
        val vBytes = "22050".toByteArray()
        writeVarint(entryOut, vBytes.size.toLong())
        entryOut.write(vBytes)

        val entryBytes = entryOut.toByteArray()
        out.write(0x72)
        writeVarint(out, entryBytes.size.toLong())
        out.write(entryBytes)

        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertTrue("Entry with missing key must not produce an entry with empty key", meta.isEmpty())
    }

    @Test
    fun test11_companionJsonWithMissingOrInvalidSampleRateDefaults() {
        val jsonZero = """{"audio": {"sample_rate": 0}}"""
        val metaZero = OnnxMetadata.extractMetadataFromJson(jsonZero)
        assertEquals("22050", metaZero["sample_rate"])

        val jsonNegative = """{"audio": {"sample_rate": -1}}"""
        val metaNeg = OnnxMetadata.extractMetadataFromJson(jsonNegative)
        assertEquals("22050", metaNeg["sample_rate"])

        val jsonNoAudio = """{"num_speakers": 2}"""
        val metaNoAudio = OnnxMetadata.extractMetadataFromJson(jsonNoAudio)
        assertEquals("22050", metaNoAudio["sample_rate"])
        assertEquals("2", metaNoAudio["n_speakers"])
    }

    // =========================================================================
    // 4. TRUNCATED PROTOBUF STREAMS
    // =========================================================================

    @Test
    fun test12_streamTruncatedAtEveryByteBoundary() {
        val fullBytes = ByteArrayOutputStream().apply {
            write(createMinimalOnnxHeader())
            write(OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "22050", "model_type" to "vits")))
        }.toByteArray()

        for (cutoff in 1 until fullBytes.size) {
            val truncatedFile = tempFolder.newFile("trunc_$cutoff.onnx")
            truncatedFile.writeBytes(fullBytes.copyOfRange(0, cutoff))

            val meta = OnnxMetadata.readMetadata(truncatedFile)
            assertNotNull(meta)
        }
    }

    @Test
    fun test13_firstEntryValidSecondEntryTruncatedPreservesFirst() {
        val file = tempFolder.newFile("partial_second.onnx")
        val out = ByteArrayOutputStream()
        out.write(createMinimalOnnxHeader())

        // First complete entry: sample_rate=22050
        out.write(OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "22050")))

        // Second truncated entry: field 14 tag + length, but missing payload bytes
        out.write(0x72)
        writeVarint(out, 50L) // declares 50 bytes
        out.write(byteArrayOf(0x0A, 0x05)) // only 2 bytes provided

        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("First valid entry must be preserved", "22050", meta["sample_rate"])
    }

    // =========================================================================
    // 5. DUPLICATE METADATA ENTRIES & OVERWRITE SEMANTICS
    // =========================================================================

    @Test
    fun test14_duplicateMetadataInStreamLastValueWins() {
        val file = tempFolder.newFile("duplicate.onnx")
        val out = ByteArrayOutputStream()
        out.write(createMinimalOnnxHeader())

        // First write sample_rate = 16000
        out.write(OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "16000")))
        // Later write sample_rate = 22050
        out.write(OnnxMetadata.encodeMetadataProps(mapOf("sample_rate" to "22050")))

        file.writeBytes(out.toByteArray())

        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("Later entry in stream overwrites earlier entry", "22050", meta["sample_rate"])
    }

    @Test
    fun test15_injectMetadataFiltersAlreadyPresentKeys() {
        val file = tempFolder.newFile("idempotent_inject.onnx")
        file.writeBytes(createMinimalOnnxHeader())

        OnnxMetadata.injectMetadata(file, mapOf("sample_rate" to "22050"))
        val size1 = file.length()

        // Inject again with same key and a different value
        OnnxMetadata.injectMetadata(file, mapOf("sample_rate" to "16000"))
        val size2 = file.length()

        assertEquals("injectMetadata must not append if key is already present", size1, size2)
        assertEquals("22050", OnnxMetadata.readMetadata(file)["sample_rate"])
    }

    @Test
    fun test16_injectMetadataAppendsOnlyMissingKeys() {
        val file = tempFolder.newFile("mixed_inject.onnx")
        file.writeBytes(createMinimalOnnxHeader())

        OnnxMetadata.injectMetadata(file, mapOf("sample_rate" to "22050"))
        val size1 = file.length()

        // Inject sample_rate (existing) and model_type (new)
        OnnxMetadata.injectMetadata(file, mapOf("sample_rate" to "22050", "model_type" to "vits"))
        val size2 = file.length()

        assertTrue("File should grow only for the new key", size2 > size1)
        val meta = OnnxMetadata.readMetadata(file)
        assertEquals("22050", meta["sample_rate"])
        assertEquals("vits", meta["model_type"])
    }

    // =========================================================================
    // 6. DIVERGENCE BETWEEN OnnxMetadata AND ProtobufTestOracle
    // =========================================================================

    @Test
    fun test17_oracleAndImplementationConvergenceOnZeroByteFileInjection() {
        val fileA = tempFolder.newFile("oracle_zero.onnx")
        val fileB = tempFolder.newFile("impl_zero.onnx")

        // ProtobufTestOracle.injectMetadata rejects 0-byte file:
        val oracleResult = ProtobufTestOracle.injectMetadata(fileA, mapOf("sample_rate" to "22050"))
        assertFalse("ProtobufTestOracle rejects 0-byte file", oracleResult)
        assertEquals(0L, fileA.length())

        // OnnxMetadata.injectMetadata also rejects 0-byte file:
        val implResult = OnnxMetadata.injectMetadata(fileB, mapOf("sample_rate" to "22050"))
        assertFalse("OnnxMetadata.injectMetadata rejects 0-byte file", implResult)
        assertEquals(0L, fileB.length())

        // Direct proof of convergence:
        assertEquals("Oracle and Implementation must agree on zero-byte file rejection", oracleResult, implResult)
    }

    @Test
    fun test18_oracleAndImplementationConvergenceOnMissingValueEntry() {
        // StringStringEntryProto with key but no value:
        val out = ByteArrayOutputStream()
        writeVarint(out, ProtobufTestOracle.TAG_ENTRY_KEY)
        val kBytes = "sample_rate".toByteArray()
        writeVarint(out, kBytes.size.toLong())
        out.write(kBytes)
        val payload = out.toByteArray()

        // ProtobufTestOracle.parseEntry returns null:
        val oracleParsed = ProtobufTestOracle.parseEntry(payload)
        assertTrue("ProtobufTestOracle returns null when value is missing", oracleParsed == null)

        // When written into an ONNX file, OnnxMetadata also discards entry with missing value:
        val file = tempFolder.newFile("missing_val_test.onnx")
        val fileOut = ByteArrayOutputStream()
        fileOut.write(0x72)
        writeVarint(fileOut, payload.size.toLong())
        fileOut.write(payload)
        file.writeBytes(fileOut.toByteArray())

        val readMeta = OnnxMetadata.readMetadata(file)
        assertFalse("OnnxMetadata must not include entries with missing values", readMeta.containsKey("sample_rate"))
        assertFalse("hasSampleRate must be false when sample_rate value is missing", OnnxMetadata.hasSampleRate(file))
    }
}
