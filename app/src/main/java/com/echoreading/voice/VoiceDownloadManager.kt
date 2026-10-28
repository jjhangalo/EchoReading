package com.echoreading.voice

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class VoiceDownloadStatus { IDLE, DOWNLOADING, SUCCESS, ERROR }

/** Selects how the worker reconstructs the requested voice from its key. */
enum class VoiceDownloadSource { LOCAL, CATALOG }

/** Result of asking the manager to transfer a voice. */
enum class VoiceDownloadStartResult { STARTED, QUEUED, DUPLICATE }

/** One voice waiting for the active transfer to finish. */
data class VoiceDownloadQueueItem(
    val key: String,
    val label: String,
    val source: VoiceDownloadSource,
)

/** A transfer that finished; screens acknowledge it after showing the result. */
data class VoiceDownloadCompletion(
    val requestId: Long,
    val key: String,
    val label: String,
    val success: Boolean,
    val error: String? = null,
)

data class VoiceDownloadState(
    val requestId: Long = 0L,
    val key: String? = null,
    val label: String? = null,
    val status: VoiceDownloadStatus = VoiceDownloadStatus.IDLE,
    val progress: Float = 0f,
    val copiedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val queued: List<VoiceDownloadQueueItem> = emptyList(),
    val completions: List<VoiceDownloadCompletion> = emptyList(),
)

/**
 * Adds [item] to the end of the queue, rejecting the active voice and any
 * duplicate key. Returns null when the request is already pending.
 */
internal fun VoiceDownloadState.enqueueQueuedVoice(
    item: VoiceDownloadQueueItem,
): VoiceDownloadState? {
    if (status == VoiceDownloadStatus.DOWNLOADING && key == item.key) return null
    if (queued.any { it.key == item.key }) return null
    return copy(queued = queued + item)
}

/** Removes a waiting voice; returns null when it was not in the queue. */
internal fun VoiceDownloadState.removeQueuedVoice(key: String): VoiceDownloadState? {
    val nextQueue = queued.filterNot { it.key == key }
    if (nextQueue.size == queued.size) return null
    return copy(queued = nextQueue)
}

/**
 * WorkManager-backed voice transfer coordinator with a persistent FIFO queue.
 *
 * Only one [VoiceDownloadWorker] runs at a time. Starting another voice while
 * one is active appends it to [VoiceDownloadState.queued]; the next item starts
 * automatically when the active worker succeeds, fails, or is cancelled. The
 * queue and terminal results are stored in SharedPreferences so process death
 * does not lose either.
 */
object VoiceDownloadManager {
    private const val WORK_NAME = "voice-download"
    private const val PREFS = "voice_download"
    private const val PREF_WORK_ID = "work_id"
    private const val PREF_KEY = "key"
    private const val PREF_LABEL = "label"
    private const val PREF_ACKNOWLEDGED = "acknowledged_work_id"
    private const val PREF_QUEUE = "queue"
    private const val PREF_COMPLETIONS = "completions"
    private const val MAX_COMPLETIONS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(VoiceDownloadState())
    val state: StateFlow<VoiceDownloadState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var observer: Job? = null
    private var activeWorkId: String? = null
    private var initialized = false

    /**
     * Starts observing durable work. Call this from Activity/Application start
     * so an in-flight transfer and its pending queue are visible after the
     * process is recreated.
     */
    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        val prefs = prefs(app)
        clearLegacyAcknowledgedWork(prefs)

        val activeId = prefs.getString(PREF_WORK_ID, null)
        val queue = readQueue(app)
        val completions = readCompletions(app)
        val base = VoiceDownloadState(queued = queue, completions = completions)
        _state.value = if (activeId == null) {
            base
        } else {
            base.copy(
                requestId = activeId.toStableId(),
                key = prefs.getString(PREF_KEY, null),
                label = prefs.getString(PREF_LABEL, null),
                status = VoiceDownloadStatus.DOWNLOADING,
            )
        }

        observer = scope.launch {
            WorkManager.getInstance(app)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .collect { infos ->
                    val pendingId = prefs(app).getString(PREF_WORK_ID, null)
                    val workId = pendingId ?: activeWorkId
                    if (workId == null) {
                        if (_state.value.status != VoiceDownloadStatus.DOWNLOADING &&
                            _state.value.queued.isNotEmpty()
                        ) {
                            startNext(app)
                        }
                        return@collect
                    }
                    activeWorkId = workId
                    val info = infos.firstOrNull { it.id.toString() == workId }
                    // The enqueue operation can be visible in preferences
                    // before WorkManager emits the new WorkInfo. Keep the
                    // optimistic state instead of falling back to old work.
                    if (info == null) return@collect
                    if (info.state.isFinished) {
                        finishActive(app, info)
                    } else {
                        publishActive(app, info)
                    }
                }
        }

