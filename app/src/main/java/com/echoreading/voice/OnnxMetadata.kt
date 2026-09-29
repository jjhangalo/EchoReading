package com.echoreading.voice

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Pure Kotlin Protobuf parser and metadata injector for ONNX models.
 *
 * Sherpa-ONNX requires embedded Protobuf metadata in ModelProto.metadata_props (field 14),
 * specifically "sample_rate", "model_type", "comment", and "has_espeak". Upstream Piper
 * models omit this metadata from the .onnx file and store it only in companion .onnx.json.
 *
 * This utility streams and skips top-level ONNX fields (including multi-megabyte tensor
 * graphs in field 7) in sub-millisecond time and injects missing metadata by appending
 * repeated field 14 entries according to standard Protocol Buffers specifications.
 */
object OnnxMetadata {

    const val DEFAULT_SAMPLE_RATE = 22050
    const val DEFAULT_MODEL_TYPE = "vits"
    const val DEFAULT_COMMENT = "piper"
    const val DEFAULT_HAS_ESPEAK = "1"
    const val DEFAULT_HAS_G2PW = "0"
    const val DEFAULT_NUM_SPEAKERS = "1"
    const val DEFAULT_VERSION = "1"

    /**
     * Reads all metadata key-value properties from ModelProto.metadata_props (field 14).
     */
    fun readMetadata(file: File): Map<String, String> {
        if (!file.isFile || file.length() == 0L) return emptyMap()
        return try {
            FileInputStream(file).buffered().use { input ->
                readMetadata(input)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Streams through top-level Protobuf fields of ModelProto, efficiently skipping
     * large tensor graphs (field 7) and collecting metadata_props (field 14).
     */
    fun readMetadata(input: InputStream): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        try {
            while (true) {
                val tag = readVarintOrNull(input) ?: break
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x7L).toInt()

                when (wireType) {
                    0 -> readVarint(input) // varint
                    1 -> skipBytes(input, 8) // 64-bit fixed
                    2 -> {
                        val length = readVarint(input)
                        if (length < 0) break
                        if (fieldNum == 14) {
                            // repeated StringStringEntryProto metadata_props = 14;
                            if (length > 65536) {
                                // Safeguard against corrupted length for a metadata entry
                                skipBytes(input, length)
                            } else {
                                val entryBytes = ByteArray(length.toInt())
                                var read = 0
                                while (read < entryBytes.size) {
                                    val count = input.read(entryBytes, read, entryBytes.size - read)
                                    if (count < 0) break
                                    read += count
                                }
                                if (read == entryBytes.size) {
                                    parseStringEntry(entryBytes)?.let { (k, v) ->
                                        metadata[k] = v
                                    }
                                }
                            }
                        } else {
                            // Skip non-metadata length-delimited fields (e.g. GraphProto field 7)
                            skipBytes(input, length)
                        }
                    }
                    5 -> skipBytes(input, 4) // 32-bit fixed
                    else -> break
                }
            }
        } catch (_: EOFException) {
            // Reached EOF or stream truncated
        } catch (_: Exception) {
            // Malformed data
        }
        return metadata
    }

    /**
     * Checks whether the .onnx model file has the required "sample_rate" in metadata_props.
     */
    fun hasSampleRate(file: File): Boolean =
        readMetadata(file)["sample_rate"]?.toIntOrNull()?.let { it > 0 } == true

    /**
     * Extracts required Sherpa-ONNX metadata from a companion .onnx.json file.
     */
    fun extractMetadataFromConfig(configFile: File): Map<String, String> {
        if (!configFile.isFile) return emptyMap()
        return try {
            extractMetadataFromJson(configFile.readText())
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Extracts required Sherpa-ONNX metadata from a JSON string.
     */
    fun extractMetadataFromJson(jsonText: String): Map<String, String> {
        return try {
            val json = JSONObject(jsonText)
            val audio = json.optJSONObject("audio")
            val sampleRate = audio?.optInt("sample_rate", 0)?.takeIf { it > 0 }
                ?: json.optInt("sample_rate", 0).takeIf { it > 0 }
                ?: DEFAULT_SAMPLE_RATE

            val numSpeakers = json.optInt("num_speakers", 1).let { if (it <= 0) 1 else it }
            val langObj = json.optJSONObject("language")
            val langName = langObj?.optString("name_english", "") ?: ""
            val langCode = langObj?.optString("code", "") ?: ""
            val espeak = json.optJSONObject("espeak")
            val voiceName = espeak?.optString("voice", "") ?: langCode

            mapOf(
                "sample_rate" to sampleRate.toString(),
                "model_type" to DEFAULT_MODEL_TYPE,
                "comment" to DEFAULT_COMMENT,
                "has_espeak" to DEFAULT_HAS_ESPEAK,
                "has_g2pw" to DEFAULT_HAS_G2PW,
                "n_speakers" to numSpeakers.toString(),
                "version" to DEFAULT_VERSION,
                "language" to langName.ifEmpty { langCode },
                "voice" to voiceName,
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Encodes key-value pairs into Protocol Buffer binary bytes as repeated field 14
     * (ModelProto.metadata_props: repeated StringStringEntryProto).
     */
    fun encodeMetadataProps(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valBytes = value.toByteArray(Charsets.UTF_8)

            val entryOut = ByteArrayOutputStream()
            // StringStringEntryProto.key: field 1, wire type 2 -> tag (1 shl 3) | 2 = 0x0A
            entryOut.write(0x0A)
            writeVarint(entryOut, keyBytes.size.toLong())
            entryOut.write(keyBytes)

            // StringStringEntryProto.value: field 2, wire type 2 -> tag (2 shl 3) | 2 = 0x12
            entryOut.write(0x12)
            writeVarint(entryOut, valBytes.size.toLong())
            entryOut.write(valBytes)

            val entryBytes = entryOut.toByteArray()
            // ModelProto.metadata_props: field 14, wire type 2 -> tag (14 shl 3) | 2 = 114 = 0x72
            out.write(0x72)
            writeVarint(out, entryBytes.size.toLong())
            out.write(entryBytes)
        }
        return out.toByteArray()
    }

    /**
     * Appends missing metadata properties to an existing .onnx file.
     * Returns true if metadata was injected or already present, false if file is missing.
     */
    fun injectMetadata(onnxFile: File, metadata: Map<String, String>): Boolean {
        if (!onnxFile.isFile || onnxFile.length() == 0L) return false
        if (metadata.isEmpty()) return true
        val existing = readMetadata(onnxFile)
        val missing = metadata.filter { !existing.containsKey(it.key) }
        if (missing.isEmpty()) return true

        val bytesToAppend = encodeMetadataProps(missing)
        if (bytesToAppend.isEmpty()) return true

        FileOutputStream(onnxFile, true).use { fos ->
            fos.write(bytesToAppend)
            fos.flush()
        }
        return true
    }

    /**
     * Overload to inject metadata with sample rate and number of speakers.
     */
    @JvmOverloads
    fun injectMetadata(onnxFile: File, sampleRate: Int, numSpeakers: Int = 1): Boolean {
        val meta = mapOf(
            "sample_rate" to sampleRate.toString(),
            "model_type" to DEFAULT_MODEL_TYPE,
            "comment" to DEFAULT_COMMENT,
            "has_espeak" to DEFAULT_HAS_ESPEAK,
            "has_g2pw" to DEFAULT_HAS_G2PW,
            "n_speakers" to numSpeakers.toString(),
            "version" to DEFAULT_VERSION,
        )
        return injectMetadata(onnxFile, meta)
    }

    /**
     * Verifies and patches missing metadata in-place for an ONNX model file.
     * Resolves configuration from companion .onnx.json, meta.json, or fallback defaults.
     * Returns true if the file is repaired or already valid, false if non-existent or unrepairable.
     */
    @JvmOverloads
    fun repairModelFile(
        onnxFile: File,
        configFile: File? = null,
        fallbackSampleRate: Int = DEFAULT_SAMPLE_RATE,
        fallbackLanguage: String = "",
    ): Boolean {
        if (!onnxFile.isFile || onnxFile.length() == 0L) return false
        if (hasSampleRate(onnxFile)) return true

        val dir = onnxFile.parentFile
        val resolvedConfig = when {
            configFile != null && configFile.isFile -> configFile
            dir != null -> {
                val directSibling = File(dir, "${onnxFile.name}.json")
                if (directSibling.isFile) directSibling
                else dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx.json") }
            }
            else -> null
        }

        val meta = if (resolvedConfig != null && resolvedConfig.isFile) {
            val extracted = extractMetadataFromConfig(resolvedConfig)
            if (extracted.isNotEmpty() && extracted.containsKey("sample_rate")) {
                extracted
            } else {
                defaultMetadata(fallbackSampleRate, fallbackLanguage)
            }
        } else {
            val metaFile = dir?.let { File(it, "meta.json") }
            if (metaFile != null && metaFile.isFile) {
                try {
                    val mJson = JSONObject(metaFile.readText())
                    val sr = mJson.optInt("sampleRate", fallbackSampleRate)
                    val lang = mJson.optString("languageCode", fallbackLanguage)
                    defaultMetadata(sr, lang)
                } catch (_: Exception) {
                    defaultMetadata(fallbackSampleRate, fallbackLanguage)
                }
            } else {
                defaultMetadata(fallbackSampleRate, fallbackLanguage)
            }
        }
        return injectMetadata(onnxFile, meta)
    }

    /**
     * Scans context.noBackupFilesDir/voices/ and repairs any installed models lacking
     * sample_rate metadata, updating their meta.json fileSize accordingly.
     * Returns the count of repaired voice models.
     */
    fun repairAllInstalledVoices(context: Context): Int {
        val voicesDir = File(context.noBackupFilesDir, "voices")
        if (!voicesDir.isDirectory) return 0
        var count = 0
        voicesDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val onnxFile = dir.listFiles()?.firstOrNull {
                    it.name.endsWith(".onnx") && !it.name.endsWith(".onnx.json")
                }
                if (onnxFile != null && !hasSampleRate(onnxFile)) {
                    if (repairModelFile(onnxFile)) {
                        count++
                        val metaFile = File(dir, "meta.json")
                        if (metaFile.isFile) {
                            try {
                                val json = JSONObject(metaFile.readText())
                                json.put("fileSize", onnxFile.length())
                                metaFile.writeText(json.toString(2))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return count
    }

    private fun defaultMetadata(sampleRate: Int, language: String): Map<String, String> =
        mapOf(
            "sample_rate" to sampleRate.toString(),
            "model_type" to DEFAULT_MODEL_TYPE,
            "comment" to DEFAULT_COMMENT,
            "has_espeak" to DEFAULT_HAS_ESPEAK,
            "has_g2pw" to DEFAULT_HAS_G2PW,
            "n_speakers" to DEFAULT_NUM_SPEAKERS,
            "version" to DEFAULT_VERSION,
            "language" to language,
        )

    private fun parseStringEntry(bytes: ByteArray): Pair<String, String>? {
        var key = ""
        var value = ""
        var offset = 0
        try {
            while (offset < bytes.size) {
                val tagByte = bytes[offset++].toInt() and 0xFF
                var tag = (tagByte and 0x7F).toLong()
                var shift = 7
                var b = tagByte
                while ((b and 0x80) != 0 && offset < bytes.size && shift < 70) {
                    b = bytes[offset++].toInt() and 0xFF
                    tag = tag or ((b.toLong() and 0x7F) shl shift)
                    shift += 7
                }
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x7L).toInt()

                when (wireType) {
                    0 -> {
                        while (offset < bytes.size) {
                            val vb = bytes[offset++].toInt() and 0xFF
                            if ((vb and 0x80) == 0) break
                        }
                    }
                    1 -> offset += 8
                    2 -> {
                        var len = 0
                        var lenShift = 0
                        while (offset < bytes.size && lenShift < 32) {
                            val lb = bytes[offset++].toInt() and 0xFF
                            len = len or ((lb and 0x7F) shl lenShift)
                            if ((lb and 0x80) == 0) break
                            lenShift += 7
                        }
                        if (len < 0 || offset + len > bytes.size) break
                        val str = String(bytes, offset, len, Charsets.UTF_8)
                        offset += len
                        if (fieldNum == 1) key = str
                        else if (fieldNum == 2) value = str
                    }
                    5 -> offset += 4
                    else -> break
                }
            }
        } catch (_: Exception) {
            // Return what was successfully parsed
        }
        return if (key.isNotEmpty() && value.isNotEmpty()) key to value else null
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            val b = input.read()
            if (b == -1) throw EOFException("Unexpected EOF while reading varint")
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Malformed varint: exceeds 64 bits")
    }

    private fun readVarintOrNull(input: InputStream): Long? {
        val first = input.read()
        if (first == -1) return null
        var result = (first.toLong() and 0x7FL)
        if ((first and 0x80) == 0) return result
        var shift = 7
        while (shift < 70) {
            val b = input.read()
            if (b == -1) throw EOFException("Unexpected EOF while reading varint continuation")
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }
        throw IllegalStateException("Malformed varint: exceeds 64 bits")
    }

    private fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7F).toInt())
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                val b = input.read()
                if (b == -1) throw EOFException("Unexpected EOF while skipping")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
