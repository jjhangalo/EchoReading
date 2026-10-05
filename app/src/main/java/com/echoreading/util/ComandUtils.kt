package com.echoreading.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderState

fun sendCommand(context: Context, action: String, text: String? = null) {
    val intent = Intent(context, ReaderPlaybackService::class.java).setAction(action)
    if (text != null) {
        intent.putExtra(ReaderPlaybackService.EXTRA_TEXT, text)
        intent.putExtra(ReaderPlaybackService.EXTRA_VOICE, ReaderState.snapshot.value.voiceId)
        intent.putExtra(ReaderPlaybackService.EXTRA_SPEED, ReaderState.snapshot.value.speed)
    }
    if (action == ReaderPlaybackService.ACTION_READ || action == ReaderPlaybackService.ACTION_PLAY) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}
