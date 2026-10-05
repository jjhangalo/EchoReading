package com.echoreading.reader

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ReadingStatus { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

data class ReaderSnapshot(
    val text: String = "",
    val status: ReadingStatus = ReadingStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val characterOffset: Int = 0,
    val voiceId: String = "pt-PT",
    val speed: Float = 1f,
    val error: String? = null,
)

object ReaderState {
    val snapshot = MutableStateFlow(ReaderSnapshot())
    val loadTextEvent = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun loadText(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

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
            status = ReadingStatus.IDLE,
            positionMs = 0L,
            durationMs = 0L,
            characterOffset = 0,
            error = null,
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
