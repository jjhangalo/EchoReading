package com.echoreading.reader

import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFiles {
    fun write(file: File, audio: GeneratedAudio): Long {
        val count = audio.samples.size
        val pcmBytes = count * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes + 36)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(audio.sampleRate)
            putInt(audio.sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes)
        }
        file.outputStream().buffered().use { output ->
            output.write(header.array())
            val bytes = ByteArray(8192)
            var used = 0
            audio.samples.forEach { value ->
                val sample = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                bytes[used++] = sample.toByte()
                bytes[used++] = (sample ushr 8).toByte()
                if (used == bytes.size) {
                    output.write(bytes)
                    used = 0
                }
            }
            if (used > 0) output.write(bytes, 0, used)
        }
        return count * 1000L / audio.sampleRate
    }
}
