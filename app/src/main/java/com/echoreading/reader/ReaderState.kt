package com.echoreading.reader

import android.content.Context
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

    fun restore(context: Context) {
        if (snapshot.value.text.isNotEmpty()) return
        val prefs = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
        snapshot.value = ReaderSnapshot(
            text = prefs.getString("text", "").orEmpty(),
            positionMs = prefs.getLong("position_ms", 0),
            characterOffset = prefs.getInt("character_offset", 0),
            voiceId = prefs.getString("voice", "pt-PT").orEmpty(),
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
