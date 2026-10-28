package com.echoreading.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echoreading.util.formatTime
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The only part of the reader card that changes continuously during playback.
 *
 * The service publishes an authoritative position every 500 ms. This component
 * re-anchors to that sample, then advances the displayed position once per
 * display frame. On a 90 Hz display the frame clock calls this about 90 times
 * per second, but only this composable recomposes; the player card and Home
 * screen stay on the slower snapshot cadence.
 *
 * Audio is synthesized at the selected reading speed, so the player still
 * advances its media position in real time (1x); [speed] must not be applied to
 * the interpolation again. [positionUpdatedAtNanos] is the service's monotonic
 * sample time, which avoids a backward jump when composition happens later.
 *
 * Pause, seek, restart and error paths snap to the supplied position instead of
 * extrapolating, so the bar cannot keep moving after the audio has stopped.
 */
@Composable
fun PlaybackProgressSection(
    positionMs: Long,
    positionUpdatedAtNanos: Long,
    durationMs: Long,
    isPlaying: Boolean,
    speed: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    var displayedPositionMs by remember { mutableLongStateOf(positionMs) }
    var anchorPositionMs by remember { mutableLongStateOf(positionMs) }
    var anchorTimeNanos by remember { mutableLongStateOf(System.nanoTime()) }

    val hasDuration = durationMs > 0L
    val progress = if (hasDuration) {
        (displayedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderValue = scrubProgress ?: progress
    val labelPositionMs = scrubProgress?.let { it * durationMs }?.toLong()
        ?: displayedPositionMs
    val labelSeconds = (labelPositionMs / 1_000L).coerceAtLeast(0L)
    val labelText = remember(labelSeconds) { formatTime(labelSeconds * 1_000L) }
    val durationText = remember(durationMs) { formatTime(durationMs) }
    val speedText = remember(speed) {
        "${String.format(Locale.US, "%.1f", speed)}x Vel"
    }

    // Re-anchor whenever the service publishes a new authoritative sample or
    // the playback mode changes. The service timestamp lets us account for the
    // time between sampling the player and composing this frame, so a new
    // sample does not pull the bar backwards by the composition latency.
    // While the user is dragging, keep their preview position instead of
    // pulling the thumb back to the anchor.
    LaunchedEffect(positionMs, positionUpdatedAtNanos, durationMs, isPlaying) {
        val sampleTimeNanos = positionUpdatedAtNanos.takeIf { it > 0L }
            ?: System.nanoTime()
        anchorPositionMs = positionMs.coerceIn(0L, maxOf(durationMs, 0L))
        anchorTimeNanos = sampleTimeNanos
        if (scrubProgress == null) {
            displayedPositionMs = if (isPlaying) {
                interpolatedPlaybackPositionMs(
                    anchorPositionMs = anchorPositionMs,
                    anchorTimeNanos = anchorTimeNanos,
                    nowNanos = System.nanoTime(),
                    durationMs = durationMs,
                )
            } else {
                anchorPositionMs
            }
        }
    }

    // Runs at the current display refresh rate (90 Hz on the target device),
    // not at a fixed timer interval. The frame timestamps come from the same
    // clock as the anchor, so the interpolation never assumes 60/90/120 Hz.
    LaunchedEffect(isPlaying, durationMs) {
        if (!isPlaying || durationMs <= 0L) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (scrubProgress != null) return@withFrameNanos
                displayedPositionMs = interpolatedPlaybackPositionMs(
                    anchorPositionMs = anchorPositionMs,
                    anchorTimeNanos = anchorTimeNanos,
                    nowNanos = frameTimeNanos,
                    durationMs = durationMs,
                )
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Slider(
            value = sliderValue,
            onValueChange = { scrubProgress = it.coerceIn(0f, 1f) },
            onValueChangeFinished = {
                val target = ((scrubProgress ?: progress).coerceIn(0f, 1f) * durationMs)
                    .roundToLong()
                    .coerceIn(0L, durationMs)
                scrubProgress = null
                displayedPositionMs = target
                anchorPositionMs = target
                anchorTimeNanos = System.nanoTime()
                onSeek(target)
            },
            enabled = hasDuration,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    labelText,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    speedText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

internal fun interpolatedPlaybackPositionMs(
    anchorPositionMs: Long,
    anchorTimeNanos: Long,
    nowNanos: Long,
    durationMs: Long,
): Long {
    if (durationMs <= 0L) return anchorPositionMs.coerceAtLeast(0L)
    val elapsedMs = ((nowNanos - anchorTimeNanos) / 1_000_000L)
        .coerceIn(0L, MAX_INTERPOLATION_MS)
    return (anchorPositionMs + elapsedMs).coerceIn(0L, durationMs)
}

private const val MAX_INTERPOLATION_MS = 1_000L
