package dev.jaredhq.dashboardandroid.watch.engine

/**
 * Durable "metric confidence" layer (Phase 2).
 *
 * Each watch metric is labelled with the *highest* evidence level proven for it, on the ladder the
 * project tracks. The lower two rungs are device-independent facts about the SDK / our pipeline; the
 * upper rungs require the connected watch to actually advertise and deliver the metric:
 *
 *  1. [MetricConfidence.SDK_MODEL_ONLY]        – an SDK type + mapper exists in this app.
 *  2. [MetricConfidence.FUNCTION_TABLE_SUPPORTED] – the connected watch's function table advertises it.
 *  3. [MetricConfidence.EMITTED_ON_REAL_SYNC]  – a real Active 4 Pro sync delivered ≥1 record.
 *  4. [MetricConfidence.UPLOADED_TO_DASHBOARD] – …and the app maps + uploads it to the dashboard.
 *  5. [MetricConfidence.SHOWN_IN_UI]           – …and the Watch screen renders it.
 *
 * This file is pure Kotlin (no `com.ido.*` / `com.veryfit.*`): the runtime evidence — function-table
 * support and per-metric emitted counts — is resolved by the caller ([IdoSdkWatchEngine] reads the
 * `SupportFunctionInfo` flag; [WatchSyncDiagnostics] supplies the emitted count) and passed in, so
 * the ladder logic stays testable off-device.
 */
enum class MetricConfidence {
    SDK_MODEL_ONLY,
    FUNCTION_TABLE_SUPPORTED,
    EMITTED_ON_REAL_SYNC,
    UPLOADED_TO_DASHBOARD,
    SHOWN_IN_UI,
}

/**
 * Static support facts for one watch metric.
 *
 * @property label human-readable name for the matrix/log.
 * @property diagnosticsKey the [WatchSyncDiagnostics] key this metric is tallied under, so an
 *   emitted count can be looked up from a sync snapshot.
 * @property functionTableField the `SupportFunctionInfo` boolean field name the watch uses to
 *   advertise this metric, or null when the SDK exposes no clean capability flag for it.
 * @property uploaded whether the app maps this metric into the dashboard upload batch.
 * @property shownInUi whether the Watch screen renders a count/row for it.
 * @property notes short caveat (e.g. "categorical, not a 0–100 score"), or null.
 */
