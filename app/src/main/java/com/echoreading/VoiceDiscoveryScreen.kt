package com.echoreading

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echoreading.reader.WavFiles
import com.echoreading.voice.CatalogVoice
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.VoiceCatalog
import com.echoreading.voice.sampleGreetingFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
fun VoiceDiscoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Catalog state
    var catalog by remember { mutableStateOf<List<CatalogVoice>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Filter & search state
    var searchQuery by remember { mutableStateOf("") }
    var selectedFamily by remember { mutableStateOf<String?>(null) }

    // Download state
    var downloadingKey by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Installed cache invalidation tracker
    var installedVersion by remember { mutableIntStateOf(0) }

    // Preview audio player state
    var playingVoiceKey by remember { mutableStateOf<String?>(null) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            playJob?.cancel()
            playJob = null
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
            playingVoiceKey = null
        }
    }

    fun loadCatalog(forceRefresh: Boolean = false) {
        scope.launch {
            if (forceRefresh) isRefreshing = true else isLoading = true
            error = null
            val result = if (forceRefresh) VoiceCatalog.refresh(context) else VoiceCatalog.load(context)
            result.onSuccess {
                catalog = it
                isLoading = false
                isRefreshing = false
            }.onFailure {
                error = it.message ?: "Falha ao carregar o catálogo de vozes"
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCatalog(forceRefresh = false)
    }

    fun playSample(voice: CatalogVoice) {
        playJob?.cancel()
        playJob = null
        if (playingVoiceKey != null) {
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
            val wasCurrent = playingVoiceKey == voice.key
            playingVoiceKey = null
            if (wasCurrent) return
        }

        playingVoiceKey = voice.key
        playJob = scope.launch {
            try {
                val modelFileName = voice.onnxFilePath.substringAfterLast('/')
                val hardcoded = OfflineVoice.voices.firstOrNull { it.modelFile == modelFileName }
                val voiceId = if (hardcoded != null && OfflineVoice.isInstalled(context, hardcoded)) hardcoded.id else voice.key
                val greeting = sampleGreetingFor(voice)
                val audio = withContext(Dispatchers.IO) {
                    OfflineVoice.synthesize(context, greeting, voiceId, 1f)
                }
                val sampleFile = File(context.cacheDir, "sample_preview_${voice.key}.wav")
                WavFiles.write(sampleFile, audio)
                val mp = MediaPlayer.create(context, Uri.fromFile(sampleFile))
                activePlayer = mp
                mp?.setOnCompletionListener {
                    it.release()
                    activePlayer = null
                    playingVoiceKey = null
                    playJob = null
                }
                mp?.start()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    playingVoiceKey = null
                    playJob = null
                    Toast.makeText(context, "Erro ao reproduzir amostra: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Filtered voices
    val filtered = remember(catalog, searchQuery, selectedFamily) {
        catalog.filter { voice ->
            val matchesFamily = selectedFamily == null ||
                voice.languageFamily.equals(selectedFamily, ignoreCase = true)
            val q = searchQuery.trim()
            val matchesSearch = q.isBlank() ||
                voice.name.contains(q, ignoreCase = true) ||
                voice.languageEnglish.contains(q, ignoreCase = true) ||
                voice.languageNative.contains(q, ignoreCase = true) ||
                voice.languageCode.contains(q, ignoreCase = true) ||
                voice.countryEnglish.contains(q, ignoreCase = true) ||
                voice.key.contains(q, ignoreCase = true)
            matchesFamily && matchesSearch
        }
    }

    // Available language families
    val families = remember(catalog) {
        catalog.groupBy { it.languageFamily }
            .map { (code, voices) ->
                val englishName = voices.first().languageEnglish
                val nativeName = voices.first().languageNative
                val displayName = if (nativeName.isNotEmpty() && !nativeName.equals(englishName, ignoreCase = true)) {
                    "$nativeName ($englishName)"
                } else {
                    englishName
                }
                Triple(code, displayName, voices.size)
            }
            .sortedBy { it.second }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Breadcrumb Top Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onBack() }
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Voz",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    " / Descobrir",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { loadCatalog(forceRefresh = true) },
                enabled = !isLoading && !isRefreshing
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Atualizar catálogo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Pesquisar por nome, idioma ou país…") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Limpar pesquisa")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
        )

        Spacer(Modifier.height(10.dp))

        // Language Filter Chips
        if (families.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFamily == null,
                        onClick = { selectedFamily = null },
                        label = { Text("Todos (${catalog.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
                items(families, key = { it.first }) { (code, name, count) ->
                    FilterChip(
                        selected = selectedFamily == code,
                        onClick = {
                            selectedFamily = if (selectedFamily == code) null else code
                        },
                        label = { Text("$name ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Content Area
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "A carregar catálogo de vozes…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "Não foi possível carregar o catálogo",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Verifique a ligação à internet e tente novamente.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = { loadCatalog(forceRefresh = true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Tentar novamente")
                            }
                        }
                    }
                }
            }
            filtered.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Nenhuma voz encontrada",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Tente ajustar a pesquisa ou o filtro de idioma.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (searchQuery.isNotEmpty() || selectedFamily != null) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = {
                                    searchQuery = ""
                                    selectedFamily = null
                                }
                            ) {
                                Text("Limpar filtros")
                            }
                        }
                    }
                }
            }
            else -> {
                val groups = remember(filtered) {
                    filtered.groupBy { voice ->
                        if (voice.countryEnglish.isNotEmpty()) {
                            "${voice.languageEnglish} (${voice.countryEnglish})"
                        } else {
                            voice.languageEnglish
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    groups.forEach { (header, voices) ->
                        stickyHeader(key = "header_$header") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        header,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ) {
                                        Text(
                                            "${voices.size}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        items(voices, key = { it.key }) { voice ->
                            val isInstalled = remember(voice.key, installedVersion) {
                                isVoiceInstalled(context, voice)
                            }
                            val isDownloading = downloadingKey == voice.key
                            val isPlayingThis = playingVoiceKey == voice.key

                            VoiceDiscoveryCard(
                                voice = voice,
                                isInstalled = isInstalled,
                                isDownloading = isDownloading,
                                canDownload = downloadingKey == null,
                                progress = if (isDownloading) downloadProgress else 0f,
                                isPlaying = isPlayingThis,
                                onPlaySample = { playSample(voice) },
                                onDownload = {
                                    if (downloadingKey != null) return@VoiceDiscoveryCard
                                    downloadingKey = voice.key
                                    downloadProgress = 0f
                                    downloadJob = scope.launch {
                                        try {
                                            OfflineVoice.installFromCatalog(context, voice) { copied, total ->
                                                if (total > 0) {
                                                    downloadProgress = copied.toFloat() / total
                                                }
                                            }
                                            installedVersion++
                                            Toast.makeText(
                                                context,
                                                "Voz ${voice.name.replaceFirstChar { it.uppercase() }} transferida com sucesso!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } catch (e: Exception) {
                                            if (e !is CancellationException) {
                                                Toast.makeText(
                                                    context,
                                                    "Erro ao transferir voz: ${e.message ?: "Falha na transferência"}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        } finally {
                                            downloadingKey = null
                                            downloadJob = null
                                        }
                                    }
                                },
                                onCancel = {
                                    downloadJob?.cancel()
                                    downloadJob = null
                                    downloadingKey = null
                                    Toast.makeText(context, "Transferência cancelada", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceDiscoveryCard(
    voice: CatalogVoice,
    isInstalled: Boolean,
    isDownloading: Boolean,
    canDownload: Boolean,
    progress: Float,
    isPlaying: Boolean,
    onPlaySample: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Voice Name & Status/Actions
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        voice.name.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            val native = voice.languageNative.ifEmpty { voice.languageEnglish }
                            append(native)
                            if (voice.countryEnglish.isNotEmpty()) {
                                append(" • ${voice.countryEnglish}")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Action area
                when {
                    isDownloading -> {
                        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancelar download",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    isInstalled -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        "Instalada",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            FilledTonalButton(
                                onClick = onPlaySample,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (isPlaying) "Pausar" else "Ouvir",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onDownload,
                            enabled = canDownload,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Descarregar", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Downloading Progress Bar
            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "A transferir modelo neural…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Bottom Badges: Quality, Size, Speakers
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Quality pill
                val qualityLabel = when (voice.quality.lowercase()) {
                    "high" -> "Alta qualidade"
                    "medium" -> "Média qualidade"
                    "low" -> "Qualidade leve"
                    "x_low" -> "Qualidade ultraleve"
                    else -> voice.quality.replaceFirstChar { it.uppercase() }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        qualityLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Size pill
                val sizeMb = voice.onnxSizeBytes / (1024f * 1024f)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        "${String.format(Locale.US, "%.1f", sizeMb)} MB",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Speaker count (if > 1)
                if (voice.numSpeakers > 1) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            "${voice.numSpeakers} falantes",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

/** Check whether a catalog voice is already installed on disk (either bundled or downloaded). */
fun isVoiceInstalled(context: Context, voice: CatalogVoice): Boolean {
    val modelFileName = voice.onnxFilePath.substringAfterLast('/')
    val installed = OfflineVoice.allVoices(context).filter { OfflineVoice.isInstalled(context, it) }
    if (installed.any { it.modelFile == modelFileName }) return true
    val voiceDir = File(context.noBackupFilesDir, "voices/${voice.key}")
    val file = File(voiceDir, modelFileName)
    return file.isFile && file.length() >= voice.onnxSizeBytes
}
