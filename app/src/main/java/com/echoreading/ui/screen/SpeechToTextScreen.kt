package com.echoreading.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.echoreading.R
import com.echoreading.speech.ModelDownloadStatus
import com.echoreading.speech.OfflineSpeech
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionStatus
import com.echoreading.ui.component.SpeechProcessResultCard
import com.echoreading.ui.component.SpeechProcessStatusCard
import com.echoreading.ui.component.TranscriptionActionFab

@Composable
fun SpeechToTexScreen(
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val snapshot by SpeechToTextState.snapshot.collectAsState()
    val download by SpeechToTextState.download.collectAsState()
    val selectedModel by SpeechToTextState.selectedModelId.collectAsState()
    val modelsRevision by SpeechToTextState.modelsRevision.collectAsState()
    val installedModels = remember(modelsRevision) { OfflineSpeech.installedModels(ctx) }
    val selectedModelLabel = selectedModel?.let { OfflineSpeech.option(it).label }
    val processedModelLabel = snapshot.modelId.takeIf { it.isNotBlank() }
        ?.let { OfflineSpeech.option(it).label }
        ?: selectedModelLabel.orEmpty()
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    val processingStatuses = setOf(
        TranscriptionStatus.DECODING,
        TranscriptionStatus.CHECKING_QUALITY,
        TranscriptionStatus.TRANSCRIBING,
    )
    val isRecording = snapshot.status == TranscriptionStatus.RECORDING
    val isProcessing = snapshot.status in processingStatuses ||
            download.status == ModelDownloadStatus.DOWNLOADING

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            SpeechToTextState.startRecording(ctx)
        } else {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.transcribe_permission_needed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val audioPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                runCatching {
                    ctx.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
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

    val startRecording = {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            SpeechToTextState.startRecording(ctx)
        } else {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpeechProcessStatusCard(
                        snapshot = snapshot,
                        onResumeClick = { SpeechToTextState.resume(ctx) },
                        selectedModelLabel = selectedModelLabel,
                        onChangeModel = { showModelDialog = true },
                    )

                    if (selectedModel == null) {
                        ModelRequiredCard(
                            downloading = download.status == ModelDownloadStatus.DOWNLOADING,
                            progress = download.progress,
                            onDownload = {
                                SpeechToTextState.downloadModel(ctx, OfflineSpeech.DEFAULT_MODEL_ID)
                            },
                        )
                    }

                    if (snapshot.status == TranscriptionStatus.DONE && snapshot.transcribedText.isNotEmpty()) {
                        SpeechProcessResultCard(
                            ctx = ctx,
                            snapshot = snapshot,
                            modelLabel = processedModelLabel,
                            onChangeModel = { showModelDialog = true },
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 96.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp),
                        )
                        Text(
                            text = "${ctx.getString(R.string.stt_screen_record_tip)}\n${
                                ctx.getString(R.string.transcribe_supported_formats)
                            } · ${
                                ctx.getString(R.string.transcribe_or_share)
                            }",
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            TranscriptionActionFab(
                recording = isRecording,
                processing = isProcessing,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onRecord = startRecording,
                onSelectAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onStopRecording = { SpeechToTextState.stopRecording(ctx) },
                onCancel = { SpeechToTextState.cancel(ctx) },
            )

            if (showModelDialog) {
                TranscriptionModelDialog(
                    models = installedModels,
                    selectedModelId = selectedModel,
                    onDismiss = { showModelDialog = false },
                    onSelect = {
                        SpeechToTextState.selectModel(ctx, it)
                        showModelDialog = false
                    },
                )
            }
        }
    }
}

@Composable
private fun TranscriptionModelDialog(
    models: List<com.echoreading.speech.SpeechModelOption>,
    selectedModelId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcribe_model_label)) },
        text = {
            Column {
                models.forEach { model ->
                    TextButton(
                        onClick = { onSelect(model.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (model.id == selectedModelId) "✓ ${model.label}" else model.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ModelRequiredCard(
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.transcribe_model_required),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (downloading) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%")
            } else {
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.transcribe_model_download_btn))
                }
            }
        }
    }
}
