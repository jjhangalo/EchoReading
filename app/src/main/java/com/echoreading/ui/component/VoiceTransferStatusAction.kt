package com.echoreading.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echoreading.R
import com.echoreading.voice.VoiceDownloadManager
import com.echoreading.voice.VoiceDownloadQueueItem
import com.echoreading.voice.VoiceDownloadState
import com.echoreading.voice.VoiceDownloadStatus

/**
 * App-level transfer control shown in the top app bar on every route.
 *
 * The compact chip reports the active percentage and pending count. Tapping it
 * opens a sheet that can cancel the active transfer and remove queued voices
 * without navigating to Settings or Voice Discovery.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun VoiceTransferStatusAction(modifier: Modifier = Modifier) {
    val state by VoiceDownloadManager.state.collectAsState()
    val active = state.status == VoiceDownloadStatus.DOWNLOADING
    val hasTransfers = active || state.queued.isNotEmpty()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(hasTransfers) {
        if (!hasTransfers) sheetOpen = false
    }
    if (!hasTransfers) return

    Surface(
        modifier = modifier.clickable { sheetOpen = true },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (active) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                )
                Text(
                    "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (state.queued.isNotEmpty()) {
                Text(
                    "+${state.queued.size}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            VoiceTransferSheet(state)
        }
    }
}

@Composable
private fun VoiceTransferSheet(state: VoiceDownloadState) {
    val active = state.status == VoiceDownloadStatus.DOWNLOADING
    val queuedCount = state.queued.size
    val summary = when {
        active && queuedCount > 0 -> stringResource(
            R.string.voice_transfer_summary_both,
            queuedCount,
        )
        active -> stringResource(R.string.voice_transfer_summary_active)
        queuedCount > 0 -> stringResource(
            R.string.voice_transfer_summary_queued,
            queuedCount,
        )
        else -> stringResource(R.string.voice_transfer_none)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.voice_transfers_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (active) {
            ActiveTransferCard(state)
        }

        if (queuedCount > 0) {
            Text(
                stringResource(R.string.voice_transfer_queue_title, queuedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.queued.forEachIndexed { index, item ->
                QueuedTransferRow(item = item, position = index + 1)
            }
            Text(
                stringResource(R.string.voice_transfer_queue_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!active && queuedCount == 0) {
            Text(
                stringResource(R.string.voice_transfer_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveTransferCard(state: VoiceDownloadState) {
    val percent = (state.progress * 100).toInt().coerceIn(0, 100)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(40.dp),
                    strokeWidth = 4.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        state.label?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.voice_transfer_active_unknown),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.voice_transfer_progress, percent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { VoiceDownloadManager.cancelActive() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(
                            R.string.voice_download_cancel_active,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
    }
}

@Composable
private fun QueuedTransferRow(item: VoiceDownloadQueueItem, position: Int) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(30.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.label.takeIf { it.isNotBlank() } ?: item.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.voice_transfer_queue_position, position),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { VoiceDownloadManager.cancelQueued(item.key) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.voice_download_remove_queued),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
