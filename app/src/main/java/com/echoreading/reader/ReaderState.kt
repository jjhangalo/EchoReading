package com.echoreading.reader

import android.content.Context
import android.content.Intent
import com.echoreading.util.sendCommand
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ReadingStatus { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

data class ReaderSnapshot(
    val text: String = "",
    val loadEpoch: Long = 0L,
    val status: ReadingStatus = ReadingStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val characterOffset: Int = 0,
    val voiceId: String = "pt-PT",
    val speed: Float = 1f,
    val error: String? = null,
    /**
     * Monotonic timestamp of [positionMs], in nanoseconds. The UI uses it to
     * anchor frame-clock interpolation without assuming that composition
     * happens at the exact moment the service sampled the player.
     */
    val positionUpdatedAtNanos: Long = 0L,
)

object ReaderState {
    val snapshot = MutableStateFlow(ReaderSnapshot())

    /**
     * Incremented whenever a screen explicitly loads new text. The playback
     * service captures this value at start; a stop/failure callback from an old
     * session must not overwrite the newly loaded state.
     */
    @Volatile
    var epoch: Long = 0L
        private set
    val loadTextEvent = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun loadText(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        // Bump before asking the service to stop so an in-flight stop/end
        // callback can detect that the visible session has already changed.
        epoch++

        if (snapshot.value.status == ReadingStatus.PLAYING ||
            snapshot.value.status == ReadingStatus.PREPARING ||
            snapshot.value.status == ReadingStatus.PAUSED) {
            try {
                val stopIntent = Intent(context, ReaderPlaybackService::class.java).apply {
                    action = ReaderPlaybackService.ACTION_STOP
                }
                context.startService(stopIntent)
            } catch (_: Exception) {
            }
        }

        snapshot.value = snapshot.value.copy(
            text = clean,
            loadEpoch = epoch,
            status = ReadingStatus.IDLE,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0,
            error = null,
            positionUpdatedAtNanos = System.nanoTime(),
        )

        try {
            context.getSharedPreferences("reading", Context.MODE_PRIVATE)
                .edit()
                .putString("draft", clean)
                .putString("text", clean)
                .putLong("position_ms", 0L)
                .putInt("character_offset", 0)
                .apply()
        } catch (_: Exception) {
        }

        loadTextEvent.tryEmit(clean)
    }

    fun setSpeed(context: Context, speed: Float) {
        val value = speed.coerceIn(0.5f, 3f)
        val current = snapshot.value
        if (current.speed == value) return
        snapshot.value = current.copy(speed = value, error = null)
        save(context)
        if (current.status.isActiveSession) {
            sendCommand(
                context,
                ReaderPlaybackService.ACTION_SPEED,
                positionMs = current.positionMs,
                speed = value,
            )
        }
    }

    fun setVoice(context: Context, voiceId: String) {
        val clean = voiceId.trim()
        if (clean.isEmpty()) return
        val current = snapshot.value
        snapshot.value = current.copy(voiceId = clean, error = null)
        save(context)
        if (current.status.isActiveSession) {
            dispatchVoice(context, clean, current.positionMs)
        }
    }

    /**
     * Re-apply the current voice id without changing it. Used when switching
     * between the offline and ElevenLabs provider for an active session.
     */
    fun refreshVoice(context: Context) {
        val current = snapshot.value
        if (current.status.isActiveSession) {
            dispatchVoice(context, current.voiceId, current.positionMs)
        }
    }

    private fun dispatchVoice(context: Context, voiceId: String, positionMs: Long) {
        sendCommand(
            context,
            ReaderPlaybackService.ACTION_VOICE,
            positionMs = positionMs,
            voiceId = voiceId,
        )
    }

    private val ReadingStatus.isActiveSession: Boolean
        get() = this == ReadingStatus.PLAYING ||
            this == ReadingStatus.PREPARING ||
            this == ReadingStatus.PAUSED

    fun restore(context: Context) {
        if (snapshot.value.text.isNotEmpty()) return
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        val savedVoice = prefs.getString("voice", null)
        val defaultVoice = if (savedVoice != null) {
            savedVoice
        } else {
            val installed = com.echoreading.voice.OfflineVoice.allVoices(context).firstOrNull { com.echoreading.voice.OfflineVoice.isInstalled(context, it) }
            installed?.id ?: "pt-PT"
        }
        snapshot.value = ReaderSnapshot(
            text = prefs.getString("text", "").orEmpty(),
            positionMs = prefs.getLong("position_ms", 0),
            characterOffset = prefs.getInt("character_offset", 0),
            voiceId = defaultVoice,
            speed = prefs.getFloat("speed", 1f),
        )
    }

    fun save(context: Context) {
        val state = snapshot.value
        context.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .edit()
            .putString("text", state.text)
            .putLong("position_ms", state.positionMs)
            .putInt("character_offset", state.characterOffset)
            .putString("voice", state.voiceId)
            .putFloat("speed", state.speed)
            .apply()
    }
}
