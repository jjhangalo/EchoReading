package com.echoreading.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderState

fun sendCommand(
    context: Context,
    action: String,
    text: String? = null,
    positionMs: Long? = null,
    voiceId: String? = null,
    speed: Float? = null,
) {
    val intent = Intent(context, ReaderPlaybackService::class.java).setAction(action)
    if (text != null) {
        intent.putExtra(ReaderPlaybackService.EXTRA_TEXT, text)
    }
    if (text != null || voiceId != null) {
        intent.putExtra(
            ReaderPlaybackService.EXTRA_VOICE,
            voiceId ?: ReaderState.snapshot.value.voiceId,
        )
    }
    if (text != null || speed != null) {
        intent.putExtra(
            ReaderPlaybackService.EXTRA_SPEED,
            speed ?: ReaderState.snapshot.value.speed,
        )
    }
    positionMs?.let { intent.putExtra(ReaderPlaybackService.EXTRA_POSITION, it) }
    if (action == ReaderPlaybackService.ACTION_READ || action == ReaderPlaybackService.ACTION_PLAY) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}
