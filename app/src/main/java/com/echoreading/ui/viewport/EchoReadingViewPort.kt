package com.echoreading.ui.viewport

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.echoreading.R
import com.echoreading.ui.component.EchoReadingBottomAppBar
import com.echoreading.ui.component.EchoReadingTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLandscapeViewPort(
    navContent: @Composable (PaddingValues) -> Unit,
    currentRoute: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        NavigationRail(
            containerColor = colorScheme.surface,
            modifier = Modifier.fillMaxHeight()
        ) {
            Spacer(Modifier.height(8.dp))
            NavigationRailItem(
                selected = currentRoute == "home",
                onClick = { onNavigate("home") },
                icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                label = { Text(stringResource(R.string.tab_home)) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                )
            )
            NavigationRailItem(
                selected = currentRoute == "transcribe",
                onClick = { onNavigate("transcribe") },
                icon = { Icon(Icons.Default.Mic, null) },
                label = { Text(stringResource(R.string.tab_transcribe)) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                )
            )
            NavigationRailItem(
                selected = currentRoute == "history",
                onClick = { onNavigate("history") },
                icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                label = { Text(stringResource(R.string.tab_history)) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                )
            )
            NavigationRailItem(
                selected = currentRoute == "settings",
                onClick = { onNavigate("settings") },
                icon = { Icon(Icons.Default.Settings, null) },
                label = { Text(stringResource(R.string.tab_settings)) },
                colors = NavigationRailItemDefaults.colors(
                    indicatorColor = colorScheme.primaryContainer.copy(alpha = 0.25f),
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                )
            )
        }
        Scaffold(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            topBar = { EchoReadingTopAppBar(currentRoute) },
        ) { padding ->
            navContent(padding)
        }
    }
}

@Composable
fun AppPortraitViewPort(
    navContent: @Composable (PaddingValues) -> Unit,
    currentRoute: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = { EchoReadingTopAppBar(currentRoute) },
        bottomBar = {
            EchoReadingBottomAppBar(currentRoute, onNavigate = onNavigate)
        },
    ) { padding ->
        navContent(padding)
    }
}
