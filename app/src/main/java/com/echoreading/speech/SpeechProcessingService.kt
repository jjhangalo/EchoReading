package com.echoreading.speech

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.echoreading.MainActivity
import com.echoreading.R
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class SpeechProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    @Volatile private var keepRecording = false
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SpeechToTextState.restore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            resumeCheckpoint()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START_RECORDING -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return stopInvalid()
                if (activeJob?.isActive == true) return START_STICKY
                startRecording(modelId)
            }
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_TRANSCRIBE_URI -> {
                val uri = intent.data ?: return stopInvalid()
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return stopInvalid()
                if (activeJob?.isActive == true) return START_STICKY
                startUriTranscription(uri, modelId)
            }
            ACTION_DOWNLOAD_MODEL -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return stopInvalid()
                if (activeJob?.isActive == true) return START_STICKY
                startDownload(modelId)
            }
            ACTION_PAUSE -> pauseCurrent()
            ACTION_RESUME -> resumeCheckpoint()
            ACTION_CANCEL -> cancelCurrent()
        }
        return START_STICKY
    }

    private fun startRecording(modelId: String) {
        val id = UUID.randomUUID().toString()
        val dir = jobDirectory(id).apply { mkdirs() }
        val pcm = File(dir, "recording.pcm")
        saveCheckpoint(
            kind = KIND_RECORDING,
            jobId = id,
            modelId = modelId,
            pcmPath = pcm.absolutePath,
            fileName = "Gravação de voz",
        )
        startWorkForeground("A gravar voz", "Toque para regressar à aplicação", TYPE_MICROPHONE, recording = true)
        acquireWakeLock()
        activeJob = scope.launch {
            try {
                recordToFile(id, modelId, pcm)
            } catch (_: CancellationException) {
            } catch (error: LinkageError) {
                fail(id, modelId, "Gravação de voz", "Motor de transcrição indisponível: ${error.message.orEmpty()}")
            } catch (error: Exception) {
                fail(id, modelId, "Gravação de voz", error.message ?: "Erro na gravação")
            } finally {
                releaseRecorder()
            }
        }
    }

    private suspend fun recordToFile(jobId: String, modelId: String, pcm: File) {
        require(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Permissão de gravação de áudio necessária" }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(AudioDecoder.SAMPLE_RATE, channel, encoding)
        require(minimum > 0) { "Microfone não disponível" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AudioDecoder.SAMPLE_RATE,
            channel,
            encoding,
            maxOf(minimum * 2, 4096),
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microfone não disponível" }
        audioRecord = recorder
        keepRecording = true
        recorder.startRecording()
        SpeechToTextState.publish(
            this,
            TranscriptionSnapshot(
                status = TranscriptionStatus.RECORDING,
                jobId = jobId,
                modelId = modelId,
                audioFileName = "Gravação de voz",
            ),
        )

        var sampleCount = 0L
        val buffer = ShortArray(2048)
        BufferedOutputStream(FileOutputStream(pcm)).use { output ->
            while (keepRecording && sampleCount < MAX_SAMPLES) {
                currentCoroutineContext().ensureActive()
                val read = recorder.read(buffer, 0, buffer.size)
                if (!keepRecording) break
                require(read >= 0) { "Falha ao ler o microfone ($read)" }
                if (read == 0) continue
                var peak = 0
                repeat(read) { index ->
                    val value = buffer[index].toInt()
                    peak = maxOf(peak, abs(value))
                    output.write(value and 0xff)
                    output.write((value ushr 8) and 0xff)
                }
                sampleCount += read
                val seconds = (sampleCount / AudioDecoder.SAMPLE_RATE).toInt()
                SpeechToTextState.updateRecording(seconds, (peak / 32768f).coerceIn(0f, 1f))
                if (sampleCount % AudioDecoder.SAMPLE_RATE < buffer.size) {
                    updateNotification("A gravar voz", formatDuration(seconds), recording = true)
                }
            }
            output.flush()
        }
        releaseRecorder()
        require(sampleCount > 0) { "transcribe_error_silent" }
        val duration = sampleCount * 1000L / AudioDecoder.SAMPLE_RATE
        saveCheckpoint(
            kind = KIND_TRANSCRIPTION,
            jobId = jobId,
            modelId = modelId,
            pcmPath = pcm.absolutePath,
            fileName = "Gravação de voz",
            durationMs = duration,
        )
        startWorkForeground("A transcrever", "A verificar a gravação", processingType())
        processPcm(jobId, modelId, pcm, "Gravação de voz", duration, 0, "")
    }

    private fun startUriTranscription(uri: Uri, modelId: String) {
        val id = UUID.randomUUID().toString()
        val fileName = getFileName(uri) ?: "audio"
        val dir = jobDirectory(id).apply { mkdirs() }
        val source = File(dir, "source")
        val pcm = File(dir, "audio.pcm")
        saveCheckpoint(KIND_COPY, id, modelId, source.absolutePath, pcm.absolutePath, fileName)
        startWorkForeground("A preparar áudio", fileName, processingType())
        acquireWakeLock()
        activeJob = scope.launch {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(source).buffered().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: error("Não foi possível abrir o ficheiro de áudio")
                saveCheckpoint(KIND_DECODING, id, modelId, source.absolutePath, pcm.absolutePath, fileName)
                SpeechToTextState.publish(
                    this@SpeechProcessingService,
                    TranscriptionSnapshot(
                        status = TranscriptionStatus.DECODING,
                        jobId = id,
                        modelId = modelId,
                        audioFileName = fileName,
                    ),
                )
                val decoded = AudioDecoder.decodeToPcm(
                    this@SpeechProcessingService,
                    Uri.fromFile(source),
                    pcm,
                ) { progress ->
                    SpeechToTextState.publish(
                        this@SpeechProcessingService,
                        SpeechToTextState.snapshot.value.copy(progress = progress * 0.2f),
                    )
                    updateNotification("A descodificar áudio", "${(progress * 100).toInt()}%")
                }
                source.delete()
                saveCheckpoint(
                    KIND_TRANSCRIPTION,
                    id,
                    modelId,
                    pcmPath = pcm.absolutePath,
                    fileName = fileName,
                    durationMs = decoded.durationMs,
                )
                processPcm(id, modelId, pcm, fileName, decoded.durationMs, 0, "")
            } catch (_: CancellationException) {
            } catch (error: LinkageError) {
                fail(id, modelId, fileName, "Motor de transcrição indisponível: ${error.message.orEmpty()}")
            } catch (error: Exception) {
                fail(id, modelId, fileName, error.message ?: "Erro ao processar o áudio")
            }
        }
    }

    private suspend fun processPcm(
        jobId: String,
        modelId: String,
        pcm: File,
        fileName: String,
        durationMs: Long,
        startChunk: Int,
        savedText: String,
    ) {
        require(OfflineSpeech.isModelInstalled(this, modelId)) { "Modelo de transcrição não instalado" }
        val totalSamples = pcm.length() / 2L
        require(totalSamples > 0) { "transcribe_error_silent" }
        require(totalSamples <= MAX_SAMPLES) { "O áudio excede o limite de 30 minutos" }
        val totalChunks = ((totalSamples + AudioDecoder.CHUNK_SAMPLES - 1) / AudioDecoder.CHUNK_SAMPLES).toInt()
        val transcript = StringBuilder(savedText.trim())
        var lastRejection: String? = null

        for (chunkIndex in startChunk until totalChunks) {
            currentCoroutineContext().ensureActive()
            val samples = AudioDecoder.readChunk(pcm, chunkIndex)
            SpeechToTextState.publish(
                this,
                TranscriptionSnapshot(
                    status = TranscriptionStatus.CHECKING_QUALITY,
                    jobId = jobId,
                    modelId = modelId,
                    audioFileName = fileName,
                    audioDurationMs = durationMs,
                    processedDurationMs = chunkIndex * 30_000L,
                    transcribedText = transcript.toString(),
                    progress = chunkIndex.toFloat() / totalChunks,
                ),
            )
            val quality = AudioQualityChecker.analyze(samples, AudioDecoder.SAMPLE_RATE)
            if (!quality.isTranscribable) {
                lastRejection = quality.rejectionReason
            } else {
                val progress = chunkIndex.toFloat() / totalChunks
                SpeechToTextState.publish(
                    this,
                    SpeechToTextState.snapshot.value.copy(
                        status = TranscriptionStatus.TRANSCRIBING,
                        qualityReport = quality,
                        progress = progress,
                    ),
                )
                updateNotification("A transcrever", "Bloco ${chunkIndex + 1} de $totalChunks")
                val text = OfflineSpeech.transcribe(this, modelId, samples)
                currentCoroutineContext().ensureActive()
                if (text.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append(' ')
                    transcript.append(text)
                }
            }
            val nextChunk = chunkIndex + 1
            val processedMs = minOf(durationMs, nextChunk * 30_000L)
            saveCheckpoint(
                KIND_TRANSCRIPTION,
                jobId,
                modelId,
                pcmPath = pcm.absolutePath,
                fileName = fileName,
                durationMs = durationMs,
                nextChunk = nextChunk,
                text = transcript.toString(),
            )
            SpeechToTextState.publish(
                this,
                SpeechToTextState.snapshot.value.copy(
                    status = TranscriptionStatus.TRANSCRIBING,
                    processedDurationMs = processedMs,
                    transcribedText = transcript.toString(),
                    progress = nextChunk.toFloat() / totalChunks,
                ),
            )
        }

        val result = transcript.toString().trim()
        require(result.isNotEmpty()) { lastRejection ?: "transcribe_error_no_speech" }
        SpeechToTextState.publish(
            this,
            TranscriptionSnapshot(
                status = TranscriptionStatus.DONE,
                jobId = jobId,
                modelId = modelId,
                audioFileName = fileName,
                audioDurationMs = durationMs,
                processedDurationMs = durationMs,
                transcribedText = result,
                progress = 1f,
            ),
        )
        clearCheckpoint()
        jobDirectory(jobId).deleteRecursively()
        showFinishedNotification("Transcrição concluída", fileName)
        finishWork()
    }

    private fun startDownload(modelId: String) {
        val model = OfflineSpeech.option(modelId)
        saveCheckpoint(KIND_DOWNLOAD, modelId = modelId)
        startWorkForeground("A descarregar ${model.label}", "0%", TYPE_DATA_SYNC)
        acquireWakeLock()
        activeJob = scope.launch {
            try {
                SpeechToTextState.publishDownload(
                    this@SpeechProcessingService,
                    ModelDownloadSnapshot(ModelDownloadStatus.DOWNLOADING, modelId, totalBytes = model.downloadBytes),
                )
                OfflineSpeech.installModel(this@SpeechProcessingService, modelId) { copied, total ->
                    SpeechToTextState.publishDownload(
                        this@SpeechProcessingService,
                        ModelDownloadSnapshot(ModelDownloadStatus.DOWNLOADING, modelId, copied, total),
                    )
                    updateNotification("A descarregar ${model.label}", "${(copied * 100L / total.coerceAtLeast(1)).toInt()}%", pause = true)
                }
                OfflineSpeech.selectModel(this@SpeechProcessingService, modelId)
                SpeechToTextState.publishDownload(
                    this@SpeechProcessingService,
                    ModelDownloadSnapshot(ModelDownloadStatus.DONE, modelId, model.downloadBytes, model.downloadBytes),
                )
                clearCheckpoint()
                showFinishedNotification("Modelo pronto", "${model.label} instalado e selecionado")
                finishWork()
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                SpeechToTextState.publishDownload(
                    this@SpeechProcessingService,
                    ModelDownloadSnapshot(
                        ModelDownloadStatus.ERROR,
                        modelId,
                        SpeechToTextState.download.value.copiedBytes,
                        model.downloadBytes,
                        error.message ?: "Falha no download",
                    ),
                )
                showFinishedNotification("Falha no download", error.message ?: "Tente novamente")
                finishWork(clearSavedJob = false)
            }
        }
    }

    private fun pauseCurrent() {
        val checkpoint = checkpoint()
        activeJob?.cancel()
        activeJob = null
        keepRecording = false
        releaseRecorder()
        if (checkpoint.kind == KIND_DOWNLOAD) {
            val model = OfflineSpeech.option(checkpoint.modelId)
            SpeechToTextState.publishDownload(
                this,
                ModelDownloadSnapshot(
                    ModelDownloadStatus.PAUSED,
                    checkpoint.modelId,
                    SpeechToTextState.download.value.copiedBytes,
                    model.downloadBytes,
                ),
            )
        } else if (checkpoint.jobId.isNotBlank()) {
            SpeechToTextState.publish(this, SpeechToTextState.snapshot.value.copy(status = TranscriptionStatus.PAUSED))
        }
        finishWork(clearSavedJob = false)
    }

    private fun cancelCurrent() {
        val saved = checkpoint()
        activeJob?.cancel()
        activeJob = null
        keepRecording = false
        releaseRecorder()
        if (saved.jobId.isNotBlank()) jobDirectory(saved.jobId).deleteRecursively()
        clearCheckpoint()
        SpeechToTextState.publish(this, TranscriptionSnapshot())
        SpeechToTextState.publishDownload(this, ModelDownloadSnapshot())
        finishWork()
    }

    private fun resumeCheckpoint() {
        if (activeJob?.isActive == true) return
        val saved = checkpoint()
        when (saved.kind) {
            KIND_DOWNLOAD -> if (saved.modelId.isNotBlank()) startDownload(saved.modelId) else stopSelf()
            KIND_TRANSCRIPTION -> {
                val pcm = File(saved.pcmPath)
                if (!pcm.isFile) {
                    fail(saved.jobId, saved.modelId, saved.fileName, "O áudio temporário já não está disponível")
                    return
                }
                startWorkForeground("A retomar transcrição", saved.fileName, processingType())
                acquireWakeLock()
                activeJob = scope.launch {
                    try {
                        processPcm(
                            saved.jobId,
                            saved.modelId,
                            pcm,
                            saved.fileName,
                            saved.durationMs,
                            saved.nextChunk,
                            saved.text,
                        )
                    } catch (_: CancellationException) {
                    } catch (error: LinkageError) {
                        fail(saved.jobId, saved.modelId, saved.fileName, "Motor de transcrição indisponível: ${error.message.orEmpty()}")
                    } catch (error: Exception) {
                        fail(saved.jobId, saved.modelId, saved.fileName, error.message ?: "Erro na transcrição")
                    }
                }
            }
            KIND_DECODING -> {
                val source = File(saved.sourcePath)
                if (!source.isFile) {
                    fail(saved.jobId, saved.modelId, saved.fileName, "O ficheiro de origem já não está disponível")
                    return
                }
                val pcm = File(saved.pcmPath)
                startWorkForeground("A retomar descodificação", saved.fileName, processingType())
                acquireWakeLock()
                activeJob = scope.launch {
                    try {
                        val decoded = AudioDecoder.decodeToPcm(
                            this@SpeechProcessingService,
                            Uri.fromFile(source),
                            pcm,
                        )
                        source.delete()
                        saveCheckpoint(
                            KIND_TRANSCRIPTION,
                            saved.jobId,
                            saved.modelId,
                            pcmPath = pcm.absolutePath,
                            fileName = saved.fileName,
                            durationMs = decoded.durationMs,
                        )
                        processPcm(
                            saved.jobId,
                            saved.modelId,
                            pcm,
                            saved.fileName,
                            decoded.durationMs,
                            0,
                            "",
                        )
                    } catch (_: CancellationException) {
                    } catch (error: LinkageError) {
                        fail(saved.jobId, saved.modelId, saved.fileName, "Motor de transcrição indisponível: ${error.message.orEmpty()}")
                    } catch (error: Exception) {
                        fail(saved.jobId, saved.modelId, saved.fileName, error.message ?: "Erro ao descodificar")
                    }
                }
            }
            KIND_RECORDING -> {
                val pcm = File(saved.pcmPath)
                if (pcm.isFile && pcm.length() > 0) {
                    val duration = pcm.length() / 2L * 1000L / AudioDecoder.SAMPLE_RATE
                    saveCheckpoint(
                        KIND_TRANSCRIPTION,
                        saved.jobId,
                        saved.modelId,
                        pcmPath = pcm.absolutePath,
                        fileName = saved.fileName,
                        durationMs = duration,
                    )
                    resumeCheckpoint()
                } else stopSelf()
            }
            else -> stopSelf()
        }
    }

    private fun stopRecording() {
        keepRecording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    private fun fail(jobId: String, modelId: String, fileName: String, message: String) {
        SpeechToTextState.publish(
            this,
            TranscriptionSnapshot(
                status = TranscriptionStatus.ERROR,
                jobId = jobId,
                modelId = modelId,
                audioFileName = fileName,
                error = message,
            ),
        )
        showFinishedNotification("Erro na transcrição", readableError(message))
        finishWork(clearSavedJob = false)
    }

    private fun readableError(message: String): String = when (message) {
        "transcribe_error_silent" -> "O áudio está em silêncio"
        "transcribe_error_no_speech" -> "Não foi detetada fala"
        "transcribe_error_noisy" -> "O áudio tem demasiado ruído"
        "transcribe_error_low_volume" -> "O volume do áudio é demasiado baixo"
        "transcribe_error_clipped" -> "O áudio está distorcido"
        else -> message
    }

    private fun startWorkForeground(
        title: String,
        text: String,
        type: Int,
        recording: Boolean = false,
    ) {
        val notification = buildNotification(title, text, recording = recording)
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification(
        title: String,
        text: String,
        recording: Boolean = false,
        pause: Boolean = false,
    ) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(title, text, recording, pause))
    }

    private fun buildNotification(
        title: String,
        text: String,
        recording: Boolean = false,
        pause: Boolean = false,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = when {
            recording -> ACTION_STOP_RECORDING
            pause -> ACTION_PAUSE
            else -> ACTION_CANCEL
        }
        val actionLabel = when {
            recording -> "Parar e transcrever"
            pause -> "Pausar"
            else -> "Cancelar"
        }
        val actionIntent = PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, SpeechProcessingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speak)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_speak),
                    actionLabel,
                    actionIntent,
                ).build(),
            )
            .build()
    }

    private fun showFinishedNotification(title: String, text: String) {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speak)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transcrição e modelos", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:speech-processing")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        if (lock?.isHeld == true) lock.release()
        wakeLock = null
    }

    private fun releaseRecorder() {
        val recorder = audioRecord
        audioRecord = null
        try { if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        SpeechToTextState.updateRecording(SpeechToTextState.recordingDurationSec.value, 0f)
    }

    private fun finishWork(clearSavedJob: Boolean = true) {
        if (clearSavedJob) clearCheckpoint()
        releaseWakeLock()
        activeJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processingType(): Int = if (Build.VERSION.SDK_INT >= 35) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) return cursor.getString(0)
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }

    private fun jobDirectory(id: String) = File(noBackupFilesDir, "stt-jobs/$id")

    private fun saveCheckpoint(
        kind: String,
        jobId: String = "",
        modelId: String,
        sourcePath: String = "",
        pcmPath: String = "",
        fileName: String = "",
        durationMs: Long = 0,
        nextChunk: Int = 0,
        text: String = "",
    ) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind", kind)
            .putString("job_id", jobId)
            .putString("model_id", modelId)
            .putString("source_path", sourcePath)
            .putString("pcm_path", pcmPath)
            .putString("file_name", fileName)
            .putLong("duration_ms", durationMs)
            .putInt("next_chunk", nextChunk)
            .putString("text", text)
            .commit()
    }

    private data class Checkpoint(
        val kind: String,
        val jobId: String,
        val modelId: String,
        val sourcePath: String,
        val pcmPath: String,
        val fileName: String,
        val durationMs: Long,
        val nextChunk: Int,
        val text: String,
    )

    private fun checkpoint(): Checkpoint {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Checkpoint(
            kind = prefs.getString("kind", "").orEmpty(),
            jobId = prefs.getString("job_id", "").orEmpty(),
            modelId = prefs.getString("model_id", "").orEmpty(),
            sourcePath = prefs.getString("source_path", "").orEmpty(),
            pcmPath = prefs.getString("pcm_path", "").orEmpty(),
            fileName = prefs.getString("file_name", "").orEmpty(),
            durationMs = prefs.getLong("duration_ms", 0),
            nextChunk = prefs.getInt("next_chunk", 0),
            text = prefs.getString("text", "").orEmpty(),
        )
    }

    private fun clearCheckpoint() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun formatDuration(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)

    private fun stopInvalid(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val saved = checkpoint()
        fail(saved.jobId, saved.modelId, saved.fileName, "O processamento excedeu o limite do Android")
    }

    override fun onDestroy() {
        keepRecording = false
        releaseRecorder()
        releaseWakeLock()
        scope.cancel()
        OfflineSpeech.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_RECORDING = "com.echoreading.speech.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.echoreading.speech.STOP_RECORDING"
        const val ACTION_TRANSCRIBE_URI = "com.echoreading.speech.TRANSCRIBE_URI"
        const val ACTION_DOWNLOAD_MODEL = "com.echoreading.speech.DOWNLOAD_MODEL"
        const val ACTION_PAUSE = "com.echoreading.speech.PAUSE"
        const val ACTION_RESUME = "com.echoreading.speech.RESUME"
        const val ACTION_CANCEL = "com.echoreading.speech.CANCEL"
        const val EXTRA_MODEL_ID = "model_id"

        private const val PREFS = "speech-job"
        private const val KIND_RECORDING = "recording"
        private const val KIND_COPY = "copy"
        private const val KIND_DECODING = "decoding"
        private const val KIND_TRANSCRIPTION = "transcription"
        private const val KIND_DOWNLOAD = "download"
        private const val CHANNEL_ID = "eco-speech-processing"
        private const val NOTIFICATION_ID = 1201
        private const val RESULT_NOTIFICATION_ID = 1202
        private const val TYPE_MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        private const val TYPE_DATA_SYNC = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        private const val MAX_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L
        private const val MAX_SAMPLES = AudioDecoder.SAMPLE_RATE.toLong() * 30L * 60L
    }
}
