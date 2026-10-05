package com.echoreading.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echoreading.R
import com.echoreading.reader.ReaderState
import com.echoreading.speech.TranscriptionSnapshot
import com.echoreading.speech.TranscriptionStatus

@Composable
fun TranscriptionActionFab(
    busy: Boolean,
    modifier: Modifier = Modifier,
    onRecord: () -> Unit,
    onSelectAudio: () -> Unit,
    onCancel: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) expanded = false
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(visible = expanded && !busy) {
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
                if (busy) onCancel() else expanded = !expanded
            },
            containerColor = if (busy) colorScheme.errorContainer
            else colorScheme.primaryContainer,
            contentColor = if (busy) colorScheme.onErrorContainer
            else colorScheme.onPrimaryContainer,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
        ) {
            Icon(
                imageVector = when {
                    busy -> Default.Cancel
                    expanded -> Default.Close
                    else -> Default.Mic
                },
                contentDescription = when {
                    busy -> stringResource(R.string.transcribe_cancel_in_progress)
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
        TranscriptionStatus.IDLE -> ""
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isProblem) colorScheme.errorContainer.copy(alpha = 0.45f)
        else colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainerLowest,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ResultAction(
                    icon = Default.PlayArrow,
                    label = stringResource(R.string.transcribe_listen),
                    onClick = { ReaderState.loadText(ctx, snapshot.transcribedText) }
                )
                ResultAction(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    icon = Default.Share,
                    label = stringResource(R.string.transcribe_share),
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, snapshot.transcribedText)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "Partilhar"))
                    },
                )
                ResultAction(
                    icon = Default.ContentCopy,
                    label = stringResource(R.string.transcribe_copy),
                    onClick = {
                        val clipboard =
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                "Transcrição",
                                snapshot.transcribedText
                            )
                        )
                    },
                )
            }

            Text(
                text = snapshot.transcribedText,
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ResultAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = colorScheme.surfaceContainer,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                icon,
                null,
                tint = colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.primary
            )
        }
    }
}
