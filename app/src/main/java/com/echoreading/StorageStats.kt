package com.echoreading

import android.content.Context
import com.echoreading.reader.ReadingHistory
import com.echoreading.speech.OfflineSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageStats(
    val voiceBytes: Long = 0L,
    val speechBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val historyBytes: Long = 0L,
) {
    val totalBytes: Long get() = voiceBytes + speechBytes + cacheBytes + historyBytes
}

/**
 * Recursive disk walks are deliberately kept off the composition/main thread.
 * Settings opens often; walking voice and cache trees synchronously causes
 * visible jank on devices with several installed models.
 */
suspend fun loadStorageStats(context: Context): StorageStats = withContext(Dispatchers.IO) {
    val voices = File(context.noBackupFilesDir, "voices")
    StorageStats(
        voiceBytes = voices.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        speechBytes = OfflineSpeech.getModelSizeBytes(context),
        cacheBytes = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
        historyBytes = ReadingHistory.sizeBytes(context),
    )
}
