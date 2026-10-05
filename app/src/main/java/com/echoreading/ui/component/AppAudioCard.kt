package com.echoreading.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons.Default
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults.buttonColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults.cardColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.echoreading.R
import com.echoreading.reader.ReaderState
import com.echoreading.speech.SpeechToTextState
import com.echoreading.speech.TranscriptionSnapshot
import com.echoreading.speech.TranscriptionStatus
import java.util.Locale

@Composable
fun RecordVoiceCard(
    ctx: Context,
    isRecording: Boolean,
    recDuration: Int,
    recAmp: Float,
    modifier: Modifier = Modifier,
    onRecClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = cardColors(
            containerColor = if (isRecording) colorScheme.primaryContainer.copy(
                alpha = 0.15f
            )
            else colorScheme.surfaceContainerLowest
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isRecording) {
                Surface(
                    shape = CircleShape,
                    color = colorScheme.error,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Default.Mic,
                            contentDescription = null,
                            tint = colorScheme.onError,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                val durationStr = String.format(
                    Locale.US,
                    "%02d:%02d",
                    recDuration / 60,
                    recDuration % 60
                )
                Text(
                    text = durationStr,
                    style = typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.error
                )

                Text(
                    text = stringResource(R.string.transcribe_listening),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )

                LinearProgressIndicator(
                    progress = { recAmp },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colorScheme.primary,
                    trackColor = colorScheme.surfaceContainerHighest,
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Button(onClick = { SpeechToTextState.stopRecording(ctx) }) {
                        Icon(Default.Stop, null)
                        Text(
                            text = stringResource(R.string.transcribe_stop_recording),
                            style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    Button(
                        onClick = { SpeechToTextState.cancel(ctx) },
                        colors = buttonColors(colorScheme.error, colorScheme.onError)
                    ) {
                        Icon(Default.Cancel, null)
                        Text(
                            text = stringResource(R.string.cancel),
                            style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Default.Mic,
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onRecClick)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Default.Mic,
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.transcribe_speak_now),
                            style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = colorScheme.onPrimary
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.stt_screen_record_tip),
                    style = typography.bodySmall,
                    color = colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SelectAudioCard(
    modifier: Modifier = Modifier,
    onOpenClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = cardColors(
            containerColor = colorScheme.surfaceContainerLowest
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Default.AudioFile,
                        contentDescription = null,
                        tint = colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colorScheme.secondary,
                modifier = Modifier.clickable(onClick = onOpenClick)
            ) {
                Text(
                    text = stringResource(R.string.transcribe_select_audio),
                    style = typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = colorScheme.onSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Text(
                text = stringResource(R.string.transcribe_supported_formats),
                style = typography.bodySmall,
                color = colorScheme.outline,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.transcribe_or_share),
                style = typography.bodySmall,
                color = colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
fun SpeechProcessStatusCard(
    snapshot: TranscriptionSnapshot,
    modifier: Modifier = Modifier,
    onCancelClick: () -> Unit,
    onResumeClick: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = cardColors(
            containerColor = colorScheme.surfaceContainerLow
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (snapshot.status) {
                        TranscriptionStatus.ERROR, TranscriptionStatus.MODEL_REQUIRED -> {
                            Icon(
                                Default.ErrorOutline,
                                null,
                                tint = colorScheme.error
                            )
                        }

                        TranscriptionStatus.DONE -> {
                            Icon(
                                Default.Mic,
                                null,
                                tint = colorScheme.primary
                            )
                        }

                        else -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = colorScheme.primary
                            )
                        }
                    }

                    val statusText = when (snapshot.status) {
                        TranscriptionStatus.DECODING -> stringResource(R.string.transcribe_decoding)
                        TranscriptionStatus.CHECKING_QUALITY -> stringResource(R.string.transcribe_checking_quality)
                        TranscriptionStatus.TRANSCRIBING -> stringResource(R.string.transcribe_in_progress)
                        TranscriptionStatus.RECORDING -> stringResource(R.string.transcribe_listening)
                        TranscriptionStatus.PAUSED -> "Processamento pausado"
                        TranscriptionStatus.MODEL_REQUIRED -> "Modelo necessário"
                        TranscriptionStatus.DONE -> stringResource(R.string.transcribe_done)
                        TranscriptionStatus.ERROR -> stringResource(R.string.transcribe_error)
                        TranscriptionStatus.IDLE -> ""
                    }

                    Text(
                        text = statusText,
                        style = typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (snapshot.status == TranscriptionStatus.ERROR ||
                            snapshot.status == TranscriptionStatus.MODEL_REQUIRED
                        ) colorScheme.error else colorScheme.onSurface
                    )
                }

                when (snapshot.status) {
                    TranscriptionStatus.TRANSCRIBING,
                    TranscriptionStatus.DECODING,
                    TranscriptionStatus.CHECKING_QUALITY -> {
                        IconButton(onCancelClick, modifier = Modifier) {
                            Icon(Default.Cancel, null, tint = colorScheme.error)
                        }
                    }

                    TranscriptionStatus.PAUSED -> {
                        IconButton(onResumeClick, modifier = Modifier) {
                            Icon(Default.PlayArrow, null, tint = colorScheme.primary)
                        }
                    }

                    TranscriptionStatus.ERROR -> {
                        IconButton(onResumeClick, modifier = Modifier) {
                            Icon(Default.PlayArrow, null, tint = colorScheme.primary)
                        }
                    }

                    else -> {}
                }
            }

            if (snapshot.audioFileName.isNotEmpty()) {
                val estMs = snapshot.audioDurationMs
                val durationStr =
                    if (estMs > 0) " • ${estMs / 60000}m ${(estMs % 60000) / 1000}s" else ""
                Text(
                    text = "${snapshot.audioFileName}$durationStr",
                    style = typography.labelSmall,
                    color = colorScheme.onSurfaceVariant
                )
            }

            if (snapshot.status == TranscriptionStatus.TRANSCRIBING ||
                snapshot.status == TranscriptionStatus.DECODING
            ) {
                LinearProgressIndicator(
                    progress = { snapshot.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = colorScheme.primary,
                    trackColor = colorScheme.surfaceContainerHighest,
                )
            } else if (snapshot.status == TranscriptionStatus.ERROR ||
                snapshot.status == TranscriptionStatus.MODEL_REQUIRED
            ) {
                // Convert standard error resources or show raw error
                val errResId = when (snapshot.error) {
                    "transcribe_error_noisy" -> R.string.transcribe_error_noisy
                    "transcribe_error_silent" -> R.string.transcribe_error_silent
                    "transcribe_error_no_speech" -> R.string.transcribe_error_no_speech
                    "transcribe_error_low_volume" -> R.string.transcribe_error_low_volume
                    "transcribe_error_clipped" -> R.string.transcribe_error_clipped
                    else -> 0
                }
                val errorText =
                    if (errResId != 0) stringResource(errResId) else snapshot.error.orEmpty()

                Text(
                    text = errorText,
                    style = typography.bodyMedium,
                    color = colorScheme.error
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
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = cardColors(
            containerColor = colorScheme.surfaceContainerLowest
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = snapshot.transcribedText,
                style = typography.bodyLarge,
                color = colorScheme.onSurface
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surfaceContainer,
                        modifier = Modifier.clickable {
                            val clipboard =
                                ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "Transcrição",
                                    snapshot.transcribedText
                                )
                            )
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Default.ContentCopy,
                                null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.transcribe_copy),
                                style = typography.labelSmall,
                                color = colorScheme.primary
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surfaceContainer,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, snapshot.transcribedText)
                            }
                            ctx.startActivity(Intent.createChooser(intent, "Partilhar"))
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Default.Share,
                                null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.transcribe_share),
                                style = typography.labelSmall,
                                color = colorScheme.primary
                            )
                        }
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = colorScheme.primary,
                    modifier = Modifier.clickable {
                        ReaderState.loadText(ctx, snapshot.transcribedText)
                    }
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Default.PlayArrow,
                            null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            stringResource(R.string.transcribe_listen),
                            style = typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
