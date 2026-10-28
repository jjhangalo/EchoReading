package com.echoreading.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoreading.R
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.ElevenLabsAuth
import com.echoreading.voice.ElevenLabsMcp
import com.echoreading.voice.ElevenLabsVoice
import com.echoreading.voice.NetworkAccess
import com.echoreading.voice.VoiceDownloadManager
import com.echoreading.voice.VoiceDownloadSource
import com.echoreading.voice.VoiceDownloadStatus
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EchoReadingTopAppBar(
    currentRoute: String,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "ECHO READING",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        color = colorScheme.primary
                    )
                    Text(
                        when (currentRoute) {
                            "history" -> stringResource(R.string.tab_history)
                            "settings" -> stringResource(R.string.tab_settings)
                            "transcribe" -> stringResource(R.string.tab_transcribe)
                            else -> stringResource(R.string.tab_home)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorScheme.surface.copy(alpha = 0.95f),
        ),
        actions = {
            VoiceTransferStatusAction()
        },
    )
}

@Composable
fun EchoReadingBottomAppBar(
    currentRoute: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    NavigationBar(
        containerColor = colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        NavigationBarItem(
            selected = currentRoute == "home",
            onClick = { onNavigate("home") },
            icon = {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null
                )
            },
            label = { Text(stringResource(R.string.tab_home)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                selectedIconColor = colorScheme.primary,
                selectedTextColor = colorScheme.primary,
            )
        )
        NavigationBarItem(
            selected = currentRoute == "transcribe",
            onClick = { onNavigate("transcribe") },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_transcribe)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                selectedIconColor = colorScheme.primary,
                selectedTextColor = colorScheme.primary,
            )
        )
        NavigationBarItem(
            selected = currentRoute == "history",
            onClick = { onNavigate("history") },
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_history)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                selectedIconColor = colorScheme.primary,
                selectedTextColor = colorScheme.primary,
            )
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = { onNavigate("settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.tab_settings)) },
            colors = NavigationBarItemDefaults.colors(
                indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                selectedIconColor = colorScheme.primary,
                selectedTextColor = colorScheme.primary,
            )
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StitchStatusVoiceBar(
    snapshot: ReaderSnapshot,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    showSpeed: Boolean = false,
) {
    val context = LocalContext.current
    val revision by ElevenLabsAuth.revision.collectAsState()
    val voiceDownload by VoiceDownloadManager.state.collectAsState()
    var sheetOpen by remember { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf("local") }
    var search by rememberSaveable { mutableStateOf("") }
    var onlineVoices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var onlineLoading by remember { mutableStateOf(false) }
    val accountReady = remember(revision) {
        ElevenLabsAuth.isEnabled(context) && ElevenLabsAuth.isConnected(context)
    }
    val networkAvailable = NetworkAccess.isAvailable(context)
    val onlineAvailable = accountReady && networkAvailable
    val onlineReading = remember(revision) { ElevenLabsAuth.useForReading(context) } && networkAvailable
    val onlineVoiceId = remember(revision) { ElevenLabsAuth.selectedVoiceId(context) }
    val onlineVoiceName = remember(revision) { ElevenLabsAuth.selectedVoiceName(context) }
    val localVoices = OfflineVoice.allVoices(context)
    val activeName = if (onlineReading) onlineVoiceName ?: "ElevenLabs"
        else OfflineVoice.option(context, snapshot.voiceId).label

    LaunchedEffect(sheetOpen) {
        if (sheetOpen) {
            source = if (onlineReading && onlineAvailable) "online" else "local"
            search = ""
        }
    }
    LaunchedEffect(sheetOpen, source, search, onlineAvailable) {
        if (sheetOpen && source == "online" && onlineAvailable) {
            if (search.isNotBlank()) delay(300)
            onlineLoading = true
            onlineVoices = emptyList()
            onlineVoices = ElevenLabsMcp.voices(context, search).getOrNull()?.voices.orEmpty()
            onlineLoading = false
        }
    }

    LaunchedEffect(voiceDownload.completions) {
        voiceDownload.completions.forEach { completion ->
            if (completion.success && completion.key.isNotBlank()) {
                ElevenLabsAuth.setUseForReading(context, false)
                ReaderState.setVoice(context, completion.key)
            }
            VoiceDownloadManager.acknowledge(completion.requestId)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Voz e ritmo", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = colorScheme.surface,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { sheetOpen = true },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(activeName, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(if (onlineReading) "ElevenLabs · online" else "No dispositivo · offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = "Escolher voz")
                }
            }
            if (showSpeed) {
                Text("Velocidade de leitura", style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { value ->
                        FilterChip(
                            selected = snapshot.speed == value,
                            onClick = {
                                ReaderState.setSpeed(context, value)
                            },
                            enabled = enabled,
                            label = { Text("${value}x") },
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Escolher voz", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            PrimaryTabRow(selectedTabIndex = if (source == "online" && onlineAvailable) 1 else 0) {
                Tab(selected = source == "local", onClick = { source = "local" },
                    text = { Text("No dispositivo") })
                if (onlineAvailable) Tab(selected = source == "online", onClick = { source = "online" },
                    text = { Text("ElevenLabs") })
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pesquisar voz") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
            val listHeight = minOf(480.dp, LocalConfiguration.current.screenHeightDp.dp * 0.55f)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = listHeight)) {
                if (source == "local") {
                    val matches = localVoices.filter {
                        it.label.contains(search.trim(), ignoreCase = true)
                    }
                    items(matches, key = { it.id }) { voice ->
                        val installed = OfflineVoice.isInstalled(context, voice)
                        val isDownloading = voiceDownload.status == VoiceDownloadStatus.DOWNLOADING &&
                            voiceDownload.key == voice.id
                        val queueIndex = voiceDownload.queued.indexOfFirst { it.key == voice.id }
                        val isQueued = queueIndex >= 0
                        val canSelect = installed || (!isDownloading && !isQueued)
                        ListItem(
                            headlineContent = { Text(voice.label, maxLines = 2) },
                            supportingContent = {
                                Text(
                                    when {
                                        installed -> "Disponível no dispositivo"
                                        isDownloading -> "A transferir…"
                                        isQueued -> "Na fila · ${queueIndex + 1}.º"
                                        else -> "Transferir voz"
                                    },
                                )
                            },
                            trailingContent = {
                                when {
                                    isDownloading -> CircularProgressIndicator(Modifier.size(20.dp))
                                    isQueued -> Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = "Na fila",
                                    )
                                    snapshot.voiceId == voice.id && !onlineReading ->
                                        Icon(Icons.Default.Check, contentDescription = "Selecionada")
                                }
                            },
                            modifier = Modifier.clickable(enabled = canSelect) {
                                if (installed) {
                                    ElevenLabsAuth.setUseForReading(context, false)
                                    ReaderState.setVoice(context, voice.id)
                                    sheetOpen = false
                                } else {
                                    VoiceDownloadManager.start(
                                        context = context.applicationContext,
                                        key = voice.id,
                                        label = voice.label,
                                        source = VoiceDownloadSource.LOCAL,
                                    )
                                }
                            },
                        )
                    }
                    if (matches.isEmpty()) item {
                        Text("Sem vozes correspondentes.", Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    if (onlineLoading) item {
                        CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                    }
                    items(onlineVoices, key = { it.id }) { voice ->
                        ListItem(
                            headlineContent = { Text(voice.name, maxLines = 2) },
                            supportingContent = { Text("ElevenLabs · online") },
                            trailingContent = {
                                if (onlineVoiceId == voice.id && onlineReading)
                                    Icon(Icons.Default.Check, contentDescription = "Selecionada")
                            },
                            modifier = Modifier.clickable {
                                ElevenLabsAuth.selectVoice(context, voice.id, voice.name)
                                ElevenLabsAuth.setUseForReading(context, true)
                                ReaderState.refreshVoice(context)
                                sheetOpen = false
                            },
                        )
                    }
                    if (!onlineLoading && onlineVoices.isEmpty()) item {
                        Text("Sem vozes online disponíveis.", Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
