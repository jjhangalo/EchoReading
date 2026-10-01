package com.echoreading.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {

    data class DecodedAudio(
        val samples: FloatArray,     // PCM normalizado [-1, 1]
        val sampleRate: Int,         // Sempre 16000 após resampling
        val durationMs: Long,        // Duração original em ms
    )

    suspend fun decode(context: Context, uri: Uri): DecodedAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcmChunks = mutableListOf<ShortArray>()
        var currentSampleRate = 16000
        var currentChannels = 1
        var durationMs = 0L

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }

            if (audioTrackIndex == -1 || format == null) {
                throw IllegalArgumentException("No audio track found")
            }

            extractor.selectTrack(audioTrackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            durationMs = durationUs / 1000

            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                currentSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                currentChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mp4"

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false

            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isEOS) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val count = shortBuffer.remaining()
                        val chunk = ShortArray(count)
                        shortBuffer.get(chunk)
                        pcmChunks.add(chunk)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        currentSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        currentChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }

        val totalSamples = pcmChunks.sumOf { it.size }
        val floatData = FloatArray(totalSamples)
        var offset = 0
        for (chunk in pcmChunks) {
            for (s in chunk) {
                floatData[offset++] = s / 32768f
            }
        }

        val monoData = if (currentChannels > 1) {
            stereoToMono(floatData, currentChannels)
        } else {
            floatData
        }

        val targetSampleRate = 16000
        val resampledData = if (currentSampleRate != targetSampleRate && currentSampleRate > 0) {
            resample(monoData, currentSampleRate, targetSampleRate)
        } else {
            monoData
        }

        DecodedAudio(resampledData, targetSampleRate, durationMs)
    }

    private fun resample(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val ratio = inputRate.toDouble() / outputRate.toDouble()
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val position = i * ratio
            val index = position.toInt()
            val fraction = position - index
            if (index + 1 < input.size) {
                output[i] = (input[index] * (1 - fraction) + input[index + 1] * fraction).toFloat()
            } else {
                output[i] = input[index]
            }
        }
        return output
    }

    private fun stereoToMono(samples: FloatArray, channels: Int): FloatArray {
        val outLength = samples.size / channels
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += samples[i * channels + c]
            }
            output[i] = sum / channels
        }
        return output
    }
}
