package dev.jaredhq.dashboardandroid.watch.engine

/**
 * Per-sync tally of **every** record the IDO SDK delivered during one sync — including the
 * callbacks [IdoSdkWatchEngine] currently drops (GPS, body composition, second-by-second HR,
 * noise, swimming, ECG, emotion) and the two it only maps partially. The mapped-metric counts the
 * UI already shows come from the upload/ViewModel path; this captures the *raw* delivery so a real
 * sync can answer "what did the Active 4 Pro actually emit, and what did we ignore?" — the evidence
 * the metric support matrix (Phase 2) needs.
 *
 * Counts only (no decoded values), so [summary] is privacy-safe to log even in a release build.
 *
 * **Threading:** single-threaded by construction. The IDO SDK marshals all `ISyncDataListener`
 * callbacks to the main thread, and [reset]/[summary]/[snapshot] run on that same sync lifecycle.
 * Deliberately not synchronized; do not call from another thread.
 */
class WatchSyncDiagnostics {

    /**
     * Raw delivery counts for one metric callback within a sync.
     *
     * @property parentRecords day/session records delivered (the callback fired this many times
     *   with a non-null payload).
     * @property itemSamples within-day item samples carried by those records (0 for callbacks that
     *   have no item list).
     * @property mappedReadings domain readings the engine actually emitted upward (≤ itemSamples or
     *   ≤ parentRecords; 0 for the no-op/deferred sinks that drop everything).
     */
    data class Tally(
        var parentRecords: Int = 0,
        var itemSamples: Int = 0,
        var mappedReadings: Int = 0,
    )

    // Insertion-ordered so the summary lists metrics in a stable, code-defined order.
    private val tallies = LinkedHashMap<String, Tally>()

    /** Drop all counts — call at the start of each sync run. */
    fun reset() {
        tallies.clear()
    }

    /**
     * Add to [metric]'s tally. Safe to call many times per metric (once per callback); the counts
     * accumulate. A metric is only listed in [summary]/[snapshot] once it has been recorded at
     * least once, so a metric the watch never delivered stays absent (distinct from delivered-but-
     * empty, which records `parentRecords` with zero items).
     */
    fun record(
        metric: String,
        parentRecords: Int = 0,
        itemSamples: Int = 0,
        mappedReadings: Int = 0,
    ) {
        val t = tallies.getOrPut(metric) { Tally() }
        t.parentRecords += parentRecords
        t.itemSamples += itemSamples
        t.mappedReadings += mappedReadings
    }

    /** True once any record at all was delivered this sync. */
    fun isEmpty(): Boolean = tallies.isEmpty()

    /** Independent copy of the current tallies (mutating the result can't affect this instance). */
    fun snapshot(): Map<String, Tally> =
        tallies.mapValuesTo(LinkedHashMap()) { (_, t) -> t.copy() }

    /** Metrics that delivered at least one parent record but produced no mapped reading — i.e. data
     *  the watch sent that the engine currently drops. The Phase-2 "what did we ignore" answer. */
    fun droppedMetrics(): List<String> =
        tallies.filter { (_, t) -> t.parentRecords > 0 && t.mappedReadings == 0 }.keys.toList()

    /**
     * Compact, counts-only one-liner for logcat, e.g.
     * `body_energy(p=2,i=251,m=251); stress(p=2,i=24,m=24); gps_v3(p=1,i=0,m=0)`.
     * Privacy-safe (no decoded values) — fine to log in release.
     */
    fun summary(): String =
        if (tallies.isEmpty()) {
            "no SDK records delivered"
        } else {
            tallies.entries.joinToString("; ") { (metric, t) ->
                "$metric(p=${t.parentRecords},i=${t.itemSamples},m=${t.mappedReadings})"
            }
        }

    /** Stable metric keys, shared by the engine (producer) and any consumer/test. */
    companion object Keys {
        // Mapped metrics (also surfaced as domain readings + UI counts).
        const val ACTIVITY_DAY_V2 = "activity_day_v2"
        const val ACTIVITY_DAY_V3 = "activity_day_v3"
        const val HEART_RATE_DAY_V2 = "heart_rate_day_v2"
        const val SLEEP_V2 = "sleep_v2"
        const val SLEEP_V3 = "sleep_v3"
        const val WORKOUT_V3 = "workout_v3"
        const val SPO2 = "spo2"
        const val HRV = "hrv"
        const val RESPIRATORY = "respiratory"
        const val TEMPERATURE = "temperature"
        const val BODY_ENERGY = "body_energy"
        const val BLOOD_PRESSURE_V3 = "blood_pressure_v3"
        const val STRESS = "stress"

        // Currently dropped — no domain model / dashboard column / clean mapping yet.
        const val SPORT_V2 = "sport_v2"
        const val BLOOD_PRESSURE_V2 = "blood_pressure_v2"
        const val GPS_V2 = "gps_v2"
        const val GPS_V3 = "gps_v3"
        const val BODY_COMPOSITION = "body_composition"
        const val HEART_RATE_SECOND = "heart_rate_second"
        const val NOISE = "noise"
        const val SWIMMING = "swimming"
        const val ECG = "ecg"
        const val EMOTION = "emotion"
        const val DRINK_PLAN = "drink_plan"
    }
}
