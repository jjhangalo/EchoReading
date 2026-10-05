package com.echoreading.speech

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class TranscriptionStatus {
    IDLE, MODEL_REQUIRED, RECORDING, DECODING, CHECKING_QUALITY, TRANSCRIBING, PAUSED, DONE, ERROR
}

data class TranscriptionSnapshot(
    val status: TranscriptionStatus = TranscriptionStatus.IDLE,
    val jobId: String = "",
    val modelId: String = "",
    val audioFileName: String = "",
    val audioDurationMs: Long = 0,
    val processedDurationMs: Long = 0,
    val transcribedText: String = "",
    val error: String? = null,
    val qualityReport: AudioQualityReport? = null,
    val progress: Float = 0f,
)

enum class ModelDownloadStatus { IDLE, DOWNLOADING, PAUSED, DONE, ERROR }

data class ModelDownloadSnapshot(
    val status: ModelDownloadStatus = ModelDownloadStatus.IDLE,
    val modelId: String = "",
    val copiedBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

object SpeechToTextState {
    val snapshot = MutableStateFlow(TranscriptionSnapshot())
    val download = MutableStateFlow(ModelDownloadSnapshot())
    val modelsRevision = MutableStateFlow(0)
    val selectedModelId = MutableStateFlow<String?>(null)
    val loadAudioEvent = MutableSharedFlow<Uri>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pendingAudioUri = MutableStateFlow<Uri?>(null)
    val isRecording = MutableStateFlow(false)
    val recordingDurationSec = MutableStateFlow(0)
    val recordingAmplitude = MutableStateFlow(0f)

    fun restore(context: Context) {
        selectedModelId.value = OfflineSpeech.selectedModelId(context)
        val prefs = context.getSharedPreferences("speech-work", Context.MODE_PRIVATE)
        val status = prefs.getString("status", null)?.let {
            runCatching { TranscriptionStatus.valueOf(it) }.getOrNull()
        } ?: TranscriptionStatus.IDLE
        val restoredStatus = if (status in setOf(
                TranscriptionStatus.RECORDING,
                TranscriptionStatus.DECODING,
                TranscriptionStatus.CHECKING_QUALITY,
                TranscriptionStatus.TRANSCRIBING,
            )
        ) TranscriptionStatus.PAUSED else status
        snapshot.value = TranscriptionSnapshot(
            status = restoredStatus,
            jobId = prefs.getString("job_id", "").orEmpty(),
            modelId = prefs.getString("model_id", "").orEmpty(),
            audioFileName = prefs.getString("file_name", "").orEmpty(),
            audioDurationMs = prefs.getLong("duration_ms", 0),
            processedDurationMs = prefs.getLong("processed_ms", 0),
            transcribedText = prefs.getString("text", "").orEmpty(),
            error = prefs.getString("error", null),
            progress = prefs.getFloat("progress", 0f),
        )
    }

    fun startRecording(context: Context) {
        val model = OfflineSpeech.selectedModelId(context)
        if (model == null) {
            snapshot.value = TranscriptionSnapshot(
                status = TranscriptionStatus.MODEL_REQUIRED,
                error = "Descarregue e selecione um modelo de transcrição primeiro",
            )
            return
        }
        start(context, SpeechProcessingService.ACTION_START_RECORDING) {
            putExtra(SpeechProcessingService.EXTRA_MODEL_ID, model)
        }
    }

    fun stopRecording(context: Context) =
        start(context, SpeechProcessingService.ACTION_STOP_RECORDING)

    fun transcribe(context: Context, audioUri: Uri) {
        val model = OfflineSpeech.selectedModelId(context)
        if (model == null) {
            snapshot.value = TranscriptionSnapshot(
                status = TranscriptionStatus.MODEL_REQUIRED,
                error = "Descarregue e selecione um modelo de transcrição primeiro",
            )
            return
        }
        start(context, SpeechProcessingService.ACTION_TRANSCRIBE_URI) {
            data = audioUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(SpeechProcessingService.EXTRA_MODEL_ID, model)
        }
    }

    fun downloadModel(context: Context, modelId: String) =
        start(context, SpeechProcessingService.ACTION_DOWNLOAD_MODEL) {
            putExtra(SpeechProcessingService.EXTRA_MODEL_ID, modelId)
        }

    fun pauseDownload(context: Context) = start(context, SpeechProcessingService.ACTION_PAUSE)

    fun resume(context: Context) = start(context, SpeechProcessingService.ACTION_RESUME)

    fun cancel(context: Context) = start(context, SpeechProcessingService.ACTION_CANCEL)

    fun selectModel(context: Context, modelId: String): Boolean {
        val selected = OfflineSpeech.selectModel(context, modelId)
        if (selected) {
            selectedModelId.value = modelId
            modelsRevision.value++
        }
        return selected
    }

    fun deleteModel(context: Context, modelId: String): Boolean {
        if (download.value.modelId == modelId && download.value.status == ModelDownloadStatus.DOWNLOADING) {
            return false
        }
        val deleted = OfflineSpeech.deleteModel(context, modelId)
        selectedModelId.value = OfflineSpeech.selectedModelId(context)
        modelsRevision.value++
        return deleted
    }

    fun deleteAllModels(context: Context): Boolean {
        val deleted = OfflineSpeech.deleteModel(context)
        selectedModelId.value = null
        modelsRevision.value++
        return deleted
    }

    internal fun publish(context: Context, value: TranscriptionSnapshot) {
        snapshot.value = value
        isRecording.value = value.status == TranscriptionStatus.RECORDING
        context.getSharedPreferences("speech-work", Context.MODE_PRIVATE).edit()
            .putString("status", value.status.name)
            .putString("job_id", value.jobId)
            .putString("model_id", value.modelId)
            .putString("file_name", value.audioFileName)
            .putLong("duration_ms", value.audioDurationMs)
            .putLong("processed_ms", value.processedDurationMs)
            .putString("text", value.transcribedText)
            .putString("error", value.error)
            .putFloat("progress", value.progress)
            .apply()
    }

    internal fun publishDownload(context: Context, value: ModelDownloadSnapshot) {
        download.value = value
        if (value.status == ModelDownloadStatus.DONE) {
            selectedModelId.value = OfflineSpeech.selectedModelId(context)
            modelsRevision.value++
        }
    }

    internal fun updateRecording(durationSec: Int, amplitude: Float) {
        recordingDurationSec.value = durationSec
        recordingAmplitude.value = amplitude
    }

    fun reset() {
        isRecording.value = false
        recordingDurationSec.value = 0
        recordingAmplitude.value = 0f
        snapshot.value = TranscriptionSnapshot()
    }

    private fun start(
        context: Context,
        actionName: String,
        configure: Intent.() -> Unit = {},
    ) {
        val intent = Intent(context, SpeechProcessingService::class.java).apply {
            action = actionName
            configure()
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
