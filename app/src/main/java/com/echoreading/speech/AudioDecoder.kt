package com.echoreading.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object AudioDecoder {
    const val SAMPLE_RATE = 16_000
    const val MAX_DURATION_MS = 30L * 60L * 1000L
    const val CHUNK_SAMPLES = 30 * SAMPLE_RATE
    private const val MAX_SAMPLES = SAMPLE_RATE.toLong() * 30L * 60L

    data class DecodedAudio(
        val pcmFile: File,
        val sampleRate: Int,
        val durationMs: Long,
        val totalSamples: Long,
    )

    suspend fun decodeToPcm(
        context: Context,
        uri: Uri,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.parentFile, "${outputFile.name}.part")
        if (temporary.exists()) temporary.delete()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var durationUs = 0L
        var normalizer: PcmNormalizer? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    inputFormat = candidate
                    break
                }
            }
            require(trackIndex >= 0 && inputFormat != null) { "O ficheiro não contém uma faixa de áudio" }
            val format = checkNotNull(inputFormat)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            require(durationUs <= 0 || durationUs / 1000 <= MAX_DURATION_MS) {
                "O áudio excede o limite de 30 minutos"
            }

            extractor.selectTrack(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            BufferedOutputStream(FileOutputStream(temporary)).use { output ->
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var endWaitStarted = 0L
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                                ?: throw IllegalStateException("Descodificador sem buffer de entrada")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                                endWaitStarted = SystemClock.elapsedRealtime()
                            } else {
                                val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                                if (durationUs > 0) onProgress((sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            normalizer = PcmNormalizer(codec.outputFormat, output)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputEnded && SystemClock.elapsedRealtime() - endWaitStarted > 10_000) {
                                throw IllegalStateException("O descodificador não concluiu o áudio")
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val writer = normalizer ?: PcmNormalizer(codec.outputFormat, output).also {
                                    normalizer = it
                                }
                                writer.write(buffer)
                                require(writer.outputSamples <= MAX_SAMPLES) {
                                    "O áudio excede o limite de 30 minutos"
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                output.flush()
            }

            val sampleCount = normalizer?.outputSamples ?: 0L
            require(sampleCount > 0) { "O ficheiro de áudio está vazio" }
            if (outputFile.exists()) outputFile.delete()
            check(temporary.renameTo(outputFile)) { "Não foi possível guardar o áudio descodificado" }
            onProgress(1f)
            DecodedAudio(
                pcmFile = outputFile,
                sampleRate = SAMPLE_RATE,
                durationMs = sampleCount * 1000L / SAMPLE_RATE,
                totalSamples = sampleCount,
            )
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (!outputFile.exists() && temporary.exists()) temporary.delete()
        }
    }

    suspend fun readChunk(file: File, chunkIndex: Int): FloatArray = withContext(Dispatchers.IO) {
        val offsetSamples = chunkIndex.toLong() * CHUNK_SAMPLES
        val offsetBytes = offsetSamples * 2L
        if (offsetBytes >= file.length()) return@withContext FloatArray(0)
        val count = minOf(CHUNK_SAMPLES.toLong(), (file.length() - offsetBytes) / 2L).toInt()
        val result = FloatArray(count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(offsetBytes)
            val bytes = ByteArray(count * 2)
            input.readFully(bytes)
            var byteIndex = 0
            for (index in result.indices) {
                val value = (bytes[byteIndex].toInt() and 0xff) or (bytes[byteIndex + 1].toInt() shl 8)
                result[index] = value.toShort() / 32768f
                byteIndex += 2
            }
        }
        result
    }

    private class PcmNormalizer(format: MediaFormat, private val output: BufferedOutputStream) {
        private val inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        private val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else AudioFormat.ENCODING_PCM_16BIT

        var outputSamples = 0L
            private set
        private var inputIndex = 0L
        private var nextOutputPosition = 0.0
        private var previous = 0f

        init {
            require(inputRate > 0 && channels > 0) { "Formato PCM inválido" }
            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                "Formato PCM não suportado"
            }
        }

        fun write(buffer: ByteBuffer) {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) writeFloats(buffer.asFloatBuffer())
            else writeShorts(buffer.asShortBuffer())
        }

        private fun writeShorts(buffer: java.nio.ShortBuffer) {
            while (buffer.remaining() >= channels) {
                var mono = 0f
                repeat(channels) { mono += buffer.get() / 32768f }
                accept(mono / channels)
            }
        }

        private fun writeFloats(buffer: java.nio.FloatBuffer) {
            while (buffer.remaining() >= channels) {
                var mono = 0f
                repeat(channels) { mono += buffer.get().coerceIn(-1f, 1f) }
                accept(mono / channels)
            }
        }

        private fun accept(current: Float) {
            if (inputIndex == 0L) previous = current
            while (nextOutputPosition <= inputIndex.toDouble()) {
                val sample = if (inputIndex == 0L) current else {
                    val fraction = (nextOutputPosition - (inputIndex - 1)).coerceIn(0.0, 1.0)
                    previous + (current - previous) * fraction.toFloat()
                }
                val shortValue = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
                output.write(shortValue and 0xff)
                output.write((shortValue ushr 8) and 0xff)
                outputSamples++
                nextOutputPosition += inputRate.toDouble() / SAMPLE_RATE.toDouble()
            }
            previous = current
            inputIndex++
        }
    }
}
