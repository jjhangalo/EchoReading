package com.echoreading

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.echoreading.reader.ReadingHistory
import com.echoreading.reader.ReadingStatus
import com.echoreading.reader.ReaderState
import com.echoreading.share.ShareIntentHandler
import com.echoreading.ui.EcoApp
import com.echoreading.ui.theme.EcoTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted: Boolean — no-op, the app works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ReaderState.restore(this)
        ReadingHistory.init(this)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        // Ask for POST_NOTIFICATIONS on first open
        if (savedInstanceState == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                com.echoreading.speech.SpeechToTextState.pendingAudioUri.value = audioUri
                com.echoreading.speech.SpeechToTextState.loadAudioEvent.tryEmit(audioUri)
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
