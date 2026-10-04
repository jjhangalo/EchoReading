package com.echoreading.ui.component

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.echoreading.MainActivity
import com.echoreading.R
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingStatus
import com.echoreading.util.sendCommand

@Composable
fun QuickReaderPopupWidget(
    text: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op callback */ }

    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle affordance
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(
                        colorScheme.outlineVariant.copy(alpha = 0.6f),
                        CircleShape
                    )
            )

            // Header row with title, waveform pill and close icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(
                                if (snapshot.status == ReadingStatus.PLAYING) colorScheme.primary
                                else colorScheme.outline,
                                CircleShape
                            )
                    )
                    Text(
                        stringResource(R.string.action_echo),
                        style = typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = colorScheme.onSurface
                    )
                    SoundWave(isPlaying = snapshot.status == ReadingStatus.PLAYING)
                }

                Surface(
                    shape = CircleShape,
                    color = colorScheme.surfaceContainer,
                    modifier = Modifier
                        .size(32.dp)
                        .clickable(onClick = onClose)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close_panel),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Scrollable text card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = colorScheme.surfaceContainerLowest
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 130.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        text = text,
                        style = typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = colorScheme.onSurface
                    )
                }
            }

            // Voice selection & status
            StitchStatusVoiceBar(snapshot = snapshot, enabled = true)

            // Transport controls (Rewind 10s, Play/Pause FAB, Forward 10s)
            val isPlaying = snapshot.status == ReadingStatus.PLAYING
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                color = colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable { sendCommand(ctx, ReaderPlaybackService.ACTION_BACK) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Replay10,
                                contentDescription = stringResource(R.string.rewind_ten),
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // Play/Pause FAB responding to snapshot.status
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.primary,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .size(56.dp)
                            .clickable {
                                if (!isPlaying && ContextCompat.checkSelfPermission(
                                        ctx, Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                                when (snapshot.status) {
                                    ReadingStatus.PLAYING -> {
                                        sendCommand(ctx, ReaderPlaybackService.ACTION_PAUSE)
                                    }

                                    ReadingStatus.PAUSED -> {
                                        sendCommand(ctx, ReaderPlaybackService.ACTION_PLAY)
                                    }

                                    else -> {
                                        sendCommand(
                                            ctx,
                                            ReaderPlaybackService.ACTION_READ,
                                            text
                                        )
                                    }
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) stringResource(R.string.pause_reading) else stringResource(
                                    R.string.resume_reading
                                ),
                                tint = colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    // Forward 10s
                    Surface(
                        shape = CircleShape,
                        color = colorScheme.surfaceContainerHighest,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                sendCommand(
                                    ctx,
                                    ReaderPlaybackService.ACTION_FORWARD
                                )
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Forward10,
                                contentDescription = stringResource(R.string.forward_ten),
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Speed adjustment pills (0.75x, 1.0x, 1.25x, 1.5x, 2.0x) dispatching ACTION_SPEED
            val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                speeds.forEach { s ->
                    val isSelected = snapshot.speed == s
                    Surface(
                        shape = CircleShape,
                        color = if (isSelected) colorScheme.primary else colorScheme.surfaceContainer,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clickable {
                                ReaderState.snapshot.value =
                                    ReaderState.snapshot.value.copy(speed = s)
                                ReaderState.save(ctx)
                                val intent =
                                    Intent(ctx, ReaderPlaybackService::class.java).apply {
                                        action = ReaderPlaybackService.ACTION_SPEED
                                        putExtra(ReaderPlaybackService.EXTRA_SPEED, s)
                                        putExtra(ReaderPlaybackService.EXTRA_TEXT, text)
                                        putExtra(
                                            ReaderPlaybackService.EXTRA_POSITION,
                                            snapshot.positionMs
                                        )
                                    }
                                ctx.startService(intent)
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${s}x",
                                style = typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Action button: "Abrir no Leitor" expands into MainActivity
            Button(
                onClick = {
                    val intent = Intent(ctx, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        putExtra(Intent.EXTRA_TEXT, text)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    ctx.startActivity(intent)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.open_in_reader),
                    style = typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
