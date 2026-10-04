package com.echoreading.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.Log
import com.echoreading.R
import com.echoreading.reader.ReaderSnapshot
import com.echoreading.reader.ReaderState
import com.echoreading.voice.OfflineVoice
import kotlinx.coroutines.launch
import java.util.Locale

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
        )
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
            icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
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
fun StitchStatusVoiceBar(
    snapshot: ReaderSnapshot,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = colorScheme.surfaceContainerLow,
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
                        .background(colorScheme.secondary, CircleShape)
                )
                Text(
                    "Motor TTS Ativo",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        Box {
            Surface(
                shape = CircleShape,
                color = colorScheme.secondaryContainer.copy(alpha = 0.35f),
                shadowElevation = 1.dp,
                modifier = Modifier.clickable(enabled = enabled && downloading == null) {
                    expanded = true
                }
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
                        tint = colorScheme.secondary
                    )
                    val shortName =
                        OfflineVoice.option(ctx, snapshot.voiceId).label.split("·").lastOrNull()
                            ?.trim() ?: "Tugão"
                    Text(
                        "$shortName • ${String.format(Locale.US, "%.1f", snapshot.speed)}x",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colorScheme.onSurface
                    )
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurfaceVariant
                    )
                }
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                OfflineVoice.allVoices(ctx).forEach { voice ->
                    val installed = OfflineVoice.isInstalled(ctx, voice)
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (installed) voice.label else "${voice.label} · ${
                                    stringResource(
                                        R.string.download_voice
                                    )
                                }"
                            )
                        },
                        onClick = {
                            expanded = false
                            if (installed) {
                                ReaderState.snapshot.value =
                                    ReaderState.snapshot.value.copy(voiceId = voice.id)
                                ReaderState.save(ctx)
                            } else {
                                downloading = voice.id
                                coroutineScope.launch {
                                    try {
                                        OfflineVoice.install(ctx, voice) { copied, total ->
                                            if (total > 0) progress = copied.toFloat() / total
                                        }
                                        ReaderState.snapshot.value =
                                            ReaderState.snapshot.value.copy(voiceId = voice.id)
                                        ReaderState.save(ctx)
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
