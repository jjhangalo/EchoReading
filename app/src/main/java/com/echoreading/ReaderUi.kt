package com.echoreading

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingStatus
import com.echoreading.voice.OfflineVoice
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun EcoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context),
        content = content,
    )
}

@Composable
fun ReaderHome() {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val draft = context.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .getString("draft", snapshot.text).orEmpty()
        mutableStateOf(TextFieldValue(draft))
    }
    LaunchedEffect(input.text) {
        delay(500)
        context.getSharedPreferences("reading", Context.MODE_PRIVATE)
            .edit().putString("draft", input.text).apply()
    }
    val editable = snapshot.status == ReadingStatus.IDLE || snapshot.status == ReadingStatus.ERROR
    LaunchedEffect(snapshot.characterOffset, snapshot.text, snapshot.status) {
        if (!editable && snapshot.text != input.text) {
            input = TextFieldValue(snapshot.text)
        }
        if (snapshot.text == input.text) {
            input = input.copy(selection = TextRange(snapshot.characterOffset.coerceIn(0, input.text.length)))
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.offline_voice), color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.read_text_hint)) },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                minLines = 8,
                enabled = editable,
            )
            VoiceControls(snapshot)
            ReaderControls(
                snapshot = snapshot,
                canStart = input.text.isNotBlank(),
                onStart = {
                    val sameReading = snapshot.text == input.text &&
                        snapshot.status == ReadingStatus.IDLE && snapshot.positionMs > 0
                    if (sameReading) sendCommand(context, ReaderPlaybackService.ACTION_PLAY)
                    else sendCommand(context, ReaderPlaybackService.ACTION_READ, input.text)
                },
            )
        }
    }
}

@Composable
fun ReaderQuickPanel(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text,
                    modifier = Modifier.padding(16.dp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            VoiceControls(snapshot)
            ReaderControls(
                snapshot = if (snapshot.text == text) snapshot else snapshot.copy(status = ReadingStatus.IDLE),
                canStart = text.isNotBlank(),
                onStart = {
                    val sameReading = snapshot.text == text &&
                        snapshot.status == ReadingStatus.IDLE && snapshot.positionMs > 0
                    if (sameReading) sendCommand(context, ReaderPlaybackService.ACTION_PLAY)
                    else sendCommand(context, ReaderPlaybackService.ACTION_READ, text)
                },
            )
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.close_panel))
            }
        }
    }
}

@Composable
private fun VoiceControls(snapshot: ReaderSnapshot) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf(false) }
    val editable = snapshot.status == ReadingStatus.IDLE || snapshot.status == ReadingStatus.ERROR
    OutlinedButton(onClick = { expanded = true }, enabled = editable && downloading == null) {
        Text("${stringResource(R.string.choose_voice)}: ${OfflineVoice.option(snapshot.voiceId).label}")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        OfflineVoice.voices.forEach { voice ->
            val installed = OfflineVoice.isInstalled(context, voice)
            DropdownMenuItem(
                text = {
                    Text(if (installed) voice.label else "${voice.label} · ${stringResource(R.string.download_voice)}")
                },
                onClick = {
                    expanded = false
                    if (installed) {
                        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = voice.id)
                        ReaderState.save(context)
                    } else {
                        downloading = voice.id
                        error = false
                        coroutineScope.launch {
                            try {
                                OfflineVoice.install(context, voice) { copied, total ->
                                    if (copied == total || copied % (1024 * 1024) < 65_536) {
                                        progress = copied.toFloat() / total
                                    }
                                }
                                ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = voice.id)
                                ReaderState.save(context)
                            } catch (_: Exception) {
                                error = true
                            } finally {
                                downloading = null
                            }
                        }
                    }
                },
            )
        }
    }
    if (downloading != null) Text("${stringResource(R.string.download_voice)}: ${(progress * 100).toInt()}%")
    if (error) Text(stringResource(R.string.download_failed), color = MaterialTheme.colorScheme.error)
    Text("${stringResource(R.string.speech_speed)}: ${String.format(Locale.US, "%.1f", snapshot.speed)}×")
    Slider(
        value = snapshot.speed,
        onValueChange = { ReaderState.snapshot.value = ReaderState.snapshot.value.copy(speed = it) },
        onValueChangeFinished = { ReaderState.save(context) },
        valueRange = 0.5f..2f,
        enabled = editable,
    )
}

@Composable
private fun ReaderControls(snapshot: ReaderSnapshot, canStart: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val active = snapshot.status == ReadingStatus.PLAYING ||
        snapshot.status == ReadingStatus.PREPARING || snapshot.status == ReadingStatus.PAUSED
    if (snapshot.status == ReadingStatus.PREPARING) {
        Text(stringResource(R.string.preparing_audio), color = MaterialTheme.colorScheme.primary)
    }
    snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!active) {
        Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_reading))
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { sendCommand(context, ReaderPlaybackService.ACTION_BACK) },
            modifier = Modifier.weight(1f),
        ) { Text("−10 s") }
        Button(
            onClick = {
                sendCommand(
                    context,
                    if (snapshot.status == ReadingStatus.PLAYING) ReaderPlaybackService.ACTION_PAUSE
                    else ReaderPlaybackService.ACTION_PLAY,
                )
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (snapshot.status == ReadingStatus.PLAYING) stringResource(R.string.pause_reading)
            else stringResource(R.string.resume_reading))
        }
        OutlinedButton(
            onClick = { sendCommand(context, ReaderPlaybackService.ACTION_FORWARD) },
            modifier = Modifier.weight(1f),
        ) { Text("+10 s") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { sendCommand(context, ReaderPlaybackService.ACTION_RESET) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.reset_reading)) }
        OutlinedButton(
            onClick = { sendCommand(context, ReaderPlaybackService.ACTION_STOP) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.stop_reading)) }
    }
}

private fun sendCommand(context: Context, action: String, text: String? = null) {
    val intent = Intent(context, ReaderPlaybackService::class.java).setAction(action)
    if (text != null) {
        intent.putExtra(ReaderPlaybackService.EXTRA_TEXT, text)
        intent.putExtra(ReaderPlaybackService.EXTRA_VOICE, ReaderState.snapshot.value.voiceId)
        intent.putExtra(ReaderPlaybackService.EXTRA_SPEED, ReaderState.snapshot.value.speed)
    }
    if (action == ReaderPlaybackService.ACTION_READ || action == ReaderPlaybackService.ACTION_PLAY) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}
