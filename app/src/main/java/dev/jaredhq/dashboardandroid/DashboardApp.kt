package dev.jaredhq.dashboardandroid

import android.app.Application
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.work.RefreshScheduler

/**
 * App entry point: initializes the service locator and schedules the periodic
 * background refresh so the widget/app cache stays warm without the user opening
 * the app.
 */
class DashboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        RefreshScheduler.ensureScheduled(this)
    }
}
