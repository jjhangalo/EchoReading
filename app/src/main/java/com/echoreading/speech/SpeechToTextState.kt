package com.echoreading.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TranscriptionStatus { IDLE, DECODING, CHECKING_QUALITY, TRANSCRIBING, DONE, ERROR }

data class TranscriptionSnapshot(
    val status: TranscriptionStatus = TranscriptionStatus.IDLE,
    val audioFileName: String = "",
    val audioDurationMs: Long = 0,
    val transcribedText: String = "",
    val error: String? = null,
    val qualityReport: AudioQualityReport? = null,
    val progress: Float = 0f,        // 0.0–1.0 para progresso visual
)

object SpeechToTextState {
    val snapshot = MutableStateFlow(TranscriptionSnapshot())
    val loadAudioEvent = MutableSharedFlow<Uri>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pendingAudioUri = MutableStateFlow<Uri?>(null)

    val isRecording = MutableStateFlow(false)
    val recordingDurationSec = MutableStateFlow(0)
    val recordingAmplitude = MutableStateFlow(0f)

    private var activeJob: Job? = null
    private var recordingJob: Job? = null

    fun startRecording(context: Context) {
        if (isRecording.value) return
        val appContext = context.applicationContext
        isRecording.value = true
        recordingDurationSec.value = 0
        recordingAmplitude.value = 0f

        activeJob?.cancel()
        recordingJob?.cancel()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufSize = if (minBuf > 0) maxOf(minBuf * 2, 2048) else 4096

            var audioRecord: AudioRecord? = null
            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufSize
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    isRecording.value = false
                    snapshot.value = snapshot.value.copy(
                        status = TranscriptionStatus.ERROR,
                        error = "Microfone não disponível"
                    )
                    return@launch
                }

                audioRecord.startRecording()
                val chunks = mutableListOf<ShortArray>()
                val buffer = ShortArray(1024)
                var totalSamples = 0L

                while (isRecording.value && isActive) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        chunks.add(buffer.copyOf(read))
                        totalSamples += read
                        recordingDurationSec.value = (totalSamples / sampleRate).toInt()

                        var maxVal = 0
                        for (k in 0 until read) {
                            val a = kotlin.math.abs(buffer[k].toInt())
                            if (a > maxVal) maxVal = a
                        }
                        recordingAmplitude.value = (maxVal / 32768f).coerceIn(0f, 1f)
                    }
                }

                try {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                } catch (_: Exception) {
                }

                val totalCount = chunks.sumOf { it.size }
                if (totalCount == 0) {
                    snapshot.value = snapshot.value.copy(
                        status = TranscriptionStatus.ERROR,
                        error = "transcribe_error_silent"
                    )
                    return@launch
                }

                // Direct short-to-float normalization [-1f, 1f] (/ponytail ultra)
                val floatSamples = FloatArray(totalCount)
                var offset = 0
                for (chunk in chunks) {
                    for (s in chunk) {
                        floatSamples[offset++] = s / 32768f
                    }
                }

                val durationMs = (totalCount.toLong() * 1000L) / sampleRate
                processSamples(appContext, floatSamples, sampleRate, "Gravação de voz", durationMs)
            } catch (_: CancellationException) {
                return@launch
            } catch (_: SecurityException) {
                snapshot.value = snapshot.value.copy(
                    status = TranscriptionStatus.ERROR,
                    error = "Permissão de gravação de áudio necessária"
                )
            } catch (e: Exception) {
                snapshot.value = snapshot.value.copy(
                    status = TranscriptionStatus.ERROR,
                    error = e.message ?: "Erro na gravação"
                )
            } finally {
                isRecording.value = false
                recordingAmplitude.value = 0f
                try {
                    audioRecord?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stopRecording(context: Context) {
        if (!isRecording.value) return
        isRecording.value = false
        recordingAmplitude.value = 0f
    }

    fun transcribe(context: Context, audioUri: Uri) {
        val appContext = context.applicationContext
        snapshot.value = TranscriptionSnapshot(
            status = TranscriptionStatus.DECODING,
            audioFileName = ""
        )

        activeJob?.cancel()
        activeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Resolved strictly on Dispatchers.IO
                val fileName = getFileName(appContext, audioUri) ?: "audio_desconhecido"
                snapshot.value = snapshot.value.copy(audioFileName = fileName)

                val decoded = AudioDecoder.decode(appContext, audioUri)
                processSamples(
                    context = appContext,
                    samples = decoded.samples,
                    sampleRate = decoded.sampleRate,
                    fileName = fileName,
                    durationMs = decoded.durationMs
                )
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                snapshot.value = snapshot.value.copy(
                    status = TranscriptionStatus.ERROR,
                    error = e.message ?: "Erro desconhecido"
                )
            }
        }
    }

    internal suspend fun processSamples(
        context: Context,
        samples: FloatArray,
        sampleRate: Int = 16000,
        fileName: String = "audio",
        durationMs: Long = (samples.size.toLong() * 1000L) / sampleRate
    ) {
        try {
            currentCoroutineContext().ensureActive()
            snapshot.value = snapshot.value.copy(
                status = TranscriptionStatus.CHECKING_QUALITY,
                audioFileName = fileName,
                audioDurationMs = durationMs
            )

            // Quality Check
            val quality = AudioQualityChecker.analyze(samples, sampleRate)
            snapshot.value = snapshot.value.copy(qualityReport = quality)

            if (!quality.isTranscribable) {
                snapshot.value = snapshot.value.copy(
                    status = TranscriptionStatus.ERROR,
                    error = quality.rejectionReason
                )
                return
            }

            currentCoroutineContext().ensureActive()

            // Transcribe
            snapshot.value = snapshot.value.copy(
                status = TranscriptionStatus.TRANSCRIBING,
                progress = 0f
            )

            if (!OfflineSpeech.isModelInstalled(context)) {
                OfflineSpeech.installModel(context) { current, total ->
                    val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                    snapshot.value = snapshot.value.copy(progress = progress * 0.5f)
                }
            }

            currentCoroutineContext().ensureActive()
            snapshot.value = snapshot.value.copy(progress = 0.5f)
            val text = OfflineSpeech.transcribe(context, samples, sampleRate)

            currentCoroutineContext().ensureActive()
            snapshot.value = snapshot.value.copy(
                status = TranscriptionStatus.DONE,
                transcribedText = text,
                progress = 1f
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snapshot.value = snapshot.value.copy(
                status = TranscriptionStatus.ERROR,
                error = e.message ?: "Erro na transcrição"
            )
        }
    }

    private suspend fun getFileName(context: Context, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            var result: String? = null
            if (uri.scheme == "content") {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index != -1) {
                                result = cursor.getString(index)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (result == null) {
                result = uri.path?.substringAfterLast('/')
            }
            result
        }

    fun reset() {
        isRecording.value = false
        recordingDurationSec.value = 0
        recordingAmplitude.value = 0f
        activeJob?.cancel()
        recordingJob?.cancel()
        snapshot.value = TranscriptionSnapshot()
    }
}
