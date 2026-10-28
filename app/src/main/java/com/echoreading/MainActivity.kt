package com.echoreading

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.echoreading.reader.ReadingHistory
import com.echoreading.reader.ReadingStatus
import com.echoreading.reader.ReaderState
import com.echoreading.share.ShareIntentHandler
import com.echoreading.speech.SpeechToTextState
import com.echoreading.ui.EcoApp
import com.echoreading.ui.theme.EcoTheme
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.OnnxMetadata
import com.echoreading.voice.VoiceDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReaderState.restore(this)
        SpeechToTextState.restore(this)
        ReadingHistory.init(this)
        VoiceDownloadManager.initialize(this)

        lifecycleScope.launch(Dispatchers.IO) {
            OfflineVoice.prewarmEspeakData(applicationContext)
            OnnxMetadata.repairAllInstalledVoices(applicationContext)
        }

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent { EcoTheme { EcoApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action == Intent.ACTION_SEND) {
            val audioUri = ShareIntentHandler.extractAudioUri(intent)
            if (audioUri != null) {
                SpeechToTextState.pendingAudioUri.value = audioUri
                SpeechToTextState.loadAudioEvent.tryEmit(audioUri)
            } else {
                val sharedText = ShareIntentHandler.extractText(intent)
                if (!sharedText.isNullOrBlank()) {
                    ReaderState.loadText(this, sharedText)
                }
            }
        } else if (action == Intent.ACTION_VIEW) {
            val viewText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
            if (!viewText.isNullOrBlank()) {
                val currentSnapshot = ReaderState.snapshot.value
                val isCurrentSession = currentSnapshot.text == viewText &&
                    (currentSnapshot.status == ReadingStatus.PLAYING ||
                     currentSnapshot.status == ReadingStatus.PAUSED ||
                     currentSnapshot.status == ReadingStatus.PREPARING)
                if (isCurrentSession) {
                    // QuickReadActivity expansion: preserve continuous audio playback
                    ReaderState.loadTextEvent.tryEmit(viewText)
                } else {
                    ReaderState.loadText(this, viewText)
                }
            }
        }
    }
}
