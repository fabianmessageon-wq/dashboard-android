package dev.jaredhq.dashboardandroid.watch.engine

/**
 * A [WatchHealthListener] that fans every callback out to a set of delegates.
 *
 * [WatchEngine] exposes a single `listener` slot, but two consumers want the decoded records: the
 * upload path ([UploadingWatchHealthListener]) and the product Watch screen (for live sync feedback).
 * The delegate set is resolved per-callback via [delegates] so a screen can register/unregister its
 * listener over the engine's lifetime without re-wiring the engine.
 *
 * Each callback fires on the SDK's callback thread; delegates are invoked in order, and one
 * throwing does not stop the rest (so a UI listener bug can't break the uploader).
 */
class CompositeWatchHealthListener(
    private val delegates: () -> List<WatchHealthListener>,
) : WatchHealthListener {

    private inline fun each(crossinline action: (WatchHealthListener) -> Unit) {
        delegates().forEach { runCatching { action(it) } }
    }

    override fun onActivityDay(day: WatchActivityDay) = each { it.onActivityDay(day) }
    override fun onHeartRateDay(day: WatchHeartRateDay) = each { it.onHeartRateDay(day) }
    override fun onSleepSession(session: WatchSleepSession) = each { it.onSleepSession(session) }
    override fun onWorkout(workout: WatchWorkout) = each { it.onWorkout(workout) }
    override fun onSpo2Reading(reading: WatchSpo2Reading) = each { it.onSpo2Reading(reading) }
    override fun onHrvReading(reading: WatchHrvReading) = each { it.onHrvReading(reading) }
    override fun onRespiratoryReading(reading: WatchRespiratoryReading) = each { it.onRespiratoryReading(reading) }
    override fun onTemperatureReading(reading: WatchTemperatureReading) = each { it.onTemperatureReading(reading) }
    override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) = each { it.onBodyEnergyReading(reading) }
    override fun onBloodPressureReading(reading: WatchBloodPressureReading) = each { it.onBloodPressureReading(reading) }
    override fun onStressReading(reading: WatchStressReading) = each { it.onStressReading(reading) }
    override fun onHeartRateReading(reading: WatchHeartRateReading) = each { it.onHeartRateReading(reading) }
    override fun onSyncProgress(percent: Int) = each { it.onSyncProgress(percent) }
    override fun onSyncComplete() = each { it.onSyncComplete() }
    override fun onSyncFailed() = each { it.onSyncFailed() }
}
