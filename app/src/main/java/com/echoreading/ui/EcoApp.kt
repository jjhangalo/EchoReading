package com.echoreading.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.echoreading.HistoryScreen
import com.echoreading.ui.screen.ReaderHome
import com.echoreading.SettingsScreen
import com.echoreading.TranscriberScreen
import com.echoreading.reader.ReaderState
import com.echoreading.speech.TranscriberState
import com.echoreading.ui.viewport.AppLandscapeViewPort
import com.echoreading.ui.viewport.AppPortraitViewPort

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EcoApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        ReaderState.loadTextEvent.collect {
            if (currentRoute != "home") {
                navController.navigate("home") {
                    popUpTo("home") { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        TranscriberState.loadAudioEvent.collect {
            if (currentRoute != "transcribe") {
                navController.navigate("transcribe") {
                    popUpTo("home")
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        TranscriberState.pendingAudioUri.collect { uri ->
            if (uri != null && currentRoute != "transcribe") {
                navController.navigate("transcribe") {
                    popUpTo("home")
                    launchSingleTop = true
                }
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
            composable("transcribe") { TranscriberScreen() }
            composable("history") { HistoryScreen(navController) }
            composable("settings") { SettingsScreen() }
        }
    }

    if (isLandscape) {
        AppLandscapeViewPort(navContent, currentRoute) { route ->
            navController.navigate(route) {
                popUpTo("home") { inclusive = true }
                launchSingleTop = true
            }
        }
    } else {
        AppPortraitViewPort(navContent, currentRoute) { route ->
            navController.navigate(route) {
                popUpTo("home") { inclusive = true }
                launchSingleTop = true
            }
        }
    }
}
