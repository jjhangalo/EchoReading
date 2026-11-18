package com.echoreading.ui.component

import android.content.Intent
import android.widget.Toast
import com.echoreading.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.echoreading.voice.ElevenLabsAuth
import com.echoreading.voice.ElevenLabsMcp
import com.echoreading.voice.ElevenLabsQuota
import com.echoreading.voice.ElevenLabsVoice
import com.echoreading.voice.NetworkAccess
import kotlinx.coroutines.launch

@Composable
fun ElevenLabsSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by ElevenLabsAuth.revision.collectAsState()
    val enabled = remember(revision) { ElevenLabsAuth.isEnabled(context) }
    val connected = remember(revision) { enabled && ElevenLabsAuth.isConnected(context) }
    val readingWithElevenLabs = remember(revision) { ElevenLabsAuth.useForReading(context) }
    val selectedVoice = remember(revision) { ElevenLabsAuth.selectedVoiceId(context) }
    val selectedName = remember(revision) { ElevenLabsAuth.selectedVoiceName(context) }
    val maxCharacters = remember(revision) { ElevenLabsAuth.maxCharactersPerReading(context) }
    var voices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var catalogFromAccount by remember { mutableStateOf<Boolean?>(null) }
    var quota by remember { mutableStateOf<ElevenLabsQuota?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val browserError = stringResource(R.string.elevenlabs_browser_error)
    val authUnavailable = stringResource(R.string.elevenlabs_auth_unavailable)
    val voicesRefreshFailed = stringResource(R.string.voices_refresh_failed)

    fun loadVoices(query: String = "") {
        scope.launch {
            if (!NetworkAccess.isAvailable(context)) {
                voices = emptyList()
                error = null
                loading = false
                return@launch
            }
            loading = true
            error = null
            ElevenLabsMcp.voices(context, query).onSuccess {
                voices = it.voices
                catalogFromAccount = it.fromAccount
                if (query.isBlank() && selectedVoice != null &&
                    it.voices.none { voice -> voice.id == selectedVoice }
                ) ElevenLabsAuth.setUseForReading(context, false)
            }.onFailure {
                voices = emptyList()
                error = if (NetworkAccess.isConnectionFailure(it) || !NetworkAccess.isAvailable(context))
                    null else voicesRefreshFailed
            }
            loading = false
        }
    }

    LaunchedEffect(enabled, connected) {
        if (connected && NetworkAccess.isAvailable(context)) {
            loadVoices()
            quota = ElevenLabsMcp.quota(context).getOrNull()
        } else {
            voices = emptyList()
            catalogFromAccount = null
            quota = null
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ElevenLabs", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Text(if (enabled) stringResource(R.string.elevenlabs_online_subtitle)
                        else stringResource(R.string.elevenlabs_offline_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { ElevenLabsAuth.setEnabled(context, it) })
            }
            Text(
                stringResource(R.string.elevenlabs_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled) return@Column

            HorizontalDivider()
            if (!connected) {
                Text(stringResource(R.string.elevenlabs_connect_hint),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    ElevenLabsAuth.beginSignIn(context).onSuccess { uri ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                            .onFailure { Toast.makeText(context, browserError, Toast.LENGTH_SHORT).show() }
                    }.onFailure {
                        Toast.makeText(context, it.message ?: authUnavailable, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.elevenlabs_sign_in)) }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.elevenlabs_connected), style = MaterialTheme.typography.labelLarge)
                    selectedName?.let { Text(stringResource(R.string.elevenlabs_voice_prefix, it), style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = { scope.launch { ElevenLabsAuth.disconnect(context) } }) { Text(stringResource(R.string.elevenlabs_sign_out)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.elevenlabs_use_reading), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = readingWithElevenLabs,
                    enabled = selectedVoice != null,
                    onCheckedChange = { ElevenLabsAuth.setUseForReading(context, it) },
                )
            }
            if (selectedVoice == null) {
                Text(stringResource(R.string.elevenlabs_pick_voice_hint), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text(stringResource(R.string.elevenlabs_explore), style = MaterialTheme.typography.titleSmall)
            if (catalogFromAccount == false && NetworkAccess.isAvailable(context)) Text(
                stringResource(R.string.elevenlabs_default_voices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.elevenlabs_voice_name)) },
                trailingIcon = {
                    IconButton(onClick = { loadVoices(search) }, enabled = !loading) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.elevenlabs_search_voices))
                    }
                },
                singleLine = true,
            )
            if (loading) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            voices.take(30).forEach { voice ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = selectedVoice == voice.id,
                        role = Role.RadioButton,
                        onClick = { ElevenLabsAuth.selectVoice(context, voice.id, voice.name) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedVoice == voice.id, onClick = null)
                    Text(voice.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!loading && voices.isEmpty() && error == null && NetworkAccess.isAvailable(context)) {
                Text(stringResource(R.string.elevenlabs_no_voices), style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text(stringResource(R.string.elevenlabs_app_limit), style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1_000, 5_000, 10_000, 20_000).forEach { limit ->
                    FilterChip(
                        selected = maxCharacters == limit,
                        onClick = { ElevenLabsAuth.setMaxCharactersPerReading(context, limit) },
                        label = { Text(stringResource(R.string.elevenlabs_thousand, limit / 1_000)) },
                    )
                }
            }

            quota?.let { usage ->
                HorizontalDivider()
                Text(stringResource(R.string.elevenlabs_account_limits, usage.tier), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.elevenlabs_usage, usage.used, usage.limit), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { (usage.used.toFloat() / usage.limit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: Text(stringResource(R.string.elevenlabs_limits_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