enum class WatchMetric(
    val label: String,
    val diagnosticsKey: String,
    val functionTableField: String?,
    val uploaded: Boolean,
    val shownInUi: Boolean,
    val notes: String? = null,
) {
    // ── Mapped metrics (domain model + upload + UI all wired) ──────────────────────────
    ACTIVITY_DAY(
        "Activity day", WatchSyncDiagnostics.ACTIVITY_DAY_V3,
        // main8 = V3 daily-activity sync; main9 (ex_table_main9_v3_sports) is the per-exercise gate
        // used by WORKOUT. The old "ex_main4_v3_activity_data" reads false on the A4P even though the
        // daily rollup emits — confirmed against the captured function-table dump (2026-06-27).
        "ex_table_main8_v3_sync_activity", uploaded = true, shownInUi = true,
        notes = "V3 daily rollup (HealthSportV3); this V3 watch never fires the v2 path.",
    ),
    WORKOUT(
        "Workout / sport session", WatchSyncDiagnostics.WORKOUT_V3,
        "ex_table_main9_v3_sports", uploaded = true, shownInUi = true,
        notes = "Per-exercise V3 session (HealthActivityV3).",
    ),
    HEART_RATE_DAY(
        "Heart-rate day", WatchSyncDiagnostics.HEART_RATE_DAY_V2,
        "heartRate", uploaded = true, shownInUi = true,
        notes = "v2 HealthHeartRate summary; a V3-only watch may not emit it.",
    ),
    SLEEP(
        "Sleep session", WatchSyncDiagnostics.SLEEP_V3,
        "V3_support_scientific_sleep", uploaded = true, shownInUi = true,
    ),
    SPO2(
        "SpO₂", WatchSyncDiagnostics.SPO2,
        "ex_main3_v3_spo2_data", uploaded = true, shownInUi = true,
    ),
    HRV(
        "HRV", WatchSyncDiagnostics.HRV,
        "V3_support_hrv", uploaded = true, shownInUi = true,
    ),
    RESPIRATORY(
        "Respiratory rate", WatchSyncDiagnostics.RESPIRATORY,
        null, uploaded = true, shownInUi = true,
        notes = "No dedicated SupportFunctionInfo flag; emission confirmed by sync only.",
    ),
    TEMPERATURE(
        "Skin/body temperature", WatchSyncDiagnostics.TEMPERATURE,
        "V3_health_sync_temperature", uploaded = true, shownInUi = true,
    ),
    BODY_ENERGY(
        "Body energy", WatchSyncDiagnostics.BODY_ENERGY,
        "v3_body_power", uploaded = true, shownInUi = true,
    ),
    BLOOD_PRESSURE(
        "Blood pressure", WatchSyncDiagnostics.BLOOD_PRESSURE_V3,
        "BloodPressure", uploaded = true, shownInUi = true,
    ),
    STRESS(
        "Stress", WatchSyncDiagnostics.STRESS,
        "ex_main3_v3_pressure", uploaded = true, shownInUi = true,
    ),

    // ── Delivered by the SDK but currently dropped (no domain model / column / clean mapping) ──
    GPS(
        "GPS track", WatchSyncDiagnostics.GPS_V3,
        "ex_gps", uploaded = false, shownInUi = false,
        notes = "Dropped: no dashboard schema for tracks yet (out of Phase-2 scope).",
    ),
    SWIMMING(
        "Swimming", WatchSyncDiagnostics.SWIMMING,
        "pool_swim", uploaded = false, shownInUi = false,
        notes = "Dropped: no domain model yet.",
    ),
    NOISE(
        "Ambient noise", WatchSyncDiagnostics.NOISE,
        "V3_health_sync_noise", uploaded = false, shownInUi = false,
        notes = "Dropped: no domain model yet.",
    ),
    HEART_RATE_SECOND(
        "Intraday HR series", WatchSyncDiagnostics.HEART_RATE_SECOND,
        null, uploaded = false, shownInUi = false,
        notes = "Emitted (~282 bare HR values/day in items[]; silentHR reads 0 on the A4P). Sample " +
            "interval being confirmed before mapping — see watch-metric-support-matrix.md.",
    ),
    BODY_COMPOSITION(
        "Body composition", WatchSyncDiagnostics.BODY_COMPOSITION,
        null, uploaded = false, shownInUi = false,
        notes = "Dropped: needs a bio-impedance scale; won't fire on a wrist watch.",
    ),
    ECG(
        "ECG", WatchSyncDiagnostics.ECG,
        null, uploaded = false, shownInUi = false,
        notes = "Dropped: no clean mapping; unknown if the Active 4 Pro records ECG.",
    ),
    EMOTION(
        "Emotion / mood", WatchSyncDiagnostics.EMOTION,
        "support_emotion_health", uploaded = false, shownInUi = false,
        notes = "Dropped: categorical mood code, not a 0–100 score.",
    );

    /**
     * Highest confidence proven for this metric, given runtime evidence:
     *
     * @param functionTableSupported the watch's function-table value for [functionTableField]
     *   (null = unknown / no table yet / no flag for this metric).
     * @param emittedOnRealSync true once a real sync delivered ≥1 record of this metric.
     *
     * The pipeline rungs (uploaded / shown-in-UI) only count once the metric was actually emitted,
     * so a metric that is upload/UI-capable but the watch never sent stays at the device rung it has
     * genuinely reached — never overstated as "shown in UI".
     */
    fun confidence(functionTableSupported: Boolean?, emittedOnRealSync: Boolean): MetricConfidence =
        when {
            emittedOnRealSync && uploaded && shownInUi -> MetricConfidence.SHOWN_IN_UI
            emittedOnRealSync && uploaded -> MetricConfidence.UPLOADED_TO_DASHBOARD
            emittedOnRealSync -> MetricConfidence.EMITTED_ON_REAL_SYNC
            functionTableSupported == true -> MetricConfidence.FUNCTION_TABLE_SUPPORTED
            else -> MetricConfidence.SDK_MODEL_ONLY
        }
}
