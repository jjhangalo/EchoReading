package com.echoreading.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echoreading.HistoryScreen
import com.echoreading.SettingsScreen
import com.echoreading.reader.ReaderState
import com.echoreading.speech.SpeechToTextState
import com.echoreading.ui.screen.ReaderHome
import com.echoreading.ui.screen.SpeechToTexScreen
import com.echoreading.ui.viewport.AppLandscapeViewPort
import com.echoreading.ui.viewport.AppPortraitViewPort

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EcoApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"
    // Coroutines started with LaunchedEffect(Unit) capture their first values.
    // Read the route from this state holder so a share received later is routed
    // using the screen that is actually visible.
    val latestRoute = rememberUpdatedState(currentRoute)

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            // Keep Home as the single root and preserve each tab's own state.
            popUpTo("home") { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        ReaderState.loadTextEvent.collect {
            if (latestRoute.value != "home") {
                navigateToTab("home")
            }
        }
    }

    LaunchedEffect(Unit) {
        SpeechToTextState.loadAudioEvent.collect {
            if (latestRoute.value != "transcribe") {
                navigateToTab("transcribe")
            }
        }
    }

    LaunchedEffect(Unit) {
        SpeechToTextState.pendingAudioUri.collect { uri ->
            if (uri != null && latestRoute.value != "transcribe") {
                navigateToTab("transcribe")
            }
        }
    }

    val navContent = @Composable { padding: PaddingValues ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { ReaderHome() }
            composable("transcribe") { SpeechToTexScreen() }
            composable("history") { HistoryScreen() }
            composable("settings") { SettingsScreen() }
        }
    }

    if (isLandscape) {
        AppLandscapeViewPort(navContent, currentRoute) { route ->
            navigateToTab(route)
        }
    } else {
        AppPortraitViewPort(navContent, currentRoute) { route ->
            navigateToTab(route)
        }
    }
}
