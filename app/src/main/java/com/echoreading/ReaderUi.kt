package com.echoreading

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echoreading.reader.ReaderPlaybackService
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.reader.ReadingStatus
import com.echoreading.voice.OfflineVoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Exact Material Design 3 Palette from Stitch DESIGN.md
val StitchLightColorScheme = lightColorScheme(
    primary = Color(0xFF3525CD),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF4F46E5),
    onPrimaryContainer = Color(0xFFDAD7FF),
    secondary = Color(0xFF712AE2),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF8A4CFC),
    onSecondaryContainer = Color(0xFFFFFBFF),
    tertiary = Color(0xFF00505F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF006A7C),
    onTertiaryContainer = Color(0xFF93E8FF),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1A1A2A),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1A1A2A),
    surfaceVariant = Color(0xFFE3E0F7),
    onSurfaceVariant = Color(0xFF464555),
    surfaceContainer = Color(0xFFEFECFF),
    surfaceContainerLow = Color(0xFFF5F2FF),
    surfaceContainerHigh = Color(0xFFE8E6FC),
    surfaceContainerHighest = Color(0xFFE3E0F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFF777587),
    outlineVariant = Color(0xFFC7C4D8),
)

val StitchDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC3C0FF),
    onPrimary = Color(0xFF0F0069),
    primaryContainer = Color(0xFF4F46E5),
    onPrimaryContainer = Color(0xFFDAD7FF),
    secondary = Color(0xFFD2BBFF),
    onSecondary = Color(0xFF25005A),
    secondaryContainer = Color(0xFF712AE2),
    onSecondaryContainer = Color(0xFFFFFBFF),
    tertiary = Color(0xFF4CD7F6),
    onTertiary = Color(0xFF001F26),
    background = Color(0xFF141422),
    onBackground = Color(0xFFFCF8FF),
    surface = Color(0xFF1A1A2A),
    onSurface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFF333348),
    onSurfaceVariant = Color(0xFFC7C4D8),
    surfaceContainer = Color(0xFF252538),
    surfaceContainerLow = Color(0xFF1F1F30),
    surfaceContainerHigh = Color(0xFF2C2C40),
    surfaceContainerHighest = Color(0xFF333348),
    surfaceContainerLowest = Color(0xFF141422),
    outline = Color(0xFF8E8B9E),
    outlineVariant = Color(0xFF464555),
)

@Composable
fun EcoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var themeMode by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    var dynamicColors by remember { mutableStateOf(prefs.getBoolean("dynamic_colors", false)) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "theme" -> themeMode = sp.getString("theme", "system") ?: "system"
                "dynamic_colors" -> dynamicColors = sp.getBoolean("dynamic_colors", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val isDark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColors && isDark -> dynamicDarkColorScheme(context)
        dynamicColors && !isDark -> dynamicLightColorScheme(context)
        isDark -> StitchDarkColorScheme
        else -> StitchLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcoApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
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
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                when (currentRoute) {
                                    "history" -> "Histórico"
                                    "settings" -> "Definições"
                                    else -> "Início"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp
            ) {
                NavigationBarItem(
                    selected = currentRoute == "home",
                    onClick = {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_home)) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    )
                )
                NavigationBarItem(
                    selected = currentRoute == "history",
                    onClick = {
                        navController.navigate("history") {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_history)) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    )
                )
                NavigationBarItem(
                    selected = currentRoute == "settings",
                    onClick = {
                        navController.navigate("settings") {
                            popUpTo("home")
                            launchSingleTop = true
                        }
                    },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                    )
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { ReaderHome() }
            composable("history") { HistoryScreen(navController) }
            composable("settings") { SettingsScreen() }
        }
    }
}

