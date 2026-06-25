package dev.jaredhq.dashboardandroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.ui.capture.CaptureViewModel
import dev.jaredhq.dashboardandroid.ui.settings.SettingsViewModel
import dev.jaredhq.dashboardandroid.ui.today.TodayViewModel
import dev.jaredhq.dashboardandroid.ui.watch.WatchHealthViewModel

/**
 * One factory for the app's handful of ViewModels, pulling dependencies from the
 * [ServiceLocator]. Avoids per-screen boilerplate while staying explicit.
 */
class AppViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(TodayViewModel::class.java) ->
            TodayViewModel(ServiceLocator.repository) as T
        modelClass.isAssignableFrom(CaptureViewModel::class.java) ->
            CaptureViewModel(ServiceLocator.repository) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(ServiceLocator.settings, ServiceLocator.repository) as T
        modelClass.isAssignableFrom(WatchHealthViewModel::class.java) ->
            WatchHealthViewModel(
                engine = ServiceLocator.watchEngine,
                deviceId = ServiceLocator.watchDeviceId,
                registerUiListener = { ServiceLocator.watchUiListener = it },
            ) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
