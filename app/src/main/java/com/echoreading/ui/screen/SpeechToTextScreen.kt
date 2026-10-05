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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.echoreading.speech.ModelDownloadStatus
import com.echoreading.speech.OfflineSpeech
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
    val download by SpeechToTextState.download.collectAsState()
    val selectedModel by SpeechToTextState.selectedModelId.collectAsState()

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
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                SpeechToTextState.transcribe(ctx, it)
            }
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
            if (selectedModel == null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = ctx.getString(R.string.transcribe_model_required),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (download.status == ModelDownloadStatus.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { download.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("${(download.progress * 100).toInt()}%")
                        } else {
                            Button(
                                onClick = {
                                    SpeechToTextState.downloadModel(ctx, OfflineSpeech.DEFAULT_MODEL_ID)
                                },
                            ) {
                                Text("Descarregar Whisper Base recomendado")
                            }
                        }
                    }
                }
            }

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

            SelectAudioCard { audioPicker.launch(arrayOf("audio/*")) }

            if (snapshot.status != TranscriptionStatus.IDLE && snapshot.status != TranscriptionStatus.RECORDING) {
                SpeechProcessStatusCard(
                    snapshot,
                    onCancelClick = { SpeechToTextState.cancel(ctx) },
                    onResumeClick = { SpeechToTextState.resume(ctx) },
                )
            }

            if (snapshot.status == TranscriptionStatus.DONE && snapshot.transcribedText.isNotEmpty()) {
                SpeechProcessResultCard(ctx, snapshot)
            }
        }
    }
}