@Composable
fun ReaderHome() {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op callback */ }

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
            // Status bar & Quick Voice Pill
            StitchStatusVoiceBar(snapshot = snapshot, enabled = editable)

            // Primary Input Card (Material 3 Elevated Container from Stitch)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header row: "Texto de Leitura" + Colar / Limpar buttons
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.EditNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                "Texto de Leitura",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // "Colar" pill button
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.clickable(enabled = editable) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                    if (clipText.isNotBlank()) input = TextFieldValue(clipText)
                                }
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Colar",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // Circular "Limpar" button
                            if (input.text.isNotBlank()) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable(enabled = editable) { input = TextFieldValue("") }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.DeleteSweep,
                                            contentDescription = "Limpar",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Text input container with soft surfaceContainerLow background
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(
                                    "Escreva ou cole o seu texto aqui...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            enabled = editable,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        )
                    }

                    // Footnote: stats counter & estimated duration
                    val words = if (input.text.isBlank()) 0 else input.text.split("\\s+".toRegex()).count { it.isNotBlank() }
                    val chars = input.text.length
                    val estSeconds = if (words > 0) (words / (2.5 * snapshot.speed)).toInt() else 0

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Text(
                                "$words palavras • $chars caracteres",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                if (estSeconds < 60) "~$estSeconds seg" else "~${estSeconds / 60}m ${estSeconds % 60}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Reprodutor de Voz Card (Bordered M3 Card from Stitch)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
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
                                Text(
                                    "EM LEITURA • PARÁGRAFO 1/1",
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
                            StitchWaveformPill(isPlaying = snapshot.status == ReadingStatus.PLAYING)

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Repeat,
                                        contentDescription = "Repetir",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Progress Bar / Scrubber & Time
                    val hasDuration = snapshot.durationMs > 0
                    val progress = if (hasDuration) (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f) else 0f

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Slider(
                            value = progress,
                            onValueChange = { /* scrubbing preview */ },
                            modifier = Modifier.fillMaxWidth().height(16.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )

                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    formatTime(snapshot.positionMs),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "/",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    formatTime(snapshot.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Text(
                                    "${String.format(Locale.US, "%.1f", snapshot.speed)}x Vel",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Transport Bar Container (Soft surfaceContainer pill containing all 5 transport controls)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
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
                                    .clickable { sendCommand(context, ReaderPlaybackService.ACTION_STOP) }
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
                                    .clickable { sendCommand(context, ReaderPlaybackService.ACTION_BACK) }
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
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable {
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
                                            if (input.text.isNotBlank()) {
                                                sendCommand(context, ReaderPlaybackService.ACTION_READ, input.text)
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
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            // 4. Forward 10s button (48dp circle)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clickable { sendCommand(context, ReaderPlaybackService.ACTION_FORWARD) }
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
                                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                        audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
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

            // Quick Tuning Card: Speed Pills & Voice Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Velocidade da Fala",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        ) {
                            Text(
                                "${String.format(Locale.US, "%.1f", snapshot.speed)}x ${if (snapshot.speed == 1.0f) "(Padrão)" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 5 speed pills in a row spanning full width
                    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        speeds.forEach { s ->
                            val isSelected = snapshot.speed == s
                            Surface(
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .clickable(enabled = editable) {
                                        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(speed = s)
                                        ReaderState.save(context)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${s}x",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Voice Synthetic Info Row
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "Voz Sintética",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape)
                            )
                            Text(
                                "Natural HD (Neural)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Helpful Context Hint Card from Stitch
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp).padding(top = 2.dp)
                    )
                    Text(
                        "Dica: Pode selecionar textos noutras aplicações e partilhar diretamente com o Echo Reading para leitura imediata.",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StitchStatusVoiceBar(snapshot: ReaderSnapshot, enabled: Boolean) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status Pill: pulsing green/secondary dot + "Motor TTS Ativo"
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape)
                )
                Text(
                    "Motor TTS Ativo",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Voice Pill from Stitch: graphic_eq + Voice Name • 1.0x + expand_more
        Box {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                shadowElevation = 1.dp,
                modifier = Modifier.clickable(enabled = enabled && downloading == null) { expanded = true }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    val shortName = OfflineVoice.option(snapshot.voiceId).label.split("·").lastOrNull()?.trim() ?: "Tugão"
                    Text(
                        "$shortName • ${String.format(Locale.US, "%.1f", snapshot.speed)}x",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                                coroutineScope.launch {
                                    try {
                                        OfflineVoice.install(context, voice) { copied, total ->
                                            if (total > 0) progress = copied.toFloat() / total
                                        }
                                        ReaderState.snapshot.value = ReaderState.snapshot.value.copy(voiceId = voice.id)
                                        ReaderState.save(context)
                                    } catch (_: Exception) {
                                    } finally {
                                        downloading = null
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun StitchWaveformPill(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "stitch_wave")
    val h1 by transition.animateFloat(6f, 18f, infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w1")
    val h2 by transition.animateFloat(10f, 24f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w2")
    val h3 by transition.animateFloat(5f, 16f, infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w3")
    val h4 by transition.animateFloat(8f, 22f, infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w4")
    val h5 by transition.animateFloat(6f, 18f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "w5")

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(Modifier.width(3.dp).height(if (isPlaying) h1.dp else 6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) h2.dp else 10.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) h3.dp else 5.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) h4.dp else 8.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)))
            Box(Modifier.width(3.dp).height(if (isPlaying) h5.dp else 6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
fun ReaderQuickPanel(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val snapshot by ReaderState.snapshot.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op callback */ }
    Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text(
                    text,
                    modifier = Modifier.padding(16.dp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            StitchStatusVoiceBar(snapshot = snapshot, enabled = snapshot.status == ReadingStatus.IDLE)
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                    val sameReading = snapshot.text == text &&
                        snapshot.status == ReadingStatus.IDLE && snapshot.positionMs > 0
                    if (sameReading) sendCommand(context, ReaderPlaybackService.ACTION_PLAY)
                    else sendCommand(context, ReaderPlaybackService.ACTION_READ, text)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_reading))
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.close_panel))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
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
