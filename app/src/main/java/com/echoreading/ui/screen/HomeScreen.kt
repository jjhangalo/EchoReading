package com.echoreading.ui.screen

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.echoreading.R
import com.echoreading.ui.component.AppBottomTipCard
import com.echoreading.ui.component.AppTextAction
import com.echoreading.ui.component.AppTextCard
import com.echoreading.ui.component.AppTextStatPill
import com.echoreading.ui.component.PlaybackProgressSection
import com.echoreading.ui.component.SoundWave
import com.echoreading.reader.MarkdownText
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingStatus
import com.echoreading.ui.component.StitchStatusVoiceBar
import com.echoreading.ui.component.MarkdownVisualTransformation
import com.echoreading.util.sendCommand
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ReaderHome() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snapshot by ReaderState.snapshot.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op callback */ }

    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val draft = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .getString("draft", snapshot.text).orEmpty()
        mutableStateOf(TextFieldValue(draft))
    }
    var appliedLoadEpoch by rememberSaveable { mutableStateOf(snapshot.loadEpoch) }

    // A share/history load can happen while another tab is visible, so Home's
    // event collector is not running. The epoch makes the new text authoritative
    // when Home is next composed, without overwriting text the user is typing.
    LaunchedEffect(snapshot.loadEpoch) {
        if (snapshot.loadEpoch != appliedLoadEpoch) {
            appliedLoadEpoch = snapshot.loadEpoch
            input = TextFieldValue(text = snapshot.text, selection = TextRange(0))
        }
    }

    LaunchedEffect(Unit) {
        ReaderState.loadTextEvent.collect { newText ->
            input = TextFieldValue(text = newText, selection = TextRange(0))
        }
    }

    LaunchedEffect(input.text) {
        delay(500.milliseconds)
        context.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .edit().putString("draft", input.text).apply()
    }

    val editable = snapshot.status == ReadingStatus.IDLE || snapshot.status == ReadingStatus.ERROR
    LaunchedEffect(editable) {
        if (!editable) focusManager.clearFocus()
    }
    val markdown = remember(input.text) { MarkdownText.parse(input.text) }
    val accent = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val markdownTransformation = remember(markdown, accent, codeBackground) {
        MarkdownVisualTransformation(markdown, accent, codeBackground)
    }
    // During playback the card renders the styled text itself instead of a
    // read-only TextField. That keeps copy/selection, enables independent
    // scrolling, and lets the card follow the spoken line with context.
    val playbackText = remember(markdownTransformation, input.text, editable) {
        if (editable) {
            null
        } else {
            markdownTransformation.filter(AnnotatedString(input.text)).text
        }
    }
    val playbackFollowOffset = remember(markdown, snapshot.characterOffset, editable) {
        if (editable) null else markdown.originalToVisual(snapshot.characterOffset)
    }
    val words = remember(markdown.spokenText) {
        if (markdown.spokenText.isBlank()) {
            0
        } else {
            markdown.spokenText.split("\\s+".toRegex()).count { it.isNotBlank() }
        }
    }
    val chars = input.text.length
    val estSeconds = remember(words, snapshot.speed) {
        if (words > 0) (words / (2.5 * snapshot.speed)).toInt() else 0
    }

    LaunchedEffect(snapshot.text, editable) {
        // The card follows snapshot.characterOffset through playbackFollowOffset.
        // Do not move the TextField selection here: doing so forced the field to
        // scroll to the current chunk and made manual scrolling during playback
        // impossible.
        if (!editable && snapshot.text != input.text) {
            input = TextFieldValue(snapshot.text)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Primary input card. The shared component keeps the reader and
            // transcription screens visually aligned while the `editable`
            // flag makes only this instance writable.
            AppTextCard(
                value = input,
                onValueChange = { input = it },
                title = stringResource(R.string.reading_text_title),
                icon = Icons.Default.EditNote,
                placeholder = stringResource(R.string.reading_text_placeholder),
                editable = editable,
                visualTransformation = markdownTransformation,
                expandable = true,
                readOnlyText = playbackText,
                followOffset = playbackFollowOffset,
                headerActions = {
                    AppTextAction(
                        icon = Icons.Default.ContentPaste,
                        label = stringResource(R.string.paste_text),
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText =
                                clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                    .orEmpty()
                            if (clipText.isNotBlank()) input = TextFieldValue(clipText)
                        },
                        enabled = editable,
                    )
                    if (input.text.isNotBlank()) {
                        AppTextAction(
                            icon = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.clear_text),
                            onClick = { input = TextFieldValue("") },
                            enabled = editable,
                        )
                    }
                },
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppTextStatPill(
                            text = stringResource(
                                R.string.text_stats_format,
                                words,
                                chars,
                            ),
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                if (estSeconds < 60) {
                                    "~$estSeconds seg"
                                } else {
                                    "~${estSeconds / 60}m ${estSeconds % 60}s"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )

            // Voice/speed stay editable during playback; the service restarts
            // at the live position when either changes. Only the draft text
            // field is locked while a session is active.
            StitchStatusVoiceBar(snapshot = snapshot, enabled = true, showSpeed = true)

            // Reprodutor de Voz Card (Bordered M3 Card from Stitch)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header with Waveform & Repeat Button
                    Row(
                        Modifier.fillMaxWidth(),
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
                                        if (snapshot.status == ReadingStatus.PLAYING) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                        CircleShape
                                    )
                            )
                            Column {
                                val readingLabel = when (snapshot.status) {
                                    ReadingStatus.PREPARING -> "A PREPARAR O ÁUDIO"
                                    ReadingStatus.PLAYING -> "EM LEITURA"
                                    ReadingStatus.PAUSED -> "LEITURA EM PAUSA"
                                    ReadingStatus.ERROR -> "ERRO NA LEITURA"
                                    ReadingStatus.IDLE -> "PRONTO PARA LER"
                                }
                                Text(
                                    readingLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Reprodutor de Voz",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SoundWave(isPlaying = snapshot.status == ReadingStatus.PLAYING)

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable(enabled = input.text.isNotBlank()) {
                                        sendCommand(
                                            context,
                                            ReaderPlaybackService.ACTION_READ,
                                            input.text,
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Replay,
                                        contentDescription = "Reiniciar leitura",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (snapshot.status == ReadingStatus.ERROR && !snapshot.error.isNullOrBlank()) {
                        Text(
                            snapshot.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Kept in its own composable: this is the only element that
                    // is updated at the display refresh rate during playback.
                    PlaybackProgressSection(
                        positionMs = snapshot.positionMs,
                        positionUpdatedAtNanos = snapshot.positionUpdatedAtNanos,
                        durationMs = snapshot.durationMs,
                        isPlaying = snapshot.status == ReadingStatus.PLAYING,
                        speed = snapshot.speed,
                        onSeek = { target ->
                            sendCommand(
                                context,
                                ReaderPlaybackService.ACTION_SEEK,
                                positionMs = target,
                            )
                        },
                    )

                    // Transport Bar Container (Soft surfaceContainer pill containing all 5 transport controls)
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Stop button (48dp circle)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        sendCommand(
                                            context,
                                            ReaderPlaybackService.ACTION_STOP
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Parar",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            // 2. Rewind 10s button (48dp circle)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        sendCommand(
                                            context,
                                            ReaderPlaybackService.ACTION_BACK
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Replay10,
                                        contentDescription = "-10s",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // 3. Main Play/Pause FAB (64dp circle in solid primary with shadow)
                            val isPlaying = snapshot.status == ReadingStatus.PLAYING
                            val isPreparing = snapshot.status == ReadingStatus.PREPARING
                            val canPlay = snapshot.status != ReadingStatus.PREPARING && (isPlaying ||
                                snapshot.status == ReadingStatus.PAUSED || markdown.spokenText.isNotBlank())
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable(enabled = canPlay) {
                                        val active = snapshot.status == ReadingStatus.PLAYING ||
                                                snapshot.status == ReadingStatus.PREPARING ||
                                                snapshot.status == ReadingStatus.PAUSED
                                        val isPause = active && isPlaying

                                        // Ask for notification permission just-in-time before Play (not on Pause)
                                        if (!isPause && ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }

                                        if (!active) {
                                            if (markdown.spokenText.isNotBlank()) {
                                                sendCommand(
                                                    context,
                                                    ReaderPlaybackService.ACTION_READ,
                                                    input.text
                                                )
                                            }
                                        } else {
                                            sendCommand(
                                                context,
                                                if (isPlaying) ReaderPlaybackService.ACTION_PAUSE
                                                else ReaderPlaybackService.ACTION_PLAY
                                            )
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isPreparing) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    } else {
                                        Icon(
                                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }
                            }

                            // 4. Forward 10s button (48dp circle)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        sendCommand(
                                            context,
                                            ReaderPlaybackService.ACTION_FORWARD
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Forward10,
                                        contentDescription = "+10s",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            // 5. Volume button (48dp circle)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable {
                                        val audio =
                                            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                        audio?.adjustStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            AudioManager.ADJUST_SAME,
                                            AudioManager.FLAG_SHOW_UI
                                        )
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Volume",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AppBottomTipCard(stringResource(R.string.home_screen_tip))

            Spacer(Modifier.height(8.dp))
        }
    }
}
