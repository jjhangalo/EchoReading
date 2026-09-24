package com.echoreading

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echoreading.reader.ReaderAudioCache
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingHistory
import com.echoreading.reader.WavFiles
import com.echoreading.voice.OfflineVoice
import com.echoreading.voice.VoiceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun SettingsScreen() {
    var currentView by rememberSaveable { mutableStateOf("main") }

    when (currentView) {
        "main" -> SettingsMainMenu(onNavigate = { currentView = it })
        "personalizacao" -> SettingsTheme(onBack = { currentView = "main" })
        "voz" -> SettingsVoice(onBack = { currentView = "main" })
        "armazenamento" -> SettingsStorage(onBack = { currentView = "main" })
    }
}

// ---------------------------------------------------------------------------
// VIEW 1: MAIN SETTINGS MENU
// ---------------------------------------------------------------------------
@Composable
private fun SettingsMainMenu(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val installedCount = OfflineVoice.voices.count { OfflineVoice.isInstalled(context, it) }

    val voiceBytes = remember {
        File(context.noBackupFilesDir, "voices").walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    val cacheBytes = remember {
        context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    val historyBytes = remember { ReadingHistory.sizeBytes(context) }
    val totalMb = (voiceBytes + cacheBytes + historyBytes) / (1024f * 1024f)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Stitch Hero Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.settings_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Category Cards List
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Category 1: Personalização
            SettingsCategoryCard(
                icon = Icons.Default.Palette,
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                title = stringResource(R.string.settings_theme),
                badgeText = "Tema e Cores",
                badgePrimary = false,
                subtitle = stringResource(R.string.settings_theme_sub),
                onClick = { onNavigate("personalizacao") }
            )

            // Category 2: Voz
            SettingsCategoryCard(
                icon = Icons.Default.RecordVoiceOver,
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                title = stringResource(R.string.settings_voice),
                badgeText = "$installedCount Instaladas",
                badgePrimary = true,
                subtitle = stringResource(R.string.settings_voice_sub),
                onClick = { onNavigate("voz") }
            )

            // Category 3: Armazenamento e Privacidade
            SettingsCategoryCard(
                icon = Icons.Default.Security,
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                title = stringResource(R.string.settings_storage),
                badgeText = "${String.format(Locale.US, "%.0f", totalMb)} MB",
                badgePrimary = false,
                subtitle = stringResource(R.string.settings_storage_sub),
                onClick = { onNavigate("armazenamento") }
            )
        }

        Spacer(Modifier.height(4.dp))

        // Quick Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Default.Verified,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        "Eco Leitura Motor v2.4 (Material 3)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Sintetizador neural ativo offline com privacidade protegida",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    title: String,
    badgeText: String,
    badgePrimary: Boolean,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = iconContainerColor,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Surface(
                    shape = CircleShape,
                    color = if (badgePrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (badgePrimary) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (badgePrimary) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// VIEW 2: PERSONALIZAÇÃO (THEME & MATERIAL YOU)
// ---------------------------------------------------------------------------
@Composable
private fun SettingsTheme(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var themeMode by rememberSaveable { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var dynamicColors by rememberSaveable { mutableStateOf(prefs.getBoolean("dynamic_colors", false)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Breadcrumb Back Header
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "TEMA E CORES",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Section: Modo de Exibição
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.DarkMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            stringResource(R.string.theme_mode),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Escolha como a app deve ser apresentada",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3-Option Segmented Pill Bar
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Triple("light", R.string.theme_light, Icons.Default.LightMode),
                            Triple("dark", R.string.theme_dark, Icons.Default.DarkMode),
                            Triple("system", R.string.theme_system, Icons.Default.BrightnessAuto),
                        ).forEach { (key, labelRes, icon) ->
                            val isSelected = themeMode == key
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest
                                else Color.Transparent,
                                shadowElevation = if (isSelected) 1.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        themeMode = key
                                        prefs.edit().putString("theme", key).apply()
                                    }
                            ) {
                                Row(
                                    Modifier.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(labelRes),
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Section: Dynamic Colors
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dynamic_colors),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.dynamic_colors_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = dynamicColors,
                    onCheckedChange = {
                        dynamicColors = it
                        prefs.edit().putBoolean("dynamic_colors", it).apply()
                    },
                    thumbContent = if (dynamicColors) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// VIEW 3: VOZ (VOICE MANAGEMENT & AUDITION)
// ---------------------------------------------------------------------------
@Composable
private fun SettingsVoice(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadCopiedMb by remember { mutableFloatStateOf(0f) }

    var playingVoiceId by remember { mutableStateOf<String?>(null) }
    var isDemonstratingActive by remember { mutableStateOf(false) }
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var voiceToDelete by remember { mutableStateOf<VoiceOption?>(null) }

    fun playSample(voice: VoiceOption, speed: Float = snapshot.speed) {
        if (playingVoiceId != null) {
            activePlayer?.stop()
            activePlayer?.release()
            activePlayer = null
            playingVoiceId = null
            isDemonstratingActive = false
            return
        }
        playingVoiceId = voice.id
        if (voice.id == snapshot.voiceId) isDemonstratingActive = true

        scope.launch {
            try {
                val sampleText = if (voice.id.startsWith("en")) {
                    "Hello! This is a preview of this voice reading text aloud."
                } else if (voice.id.contains("BR")) {
                    "Olá! Esta é uma demonstração da voz brasileira lendo texto em voz alta."
                } else {
                    "Olá! Esta é uma demonstração desta voz lendo texto em voz alta com clareza e ritmo natural."
                }
                val audio = withContext(Dispatchers.IO) {
                    OfflineVoice.synthesize(context, sampleText, voice.id, speed)
                }
                val sampleFile = File(context.cacheDir, "sample_${voice.id}.wav")
                WavFiles.write(sampleFile, audio)
                val mp = MediaPlayer.create(context, Uri.fromFile(sampleFile))
                activePlayer = mp
                mp?.setOnCompletionListener {
                    it.release()
                    activePlayer = null
                    playingVoiceId = null
                    isDemonstratingActive = false
                }
                mp?.start()
            } catch (e: Exception) {
                playingVoiceId = null
                isDemonstratingActive = false
                Toast.makeText(context, "Erro ao reproduzir amostra: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val installedVoices = OfflineVoice.voices.filter { OfflineVoice.isInstalled(context, it) }
    val installedMb = installedVoices.sumOf {
        if (it.url == null) 63L * 1024 * 1024 else it.fileSize
    } / (1024f * 1024f)

    val downloadableVoices = OfflineVoice.voices.filter { !OfflineVoice.isInstalled(context, it) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Breadcrumb Back Header
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "GESTÃO TTS",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // -------------------------------------------------------------------
        // SECTION A: VOZES INSTALADAS
        // -------------------------------------------------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "VOZES INSTALADAS (NO DISPOSITIVO)",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    "${installedVoices.size} vozes • ${String.format(Locale.US, "%.0f", installedMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            installedVoices.forEach { voice ->
                val isSelected = snapshot.voiceId == voice.id
                val isPlaying = playingVoiceId == voice.id

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = voice.id)
                            ReaderState.save(context)
                        },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    )
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isSelected) Icons.Default.Mic else Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    voice.label,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        if (voice.url == null) "PADRÃO" else "NEURAL HD",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${if (voice.url == null) "Portugal · Offline integrado (63 MB)" else "Transferida (${String.format(Locale.US, "%.0f", voice.fileSize / (1024f * 1024f))} MB)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { playSample(voice) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                if (isPlaying) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Ouvir Amostra",
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (voice.url != null) {
                                IconButton(
                                    onClick = { voiceToDelete = voice },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Remover",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // SECTION B: DISPONÍVEIS PARA TRANSFERÊNCIA
        // -------------------------------------------------------------------
        if (downloadableVoices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "DISPONÍVEIS PARA TRANSFERÊNCIA",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "Nuvem TTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                downloadableVoices.forEach { voice ->
                    val isDownloading = downloadingId == voice.id
                    val sizeMb = voice.fileSize / (1024f * 1024f)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(
                                if (isDownloading) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        )
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isDownloading) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isDownloading) {
                                            val infiniteTransition = rememberInfiniteTransition(label = "downloading")
                                            val rotation by infiniteTransition.animateFloat(
                                                initialValue = 0f,
                                                targetValue = 360f,
                                                animationSpec = infiniteRepeatable(
                                                    animation = tween(1200, easing = LinearEasing),
                                                    repeatMode = RepeatMode.Restart
                                                ),
                                                label = "rotation"
                                            )
                                            Icon(
                                                Icons.Default.Sync,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .rotate(rotation)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.RecordVoiceOver,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            voice.label,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                "NEURAL",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${String.format(Locale.US, "%.1f", sizeMb)} MB · Pacote de Voz Neural",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (!isDownloading) {
                                    Button(
                                        onClick = {
                                            downloadingId = voice.id
                                            downloadProgress = 0f
                                            downloadCopiedMb = 0f
                                            downloadJob = scope.launch {
                                                try {
                                                    OfflineVoice.install(context, voice) { copied, total ->
                                                        if (total > 0) {
                                                            downloadProgress = copied.toFloat() / total
                                                            downloadCopiedMb = copied / (1024f * 1024f)
                                                        }
                                                    }
                                                    ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = voice.id)
                                                    ReaderState.save(context)
                                                    Toast.makeText(context, "${voice.label} pronta a usar!", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    if (e !is kotlinx.coroutines.CancellationException) {
                                                        Toast.makeText(context, context.getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
                                                    }
                                                } finally {
                                                    downloadingId = null
                                                    downloadProgress = 0f
                                                }
                                            }
                                        },
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            horizontal = 14.dp,
                                            vertical = 8.dp
                                        )
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${String.format(Locale.US, "%.0f", sizeMb)} MB",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${(downloadProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                downloadJob?.cancel()
                                                downloadingId = null
                                                downloadProgress = 0f
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Cancelar",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            if (isDownloading) {
                                LinearProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(CircleShape),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "A descarregar modelo neural...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "${String.format(Locale.US, "%.1f", downloadCopiedMb)} MB / ${String.format(Locale.US, "%.1f", sizeMb)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // SECTION C: AJUSTES DA VOZ SELECIONADA
        // -------------------------------------------------------------------
        val activeVoice = OfflineVoice.option(snapshot.voiceId)
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Ajustes da Voz (${activeVoice.label.substringAfterLast("·").trim()})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Ativa",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Speed Tuning
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Velocidade de Leitura",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                "${snapshot.speed}x",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        speeds.forEach { s ->
                            val selected = snapshot.speed == s
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    ReaderState.snapshot.value = ReaderState.snapshot.value.copy(speed = s)
                                    ReaderState.save(context)
                                },
                                label = {
                                    Text(
                                        "${s}x",
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }

                // Audio Sample Audition Button
                Button(
                    onClick = { playSample(activeVoice, snapshot.speed) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        if (isDemonstratingActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isDemonstratingActive) "A reproduzir demonstração…" else "Ouvir Demonstração da Voz",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // Voice Deletion Confirmation Dialog
        voiceToDelete?.let { voice ->
            AlertDialog(
                onDismissRequest = { voiceToDelete = null },
                title = { Text("Eliminar voz ${voice.label}?") },
                text = {
                    Text("Esta voz libertará ${String.format(Locale.US, "%.0f", voice.fileSize / (1024f * 1024f))} MB de armazenamento. Poderá transferi-la novamente a qualquer momento.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val dir = File(context.noBackupFilesDir, "voices/${voice.id}")
                            dir.deleteRecursively()
                            if (snapshot.voiceId == voice.id) {
                                ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = "pt-PT")
                                ReaderState.save(context)
                            }
                            voiceToDelete = null
                            Toast.makeText(context, "Voz eliminada", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text(stringResource(R.string.delete_entry), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { voiceToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// VIEW 4: ARMAZENAMENTO E PRIVACIDADE
// ---------------------------------------------------------------------------
@Composable
private fun SettingsStorage(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    var voiceBytes by remember {
        mutableLongStateOf(
            File(context.noBackupFilesDir, "voices").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        )
    }
    var cacheBytes by remember {
        mutableLongStateOf(
            context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        )
    }
    var historyBytes by remember {
        mutableLongStateOf(ReadingHistory.sizeBytes(context))
    }

    val totalBytes = voiceBytes + cacheBytes + historyBytes
    val totalMb = totalBytes / (1024f * 1024f)
    val voiceMb = voiceBytes / (1024f * 1024f)
    val cacheMb = cacheBytes / (1024f * 1024f)
    val historyMb = historyBytes / (1024f * 1024f)

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var saveHistory by rememberSaveable {
        mutableStateOf(prefs.getBoolean("save_history", true))
    }

    var showClearVoicesDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    fun recalculateSizes() {
        voiceBytes = File(context.noBackupFilesDir, "voices").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        cacheBytes = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        historyBytes = ReadingHistory.sizeBytes(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Breadcrumb Back Header
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.tab_settings),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "MEMÓRIA & DADOS",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Section 1: Storage Summary Card
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            "Resumo de Armazenamento",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "${String.format(Locale.US, "%.1f", totalMb)} MB",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Stitch Segmented Multi-Color Bar
                val safeTotal = if (totalBytes > 0) totalBytes.toFloat() else 1f
                val voiceFraction = (voiceBytes / safeTotal).coerceIn(0f, 1f)
                val cacheFraction = (cacheBytes / safeTotal).coerceIn(0f, 1f)
                val historyFraction = (historyBytes / safeTotal).coerceIn(0f, 1f)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            if (voiceFraction > 0.01f) {
                                Box(
                                    Modifier
                                        .weight(voiceFraction)
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            if (cacheFraction > 0.01f) {
                                Box(
                                    Modifier
                                        .weight(cacheFraction)
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                )
                            }
                            if (historyFraction > 0.01f) {
                                Box(
                                    Modifier
                                        .weight(historyFraction)
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.tertiary)
                                )
                            }
                        }
                    }

                    // Legend
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Text(
                                "Vozes: ${String.format(Locale.US, "%.1f", voiceMb)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            )
                            Text(
                                "Cache: ${String.format(Locale.US, "%.1f", cacheMb)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                            )
                            Text(
                                "Textos: ${String.format(Locale.US, "%.2f", historyMb)} MB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

                // Breakdown list
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StorageStatRow(
                        label = "Espaço total ocupado pela app",
                        value = "${String.format(Locale.US, "%.1f", totalMb)} MB",
                        isHighlight = true
                    )
                    StorageStatRow(
                        label = "Dados de vozes instaladas",
                        value = "${String.format(Locale.US, "%.1f", voiceMb)} MB"
                    )
                    StorageStatRow(
                        label = "Textos guardados no histórico",
                        value = "${String.format(Locale.US, "%.2f", historyMb)} MB"
                    )
                    StorageStatRow(
                        label = "Memória Cache temporária",
                        value = "${String.format(Locale.US, "%.1f", cacheMb)} MB"
                    )
                }
            }
        }

        // Section 2: Ações de Limpeza
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        "Ações de Limpeza",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                // Action 1: Delete downloaded voices
                StorageActionItem(
                    title = "Eliminar vozes transferidas",
                    subtitle = "Libertará ${String.format(Locale.US, "%.0f", voiceMb)} MB de ficheiros descarregados",
                    subtitleColor = MaterialTheme.colorScheme.error,
                    buttonText = "Eliminar",
                    isDestructive = true,
                    onClick = { showClearVoicesDialog = true }
                )

                // Action 2: Delete saved texts (preserves favorites!)
                StorageActionItem(
                    title = "Eliminar textos salvos",
                    subtitle = "Limpa o histórico local mantendo os favoritos",
                    subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    buttonText = "Limpar",
                    isDestructive = false,
                    onClick = { showClearHistoryDialog = true }
                )

                // Action 3: Delete cache
                StorageActionItem(
                    title = "Eliminar cache",
                    subtitle = "Ficheiros temporários e áudios renderizados",
                    subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    buttonText = "Ação Imediata",
                    isPrimary = true,
                    onClick = {
                        ReaderAudioCache.clear()
                        context.cacheDir.deleteRecursively()
                        recalculateSizes()
                        Toast.makeText(context, "Cache de áudio limpa", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        // Section 3: History Toggle
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
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.save_history_toggle),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        stringResource(R.string.save_history_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = saveHistory,
                    onCheckedChange = {
                        saveHistory = it
                        prefs.edit().putBoolean("save_history", it).apply()
                    },
                    thumbContent = if (saveHistory) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        // Section 4: Privacy Callout Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                )
            )
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.privacy_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.privacy_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // Dialog: Confirm Delete Downloaded Voices
        if (showClearVoicesDialog) {
            AlertDialog(
                onDismissRequest = { showClearVoicesDialog = false },
                title = { Text("Eliminar vozes transferidas?") },
                text = {
                    Text("Todas as vozes adicionais descarregadas serão removidas (${String.format(Locale.US, "%.0f", voiceMb)} MB). A voz padrão embutida permanecerá disponível.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            File(context.noBackupFilesDir, "voices").listFiles()?.forEach { f ->
                                if (f.name != "tokens.txt") f.deleteRecursively()
                            }
                            if (snapshot.voiceId != "pt-PT") {
                                ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = "pt-PT")
                                ReaderState.save(context)
                            }
                            recalculateSizes()
                            showClearVoicesDialog = false
                            Toast.makeText(context, "Vozes transferidas eliminadas", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearVoicesDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        // Dialog: Confirm Delete Saved Texts (Keeping Favorites!)
        if (showClearHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showClearHistoryDialog = false },
                title = { Text(stringResource(R.string.clear_confirm_title)) },
                text = { Text(stringResource(R.string.clear_confirm_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                ReadingHistory.clearNonFavorites()
                                recalculateSizes()
                                showClearHistoryDialog = false
                                Toast.makeText(context, "Histórico limpo (favoritos mantidos)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Limpar", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearHistoryDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun StorageStatRow(
    label: String,
    value: String,
    isHighlight: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isHighlight) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (isHighlight) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StorageActionItem(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    buttonText: String,
    isDestructive: Boolean = false,
    isPrimary: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor
                )
            }

            if (isDestructive) {
                OutlinedButton(
                    onClick = onClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error)
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp
                    )
                ) {
                    Text(
                        buttonText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            } else if (isPrimary) {
                Button(
                    onClick = onClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp
                    )
                ) {
                    Text(
                        buttonText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onClick() }
                ) {
                    Text(
                        buttonText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
