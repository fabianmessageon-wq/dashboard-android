package dev.jaredhq.dashboardandroid

import android.app.Application
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.work.RefreshScheduler

/**
 * App entry point: initializes the service locator and schedules the periodic
 * background work — the Today cache refresh (keeps the widget warm) and the
 * notifications bridge (surfaces dashboard reminders + the daily quote) — so the
 * companion stays useful without the user opening the app.
 */
class DashboardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        RefreshScheduler.ensureScheduled(this)
        RefreshScheduler.ensureNotificationsScheduled(this)
    }
}