        if (activeId == null && queue.isNotEmpty()) {
            startNext(app)
        }
    }

    /**
     * Starts [key], or puts it in the queue when another voice is active or
     * already pending. Comparing against the whole queue makes repeated taps
     * harmless.
     */
    @Synchronized
    fun start(
        context: Context,
        key: String,
        label: String,
        source: VoiceDownloadSource,
    ): VoiceDownloadStartResult {
        initialize(context)
        val app = context.applicationContext
        val current = _state.value
        val item = VoiceDownloadQueueItem(key = key, label = label, source = source)
        val updated = current.enqueueQueuedVoice(item)
            ?: return VoiceDownloadStartResult.DUPLICATE
        writeQueue(app, updated.queued)
        val busy = current.status == VoiceDownloadStatus.DOWNLOADING ||
            activeWorkId != null ||
            prefs(app).getString(PREF_WORK_ID, null) != null ||
            current.queued.isNotEmpty()
        _state.value = updated
        if (busy) return VoiceDownloadStartResult.QUEUED

        startNext(app)
        return if (_state.value.status == VoiceDownloadStatus.DOWNLOADING &&
            _state.value.key == key
        ) {
            VoiceDownloadStartResult.STARTED
        } else {
            VoiceDownloadStartResult.QUEUED
        }
    }

    /** Cancels only the active transfer; the next queued voice starts at once. */
    @Synchronized
    fun cancelActive() {
        val app = appContext ?: return
        val prefs = prefs(app)
        val workId = activeWorkId ?: prefs.getString(PREF_WORK_ID, null)
        if (workId == null && _state.value.status != VoiceDownloadStatus.DOWNLOADING) return

        activeWorkId = null
        if (workId != null) {
            runCatching {
                WorkManager.getInstance(app).cancelWorkById(UUID.fromString(workId))
            }
        }
        clearActivePrefs(prefs)
        _state.value = _state.value.copy(
            requestId = 0L,
            key = null,
            label = null,
            status = VoiceDownloadStatus.IDLE,
            progress = 0f,
            copiedBytes = 0L,
            totalBytes = 0L,
            error = null,
        )
        startNext(app)
    }

    /** Removes one waiting voice without touching the active transfer. */
    @Synchronized
    fun cancelQueued(key: String) {
        val app = appContext ?: return
        val current = _state.value
        val updated = current.removeQueuedVoice(key) ?: return
        writeQueue(app, updated.queued)
        _state.value = updated
    }

    /** Kept for callers that only know how to stop the current transfer. */
    fun cancel() = cancelActive()

    /** Lets the observing screen consume a terminal result exactly once. */
    @Synchronized
    fun acknowledge(requestId: Long) {
        val app = appContext ?: return
        val current = _state.value
        val remaining = current.completions.filterNot { it.requestId == requestId }
        if (remaining.size == current.completions.size) return
        writeCompletions(app, remaining)
        _state.value = current.copy(completions = remaining)
    }

    private fun startNext(app: Context) {
        val current = _state.value
        if (current.status == VoiceDownloadStatus.DOWNLOADING ||
            activeWorkId != null ||
            prefs(app).getString(PREF_WORK_ID, null) != null
        ) {
            return
        }
        val next = current.queued.firstOrNull() ?: return
        val remaining = current.queued.drop(1)
        val request = buildRequest(next)
        val prefs = prefs(app)

        prefs.edit()
            .putString(PREF_WORK_ID, request.id.toString())
            .putString(PREF_KEY, next.key)
            .putString(PREF_LABEL, next.label)
            .remove(PREF_ACKNOWLEDGED)
            .apply()
        writeQueue(app, remaining)
        activeWorkId = request.id.toString()
        _state.value = current.copy(
            requestId = request.id.stableId(),
            key = next.key,
            label = next.label,
            status = VoiceDownloadStatus.DOWNLOADING,
            progress = 0f,
            copiedBytes = 0L,
            totalBytes = 0L,
            error = null,
            queued = remaining,
        )
        WorkManager.getInstance(app)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun buildRequest(item: VoiceDownloadQueueItem) =
        OneTimeWorkRequestBuilder<VoiceDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    VoiceDownloadWorker.KEY_VOICE to item.key,
                    VoiceDownloadWorker.KEY_LABEL to item.label,
                    VoiceDownloadWorker.KEY_SOURCE to when (item.source) {
                        VoiceDownloadSource.LOCAL -> VoiceDownloadWorker.SOURCE_LOCAL
                        VoiceDownloadSource.CATALOG -> VoiceDownloadWorker.SOURCE_CATALOG
                    },
                ),
            )
            .addTag(WORK_NAME)
            .build()

    private fun publishActive(app: Context, info: WorkInfo) {
        val prefs = prefs(app)
        val key = prefs.getString(PREF_KEY, null)
            ?: info.progress.getString(VoiceDownloadWorker.KEY_VOICE)
            ?: info.outputData.getString(VoiceDownloadWorker.KEY_VOICE)
            ?: _state.value.key
        val label = prefs.getString(PREF_LABEL, null)
            ?: info.progress.getString(VoiceDownloadWorker.KEY_LABEL)
            ?: info.outputData.getString(VoiceDownloadWorker.KEY_LABEL)
            ?: _state.value.label
        val copied = info.progress.getLong(VoiceDownloadWorker.KEY_COPIED, 0L)
        val total = info.progress.getLong(VoiceDownloadWorker.KEY_TOTAL, 0L)
        _state.value = _state.value.copy(
            requestId = info.id.stableId(),
            key = key,
            label = label,
            status = VoiceDownloadStatus.DOWNLOADING,
            progress = if (total > 0) {
                (copied.toFloat() / total).coerceIn(0f, 1f)
            } else {
                0f
            },
            copiedBytes = copied,
            totalBytes = total,
            error = null,
        )
    }

    private fun finishActive(app: Context, info: WorkInfo) {
        val prefs = prefs(app)
        val requestId = info.id.stableId()
        val key = prefs.getString(PREF_KEY, null)
            ?: info.progress.getString(VoiceDownloadWorker.KEY_VOICE)
            ?: info.outputData.getString(VoiceDownloadWorker.KEY_VOICE)
            ?: _state.value.key
        val label = prefs.getString(PREF_LABEL, null)
            ?: info.progress.getString(VoiceDownloadWorker.KEY_LABEL)
            ?: info.outputData.getString(VoiceDownloadWorker.KEY_LABEL)
            ?: _state.value.label
            ?: key
        val current = _state.value
        var completions = current.completions
        if (info.state != WorkInfo.State.CANCELLED &&
            completions.none { it.requestId == requestId }
        ) {
            val completion = VoiceDownloadCompletion(
                requestId = requestId,
                key = key.orEmpty(),
                label = label.orEmpty(),
                success = info.state == WorkInfo.State.SUCCEEDED,
                error = if (info.state == WorkInfo.State.FAILED) {
                    info.outputData.getString(VoiceDownloadWorker.KEY_ERROR)
                        ?: "Falha na transferência"
                } else {
                    null
                },
            )
            completions = (completions + completion).takeLast(MAX_COMPLETIONS)
            writeCompletions(app, completions)
        }

        clearActivePrefs(prefs)
        activeWorkId = null
        _state.value = current.copy(
            requestId = 0L,
            key = null,
            label = null,
            status = VoiceDownloadStatus.IDLE,
            progress = 0f,
            copiedBytes = 0L,
            totalBytes = 0L,
            error = null,
            completions = completions,
        )
        startNext(app)
    }

    private fun clearActivePrefs(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_WORK_ID)
            .remove(PREF_KEY)
            .remove(PREF_LABEL)
            .remove(PREF_ACKNOWLEDGED)
            .apply()
    }

    /**
     * The old single-download implementation stored an acknowledged work id.
     * Treat it as already consumed while upgrading so the first launch after
     * the update does not replay an old toast/selection.
     */
    private fun clearLegacyAcknowledgedWork(prefs: SharedPreferences) {
        val activeId = prefs.getString(PREF_WORK_ID, null)
        val acknowledgedId = prefs.getString(PREF_ACKNOWLEDGED, null)
        if (activeId != null && activeId == acknowledgedId) {
            clearActivePrefs(prefs)
        }
    }

    private fun readQueue(context: Context): List<VoiceDownloadQueueItem> {
        val raw = prefs(context).getString(PREF_QUEUE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        VoiceDownloadQueueItem(
                            key = key,
                            label = item.optString("label").ifBlank { key },
                            source = sourceFromName(item.optString("source")),
                        ),
                    )
                }
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
    }

    private fun writeQueue(context: Context, queue: List<VoiceDownloadQueueItem>) {
        val array = JSONArray()
        queue.forEach { item ->
            array.put(
                JSONObject()
                    .put("key", item.key)
                    .put("label", item.label)
                    .put("source", item.source.name),
            )
        }
        prefs(context).edit().putString(PREF_QUEUE, array.toString()).apply()
    }

    private fun readCompletions(context: Context): List<VoiceDownloadCompletion> {
        val raw = prefs(context).getString(PREF_COMPLETIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val key = item.optString("key").takeIf { it.isNotBlank() }
                        ?: continue
                    add(
                        VoiceDownloadCompletion(
                            requestId = item.optLong("request_id"),
                            key = key,
                            label = item.optString("label").ifBlank { key },
                            success = item.optBoolean("success", false),
                            error = item.optString("error")
                                .takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }.distinctBy { it.requestId }
        }.getOrDefault(emptyList())
    }

    private fun writeCompletions(
        context: Context,
        completions: List<VoiceDownloadCompletion>,
    ) {
        val array = JSONArray()
        completions.forEach { completion ->
            val item = JSONObject()
                .put("request_id", completion.requestId)
                .put("key", completion.key)
                .put("label", completion.label)
                .put("success", completion.success)
            completion.error?.let { item.put("error", it) }
            array.put(item)
        }
        prefs(context).edit().putString(PREF_COMPLETIONS, array.toString()).apply()
    }

    private fun sourceFromName(raw: String): VoiceDownloadSource =
        VoiceDownloadSource.values().firstOrNull { it.name == raw }
            ?: VoiceDownloadSource.LOCAL

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun String.toStableId(): Long =
        runCatching { UUID.fromString(this).stableId() }.getOrDefault(0L)

    private fun UUID.stableId(): Long {
        val value = mostSignificantBits xor leastSignificantBits
        return if (value == 0L) 1L else value
    }
}
