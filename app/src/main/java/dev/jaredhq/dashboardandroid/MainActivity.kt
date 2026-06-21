package dev.jaredhq.dashboardandroid

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import dev.jaredhq.dashboardandroid.ui.watch.WatchScreen
import dev.jaredhq.dashboardandroid.ui.watch.WatchViewModel
import dev.jaredhq.dashboardandroid.work.WatchSyncScheduler

class MainActivity : ComponentActivity() {

    // The route a launcher/widget/notification asked us to open on. Held as a
    // Compose state so [AppRoot] reacts to a fresh deep link delivered via
    // onNewIntent (widget tap while the app is already running), not just cold
    // start. Consumed (reset to null) once navigation happens.
    private val startRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        startRoute.value = intent?.getStringExtra(EXTRA_START_ROUTE)
        setContent {
            DashboardTheme {
                AppRoot(startRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Only overwrite the pending route when this intent actually carries one.
        // An unrelated intent (no EXTRA_START_ROUTE) must not null out a route
        // that AppRoot hasn't consumed yet.
        intent.getStringExtra(EXTRA_START_ROUTE)?.let { startRoute.value = it }
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Best-effort, fire-and-forget: the result doesn't gate any UI. The
        // notification worker simply no-ops if the user declines.
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS)
    }

    companion object {
        /** Intent extra carrying a tab route ("today" | "capture" | "watch" | "settings"). */
        const val EXTRA_START_ROUTE = "start_route"
        private const val RC_NOTIFICATIONS = 4011
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Filled.Today),
    Capture("capture", "Capture", Icons.Filled.Add),
    Watch("watch", "Watch", Icons.Filled.Bluetooth),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
private fun AppRoot(startRoute: MutableState<String?>) {
    val navController = rememberNavController()
    val factory = AppViewModelFactory()

    // React to a deep link (widget/notification) by switching tabs, then consume
    // it so returning to the app later doesn't re-trigger the jump. Unknown
    // routes are ignored defensively.
    val requested = startRoute.value
    LaunchedEffect(requested) {
        if (requested != null) {
            if (Tab.entries.any { it.route == requested }) {
                navController.navigate(requested) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            startRoute.value = null
        }
    }

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
                // Refresh whenever this destination RESUMES: first show, return to
                // the tab (the NavBackStackEntry lifecycle is the LocalLifecycleOwner
                // here), AND the app returning to the foreground. So a capture/chat
                // done elsewhere — or a change made on another device — is reflected.
                LifecycleResumeEffect(Unit) {
                    vm.refresh()
                    onPauseOrDispose { }
                }
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

                // On-device speech-to-text via the platform recognizer activity.
                // The recognizer app owns the microphone (so this app needs no
                // RECORD_AUDIO) and shows its own listening UI; we only receive
                // the final transcript and drop it into the input for editing.
                val context = LocalContext.current
                val speechAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }
                val speechLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    // Backing out of the recognizer (RESULT_CANCELED) is not an
                    // error — say nothing. Only a successful recognition that
                    // produced no usable text warrants the "Didn't catch that" notice.
                    if (result.resultCode != android.app.Activity.RESULT_OK) {
                        return@rememberLauncherForActivityResult
                    }
                    val transcript = result.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (transcript.isNotEmpty()) vm.applyTranscript(transcript) else vm.onSpeechNoResult()
                }

                CaptureScreen(
                    state = state,
                    onInputChange = vm::onInputChange,
                    onToggleAssistant = vm::setUseAssistant,
                    onSend = vm::send,
                    onDismissSpeechNotice = vm::dismissSpeechNotice,
                    speechAvailable = speechAvailable,
                    onStartSpeech = {
                        if (!speechAvailable) {
                            vm.onSpeechUnavailable()
                        } else {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your capture")
                            }
                            // A device can report a recognizer yet still fail to launch
                            // the intent — fall back to the same friendly notice.
                            runCatching { speechLauncher.launch(intent) }
                                .onFailure { vm.onSpeechUnavailable() }
                        }
                    },
                )
            }
            composable(Tab.Watch.route) {
                val vm: WatchViewModel = viewModel(factory = factory)
                val state by vm.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                WatchScreen(
                    state = state,
                    onScanClick = vm::startScan,
                    onDisconnectClick = vm::disconnect,
                    onMacRequest = vm::requestMacAddress,
                    onDeviceInfoRequest = vm::requestDeviceInfo,
                    onStatusRequest = vm::requestStatus,
                    onSyncClick = {
                        WatchSyncScheduler.syncNow(context)
                        vm.onSyncRequested()
                    },
                    onClearLog = vm::clearLog,
                    onPermissionsGranted = vm::onPermissionsGranted,
                    onPermissionsDenied = vm::onPermissionsDenied,
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
