package com.echoreading.voice

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Downloads one voice in WorkManager so a 60 MB transfer survives the app
 * process being killed while the user is away from the originating screen.
 *
 * `OfflineVoice` writes to a `.part` file and only renames it after validation,
 * so a retry after process death always discards an incomplete transfer rather
 * than treating it as installed.
 */
class VoiceDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_VOICE)
            ?: return Result.failure(errorData("Pedido de transferência sem voz."))
        val label = inputData.getString(KEY_LABEL).orEmpty()
        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_LOCAL

        var lastProgressAt = 0L
        val onProgress: (copied: Long, total: Long) -> Unit = { copied, total ->
            val now = SystemClock.elapsedRealtime()
            // WorkManager progress writes are relatively expensive; keep the
            // UI smooth without writing on every network buffer.
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                (total > 0 && copied >= total)
            ) {
                lastProgressAt = now
                setProgressAsync(
                    workDataOf(
                        KEY_VOICE to key,
                        KEY_LABEL to label,
                        KEY_COPIED to copied,
                        KEY_TOTAL to total,
                    ),
                )
            }
        }

        return try {
            when (source) {
                SOURCE_CATALOG -> {
                    val catalog = VoiceCatalog.load(applicationContext)
                        .getOrElse { error ->
                            return retryOrFail(error)
                        }
                    val voice = catalog.firstOrNull { it.key == key }
                        ?: return Result.failure(
                            errorData("A voz $key já não está disponível no catálogo."),
                        )
                    OfflineVoice.installFromCatalog(applicationContext, voice, onProgress)
                }

                else -> {
                    val voice = OfflineVoice.allVoices(applicationContext)
                        .firstOrNull { it.id == key }
                        ?: return Result.failure(
                            errorData("A voz $key já não está disponível neste dispositivo."),
                        )
                    OfflineVoice.install(applicationContext, voice, onProgress)
                }
            }

            OfflineVoice.invalidateDiscoveryCache()
            Result.success(
                workDataOf(
                    KEY_VOICE to key,
                    KEY_LABEL to label,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            retryOrFail(error)
        }
    }

    private fun retryOrFail(error: Throwable): Result {
        val message = error.message ?: "Falha na transferência."
        return if (error is IOException && runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure(errorData(message))
        }
    }

    private fun errorData(message: String) = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_VOICE = "voice"
        const val KEY_LABEL = "label"
        const val KEY_SOURCE = "source"
        const val KEY_COPIED = "copied"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        const val SOURCE_LOCAL = "local"
        const val SOURCE_CATALOG = "catalog"

        private const val MAX_ATTEMPTS = 3
        private const val PROGRESS_INTERVAL_MS = 250L
    }
}
