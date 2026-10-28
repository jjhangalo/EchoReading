package com.echoreading.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echoreading.R
import com.echoreading.reader.ReaderState
import com.echoreading.speech.TranscriptionSnapshot
import com.echoreading.speech.TranscriptionStatus

@Composable
fun TranscriptionActionFab(
    recording: Boolean,
    processing: Boolean,
    modifier: Modifier = Modifier,
    onRecord: () -> Unit,
    onSelectAudio: () -> Unit,
    onStopRecording: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recording, processing) {
        if (recording || processing) expanded = false
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(visible = expanded && !recording && !processing) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        expanded = false
                        onSelectAudio()
                    },
                    icon = { Icon(Default.FileUpload, contentDescription = null) },
                    text = { Text(stringResource(R.string.transcribe_select_audio)) },
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        expanded = false
                        onRecord()
                    },
                    icon = { Icon(Default.Mic, contentDescription = null) },
                    text = { Text(stringResource(R.string.transcribe_record_voice)) },
                )
            }
        }

        FloatingActionButton(
            onClick = {
                when {
                    recording -> onStopRecording()
                    processing -> onCancel()
                    else -> expanded = !expanded
                }
            },
            containerColor = if (processing) colorScheme.errorContainer
            else colorScheme.primary,
            contentColor = if (processing) colorScheme.onErrorContainer
            else colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        ) {
            Icon(
                imageVector = when {
                    recording -> Default.Stop
                    processing -> Default.Cancel
                    expanded -> Default.Close
                    else -> Default.Mic
                },
                contentDescription = when {
                    recording -> stringResource(R.string.transcribe_stop_recording)
                    processing -> stringResource(R.string.transcribe_cancel_in_progress)
                    expanded -> stringResource(R.string.close_panel)
                    else -> stringResource(R.string.transcribe_open_actions)
                },
            )
        }
    }
}

@Composable
fun SpeechProcessStatusCard(
    snapshot: TranscriptionSnapshot,
    modifier: Modifier = Modifier,
    onResumeClick: () -> Unit = {},
    selectedModelLabel: String? = null,
    onChangeModel: () -> Unit = {},
) {
    val isProblem = snapshot.status == TranscriptionStatus.ERROR ||
            snapshot.status == TranscriptionStatus.MODEL_REQUIRED
    val isWorking = snapshot.status in setOf(
        TranscriptionStatus.RECORDING,
        TranscriptionStatus.DECODING,
        TranscriptionStatus.CHECKING_QUALITY,
        TranscriptionStatus.TRANSCRIBING,
    )
    val statusText = when (snapshot.status) {
        TranscriptionStatus.DECODING -> stringResource(R.string.transcribe_decoding)
        TranscriptionStatus.CHECKING_QUALITY -> stringResource(R.string.transcribe_checking_quality)
        TranscriptionStatus.TRANSCRIBING -> stringResource(R.string.transcribe_in_progress)
        TranscriptionStatus.RECORDING -> stringResource(R.string.transcribe_listening)
        TranscriptionStatus.PAUSED -> stringResource(R.string.transcribe_paused)
        TranscriptionStatus.MODEL_REQUIRED -> stringResource(R.string.transcribe_model_required)
        TranscriptionStatus.DONE -> stringResource(R.string.transcribe_done)
        TranscriptionStatus.ERROR -> stringResource(R.string.transcribe_error)
        TranscriptionStatus.IDLE -> stringResource(R.string.transcribe_ready)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isProblem) colorScheme.errorContainer.copy(alpha = 0.45f)
        else colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isProblem -> Icon(
                        Default.ErrorOutline,
                        null,
                        tint = colorScheme.error
                    )

                    snapshot.status == TranscriptionStatus.DONE -> Icon(
                        Default.CheckCircle,
                        null,
                        tint = colorScheme.primary,
                    )

                    isWorking -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )

                    else -> Icon(Default.Mic, null, tint = colorScheme.primary)
                }
                Text(
                    text = statusText,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isProblem) colorScheme.error
                    else colorScheme.onSurface,
                )
                if (snapshot.status == TranscriptionStatus.PAUSED || snapshot.status == TranscriptionStatus.ERROR) {
                    IconButton(onClick = onResumeClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Default.PlayArrow,
                            contentDescription = stringResource(R.string.transcribe_resume)
                        )
                    }
                }
            }

            if (snapshot.audioFileName.isNotEmpty()) {
                val duration = snapshot.audioDurationMs.takeIf { it > 0 }?.let {
                    " · ${it / 60_000}m ${(it % 60_000) / 1_000}s"
                }.orEmpty()
                Text(
                    text = snapshot.audioFileName + duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            if (snapshot.status == TranscriptionStatus.IDLE && selectedModelLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.transcribe_model_selected, selectedModelLabel),
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppTextAction(
                        icon = Default.Tune,
                        label = stringResource(R.string.transcribe_change_model),
                        onClick = onChangeModel,
                    )
                }
            }

            if (isWorking && snapshot.status != TranscriptionStatus.RECORDING) {
                LinearProgressIndicator(
                    progress = { snapshot.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape),
                )
            }

            if (isProblem && !snapshot.error.isNullOrBlank()) {
                val errorText = when (snapshot.error) {
                    "transcribe_error_noisy" -> stringResource(R.string.transcribe_error_noisy)
                    "transcribe_error_silent" -> stringResource(R.string.transcribe_error_silent)
                    "transcribe_error_no_speech" -> stringResource(R.string.transcribe_error_no_speech)
                    "transcribe_error_low_volume" -> stringResource(R.string.transcribe_error_low_volume)
                    "transcribe_error_clipped" -> stringResource(R.string.transcribe_error_clipped)
                    else -> snapshot.error
                }
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun SpeechProcessResultCard(
    ctx: Context,
    snapshot: TranscriptionSnapshot,
    modelLabel: String,
    modifier: Modifier = Modifier,
    onChangeModel: () -> Unit = {},
) {
    val transcribedText = snapshot.transcribedText
    val textValue = remember(snapshot.transcribedText) {
        TextFieldValue(snapshot.transcribedText)
    }
    val words = remember(transcribedText) {
        if (transcribedText.isBlank()) {
            0
        } else {
            transcribedText.trim().split("\\s+".toRegex()).count { it.isNotBlank() }
        }
    }
    val chars = transcribedText.length

    AppTextCard(
        value = textValue,
        onValueChange = {},
        title = stringResource(R.string.transcribe_result_title),
        icon = Icons.AutoMirrored.Filled.Notes,
        placeholder = "",
        modifier = modifier,
        editable = false,
        readOnlyAsText = true,
        headerActions = {
            AppTextAction(
                icon = Default.PlayArrow,
                contentDescription = stringResource(R.string.transcribe_listen),
                onClick = { ReaderState.loadText(ctx, transcribedText) },
            )
            AppTextAction(
                icon = Default.Share,
                contentDescription = stringResource(R.string.transcribe_share),
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, transcribedText)
                    }
                    ctx.startActivity(Intent.createChooser(intent, "Partilhar"))
                },
            )
            AppTextAction(
                icon = Default.ContentCopy,
                contentDescription = stringResource(R.string.transcribe_copy),
                onClick = {
                    val clipboard =
                        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "Transcrição",
                            transcribedText,
                        ),
                    )
                },
            )
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextStatPill(
                    text = stringResource(R.string.text_stats_format, words, chars),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Default.Lock,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.transcribe_read_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.transcribe_model_used, modelLabel),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppTextAction(
                    icon = Default.Tune,
                    label = stringResource(R.string.transcribe_change_model),
                    onClick = onChangeModel,
                )
            }
        },
    )
}
