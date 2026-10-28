package com.echoreading.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.echoreading.R
import com.echoreading.ui.component.AppBottomTipCard
import com.echoreading.speech.ModelDownloadSnapshot
import com.echoreading.speech.ModelDownloadStatus
import com.echoreading.speech.OfflineSpeech
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionStatus
import com.echoreading.ui.component.SpeechProcessResultCard
import com.echoreading.ui.component.SpeechProcessStatusCard
import com.echoreading.ui.component.TranscriptionActionFab
import java.io.File
import java.util.Locale

@Composable
fun SpeechToTexScreen(
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val resources = LocalResources.current
    val snapshot by SpeechToTextState.snapshot.collectAsState()
    val download by SpeechToTextState.download.collectAsState()
    val selectedModel by SpeechToTextState.selectedModelId.collectAsState()
    val modelsRevision by SpeechToTextState.modelsRevision.collectAsState()
    val installedModelIds = remember(modelsRevision) {
        OfflineSpeech.installedModels(ctx).map { it.id }.toSet()
    }
    val selectedModelLabel = selectedModel?.let { OfflineSpeech.option(it).label }
    val processedModelLabel = snapshot.modelId.takeIf { it.isNotBlank() }
        ?.let { OfflineSpeech.option(it).label }
        ?: selectedModelLabel.orEmpty()
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    val processingStatuses = setOf(
        TranscriptionStatus.DECODING,
        TranscriptionStatus.CHECKING_QUALITY,
        TranscriptionStatus.TRANSCRIBING,
    )
    val isRecording = snapshot.status == TranscriptionStatus.RECORDING
    val isProcessing = snapshot.status in processingStatuses ||
            download.status == ModelDownloadStatus.DOWNLOADING

    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    val playableAudioPath = snapshot.audioPath
        .takeIf { it.isNotBlank() && File(it).isFile }
        .orEmpty()

    DisposableEffect(playableAudioPath) {
        onDispose {
            audioPlayer?.release()
            audioPlayer = null
            isAudioPlaying = false
        }
    }

    fun ensureAudioPlayer(): MediaPlayer? {
        audioPlayer?.let { return it }
        if (playableAudioPath.isBlank()) return null
        return runCatching {
            MediaPlayer.create(ctx, Uri.fromFile(File(playableAudioPath)))?.also { player ->
                player.setOnCompletionListener { isAudioPlaying = false }
                player.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (audioPlayer === failed) {
                        audioPlayer = null
                        isAudioPlaying = false
                    }
                    true
                }
            }
        }.getOrNull()?.also { audioPlayer = it }
    }

    fun playAudio() {
        val player = ensureAudioPlayer() ?: return
        runCatching {
            val duration = player.duration
            if (duration > 0 && player.currentPosition >= duration) {
                player.seekTo(0)
            }
            player.start()
            isAudioPlaying = true
        }.onFailure {
            player.release()
            if (audioPlayer === player) audioPlayer = null
            isAudioPlaying = false
        }
    }

    fun pauseAudio() {
        audioPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.pause() }
        }
        isAudioPlaying = false
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            SpeechToTextState.startRecording(ctx)
        } else {
            Toast.makeText(
                ctx,
                resources.getString(R.string.transcribe_permission_needed),
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SpeechProcessStatusCard(
                        snapshot = snapshot,
                        onResumeClick = { SpeechToTextState.resume(ctx) },
                        selectedModelLabel = selectedModelLabel,
                        onChangeModel = { showModelSheet = true },
                    )

                    if (playableAudioPath.isNotBlank()) {
                        AudioPlaybackCard(
                            fileName = snapshot.audioFileName.ifBlank {
                                resources.getString(R.string.transcribe_audio_default_name)
                            },
                            isPlaying = isAudioPlaying,
                            onPlay = ::playAudio,
                            onPause = ::pauseAudio,
                        )
                    }

                    if (selectedModel == null && snapshot.status != TranscriptionStatus.DONE) {
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
                            onChangeModel = { showModelSheet = true },
                        )
                    }
                }

                AppBottomTipCard(
                    text = "${resources.getString(R.string.stt_screen_record_tip)}\n${
                        resources.getString(R.string.transcribe_supported_formats)
                    } · ${
                        resources.getString(R.string.transcribe_or_share)
                    }",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .padding(bottom = 72.dp),
                )
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

            if (showModelSheet) {
                TranscriptionModelSheet(
                    installedModelIds = installedModelIds,
                    selectedModelId = selectedModel,
                    download = download,
                    onDismiss = { showModelSheet = false },
                    onSelect = { modelId ->
                        if (SpeechToTextState.selectModel(ctx, modelId)) {
                            showModelSheet = false
                        }
                    },
                    onDownload = { modelId ->
                        SpeechToTextState.downloadModel(ctx, modelId)
                    },
                    onPause = { SpeechToTextState.pauseDownload(ctx) },
                    onResume = { SpeechToTextState.resume(ctx) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TranscriptionModelSheet(
    installedModelIds: Set<String>,
    selectedModelId: String?,
    download: ModelDownloadSnapshot,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val context = LocalContext.current
    val anotherDownloadActive = download.status == ModelDownloadStatus.DOWNLOADING

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.transcribe_model_label),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.transcribe_model_sheet_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
            ) {
                items(OfflineSpeech.models, key = { it.id }) { model ->
                    val installed = model.id in installedModelIds
                    val selected = model.id == selectedModelId
                    val thisDownload = download.modelId == model.id
                    val downloading = thisDownload &&
                        download.status == ModelDownloadStatus.DOWNLOADING
                    val pausedOrFailed = thisDownload &&
                        download.status in setOf(
                            ModelDownloadStatus.PAUSED,
                            ModelDownloadStatus.ERROR,
                        )

                    Column {
                        ListItem(
                            headlineContent = {
                                Text(
                                    model.label + if (model.recommended) {
                                        " · Recomendado"
                                    } else {
                                        ""
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        if (installed) {
                                            "Instalado · ${
                                                String.format(
                                                    Locale.US,
                                                    "%.0f",
                                                    OfflineSpeech.getModelSizeBytes(
                                                        context,
                                                        model.id,
                                                    ) / (1024f * 1024f),
                                                )
                                            } MB"
                                        } else {
                                            "Transferir · ${
                                                String.format(
                                                    Locale.US,
                                                    "%.0f",
                                                    model.downloadBytes / 1_000_000f,
                                                )
                                            } MB"
                                        },
                                    )
                                    if (pausedOrFailed && !download.error.isNullOrBlank()) {
                                        Text(
                                            download.error,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                Icon(
                                    if (installed) {
                                        Icons.Default.CheckCircle
                                    } else {
                                        Icons.Default.CloudDownload
                                    },
                                    contentDescription = null,
                                    tint = if (installed) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            trailingContent = {
                                when {
                                    selected -> Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = stringResource(
                                            R.string.transcribe_model_selected,
                                            model.label,
                                        ),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    downloading -> IconButton(onClick = onPause) {
                                        Icon(
                                            Icons.Default.Pause,
                                            contentDescription = "Pausar transferência",
                                        )
                                    }
                                    pausedOrFailed -> TextButton(onClick = onResume) {
                                        Text("Retomar")
                                    }
                                    installed -> TextButton(
                                        onClick = { onSelect(model.id) },
                                    ) {
                                        Text("Usar")
                                    }
                                    else -> FilledTonalButton(
                                        onClick = { onDownload(model.id) },
                                        enabled = !anotherDownloadActive,
                                    ) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text("Transferir")
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = installed && !selected) {
                                onSelect(model.id)
                            },
                        )
                        if (downloading) {
                            LinearProgressIndicator(
                                progress = { download.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                stringResource(
                                    R.string.transcribe_model_download,
                                ) + " ${(download.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlaybackCard(
    fileName: String,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.transcribe_audio_playback_title),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onPlay,
                    enabled = !isPlaying,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.transcribe_audio_play))
                }
                OutlinedButton(
                    onClick = onPause,
                    enabled = isPlaying,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.transcribe_audio_pause))
                }
            }
        }
    }
}

@Composable
private fun ModelRequiredCard(
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = shape,
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.transcribe_model_label),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(
                text = stringResource(R.string.transcribe_model_required),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                )
                Text(
                    text = "${stringResource(R.string.transcribe_model_download)} " +
                            "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = onDownload) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.transcribe_model_download_btn))
                }
            }
        }
    }
}
