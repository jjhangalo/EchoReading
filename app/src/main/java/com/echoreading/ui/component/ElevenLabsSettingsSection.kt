package com.echoreading.ui.component

import android.content.Intent
import android.widget.Toast
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
                    null else "Não foi possível atualizar as vozes."
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
                    Text(if (enabled) "Vozes online · ligação à internet necessária"
                        else "Desativado · a voz offline continua disponível",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { ElevenLabsAuth.setEnabled(context, it) })
            }
            Text(
                "Com as vozes ElevenLabs ativas, o texto a sintetizar é enviado para a ElevenLabs. Sem esta opção, a leitura usa apenas vozes locais.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled) return@Column

            HorizontalDivider()
            if (!connected) {
                Text("Ligue a sua conta ElevenLabs para explorar vozes sem introduzir uma chave de API.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    ElevenLabsAuth.beginSignIn(context).onSuccess { uri ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                            .onFailure { Toast.makeText(context, "Não foi possível abrir o navegador.", Toast.LENGTH_SHORT).show() }
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Autenticação indisponível.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Iniciar sessão na ElevenLabs") }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Conta ligada", style = MaterialTheme.typography.labelLarge)
                    selectedName?.let { Text("Voz: $it", style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = { scope.launch { ElevenLabsAuth.disconnect(context) } }) { Text("Sair") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar na leitura", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = readingWithElevenLabs,
                    enabled = selectedVoice != null,
                    onCheckedChange = { ElevenLabsAuth.setUseForReading(context, it) },
                )
            }
            if (selectedVoice == null) {
                Text("Escolha uma voz para ativar a leitura online.", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Explorar vozes", style = MaterialTheme.typography.titleSmall)
            if (catalogFromAccount == false && NetworkAccess.isAvailable(context)) Text(
                "A mostrar vozes padrão disponíveis para esta conta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nome da voz") },
                trailingIcon = {
                    IconButton(onClick = { loadVoices(search) }, enabled = !loading) {
                        Icon(Icons.Default.Search, contentDescription = "Procurar vozes")
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
                Text("Sem vozes para mostrar.", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Text("Limite desta aplicação por leitura", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1_000, 5_000, 10_000, 20_000).forEach { limit ->
                    FilterChip(
                        selected = maxCharacters == limit,
                        onClick = { ElevenLabsAuth.setMaxCharactersPerReading(context, limit) },
                        label = { Text("${limit / 1_000} mil") },
                    )
                }
            }

            quota?.let { usage ->
                HorizontalDivider()
                Text("Limites da conta · ${usage.tier}", style = MaterialTheme.typography.titleSmall)
                Text("Utilização: ${usage.used} / ${usage.limit}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { (usage.used.toFloat() / usage.limit).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } ?: Text("Limites da conta indisponíveis nesta ligação.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
