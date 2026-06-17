package dev.jaredhq.dashboardandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.jaredhq.dashboardandroid.ui.AppViewModelFactory
import dev.jaredhq.dashboardandroid.ui.capture.CaptureScreen
import dev.jaredhq.dashboardandroid.ui.capture.CaptureViewModel
import dev.jaredhq.dashboardandroid.ui.settings.SettingsScreen
import dev.jaredhq.dashboardandroid.ui.settings.SettingsViewModel
import dev.jaredhq.dashboardandroid.ui.theme.DashboardTheme
import dev.jaredhq.dashboardandroid.ui.today.TodayScreen
import dev.jaredhq.dashboardandroid.ui.today.TodayViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DashboardTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Filled.Today),
    Capture("capture", "Capture", Icons.Filled.Add),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val factory = AppViewModelFactory()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val current = backStack?.destination
                Tab.entries.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Today.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.Today.route) {
                val vm: TodayViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                TodayScreen(
                    state = state,
                    onRefresh = vm::refresh,
                    onToggleHabit = vm::toggleHabit,
                    onStartFocus = vm::startFocus,
                )
            }
            composable(Tab.Capture.route) {
                val vm: CaptureViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                CaptureScreen(
                    state = state,
                    onInputChange = vm::onInputChange,
                    onToggleAssistant = vm::setUseAssistant,
                    onSend = vm::send,
                )
            }
            composable(Tab.Settings.route) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onBaseUrlChange = vm::onBaseUrlChange,
                    onTokenChange = vm::onTokenChange,
                    onSave = vm::save,
                    onClearToken = vm::clearToken,
                    onTest = vm::testConnection,
                )
            }
        }
    }
}
