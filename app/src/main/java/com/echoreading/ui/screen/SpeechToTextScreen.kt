package com.echoreading.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.echoreading.R
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionStatus
import com.echoreading.ui.component.RecordVoiceCard
import com.echoreading.ui.component.SelectAudioCard
import com.echoreading.ui.component.SpeechProcessResultCard
import com.echoreading.ui.component.SpeechProcessStatusCard

@Composable
fun SpeechToTexScreen(
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val snapshot by SpeechToTextState.snapshot.collectAsState()
    val isRecording by SpeechToTextState.isRecording.collectAsState()
    val recDuration by SpeechToTextState.recordingDurationSec.collectAsState()
    val recAmp by SpeechToTextState.recordingAmplitude.collectAsState()

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            SpeechToTextState.startRecording(ctx)
        } else {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.transcribe_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { SpeechToTextState.transcribe(ctx, it) }
        }

    LaunchedEffect(Unit) {
        SpeechToTextState.pendingAudioUri.value?.let {
            SpeechToTextState.pendingAudioUri.value = null
            SpeechToTextState.transcribe(ctx, it)
        }
    }

    LaunchedEffect(Unit) {
        SpeechToTextState.loadAudioEvent.collect {
            SpeechToTextState.pendingAudioUri.value = null
            SpeechToTextState.transcribe(ctx, it)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RecordVoiceCard(ctx, isRecording, recDuration, recAmp) {
                if (ContextCompat.checkSelfPermission(
                        ctx,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    SpeechToTextState.startRecording(ctx)
                } else {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            SelectAudioCard { audioPicker.launch("audio/*") }

            if (snapshot.status != TranscriptionStatus.IDLE) {
                SpeechProcessStatusCard(snapshot, onCancelClick = SpeechToTextState::reset)
            }

            if (snapshot.status == TranscriptionStatus.DONE && snapshot.transcribedText.isNotEmpty()) {
                SpeechProcessResultCard(ctx, snapshot)
            }
        }
    }
}
