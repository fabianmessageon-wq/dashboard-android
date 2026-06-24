package dev.jaredhq.dashboardandroid.watch.engine

import android.app.Application
import android.util.Log
import com.ido.ble.BLEManager
import com.ido.ble.InitParam
import com.ido.ble.callback.CallBackManager
import com.ido.ble.callback.SyncCallBack
import com.ido.ble.data.manage.database.HealthActivity
import com.ido.ble.data.manage.database.HealthBloodPressed
import com.ido.ble.data.manage.database.HealthBloodPressedItem
import com.ido.ble.data.manage.database.HealthHeartRate
import com.ido.ble.data.manage.database.HealthHeartRateItem
import com.ido.ble.data.manage.database.HealthSleep
import com.ido.ble.data.manage.database.HealthSleepItem
import com.ido.ble.data.manage.database.HealthSport
import com.ido.ble.data.manage.database.HealthSportItem

/**
 * [WatchEngine] backed by the vendored IDO/VeryFit SDK (ADR 0001).
 *
 * **This is the only file in the app permitted to import `com.ido.*` / `com.veryfit.*`.**
 * Everything it exposes upward is the app's own domain types — it registers the SDK's
 * callbacks, triggers a sync via [BLEManager], and maps the SDK's typed `HealthXxx`
 * objects into [WatchActivityDay] / [WatchHeartRateDay] / [WatchSleepSession].
 *
 * The native lib does all BLE framing/parsing; we consume already-decoded objects, so the
 * mappers here are pure field copies — no protocol logic.
 *
 * Scope of this first cut (health-first): the v2 sync callbacks (daily activity, heart-rate
 * summary, sleep). The richer V3 metrics (SpO2, body composition, stress/HRV, temperature,
 * respiratory rate, blood-pressure V3) arrive through `SyncV3CallBack.ICallBack` and are the
 * documented next step — register it the same way once the v2 path is verified on hardware.
 */
class IdoSdkWatchEngine(private val app: Application) : WatchEngine {

    override var listener: WatchHealthListener? = null

    @Volatile
    private var initialized = false

    override fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // SDK lifecycle hooks. onApplicationCreate must run before init().
            BLEManager.onApplicationCreate(app)
            BLEManager.init(buildInitParam())
            registerCallbacks()
            initialized = true
            Log.i(TAG, "IDO SDK initialised")
        }
    }

    private fun buildInitParam(): InitParam = InitParam().apply {
        databaseName = "ido-watch.db"
        isSaveDeviceDataToDB = true
        // No SQLCipher native lib is vendored — keep the SDK DB/SP unencrypted (ADR 0001).
        isEncryptedDBData = false
        isEncryptedSPData = false
        isEnableLog = true
        log_save_path = app.filesDir.absolutePath + "/ido-logs"
        log_save_days = 3
    }

    override fun connect(macAddress: String) {
        Log.i(TAG, "connect($macAddress)")
        BLEManager.autoConnect(macAddress)
    }

    override fun disconnect() {
        BLEManager.disConnect()
    }

    override fun isConnected(): Boolean = BLEManager.isConnected()

    override fun syncHealth() {
        if (!isConnected()) {
            Log.w(TAG, "syncHealth() ignored — not connected")
            listener?.onSyncFailed()
            return
        }
        Log.i(TAG, "starting health + activity sync")
        BLEManager.startSyncActivityData()
        BLEManager.startSyncHealthData()
    }

    private fun registerCallbacks() {
        val cm = CallBackManager.getManager()
        cm.registerSyncActivityCallBack(activityCallBack)
        cm.registerSyncHealthCallBack(healthCallBack)
        // NEXT: cm.registerSyncV3CallBack(v3CallBack) for SpO2/body-composition/stress/etc.
    }

    // ── SDK callbacks (v2) ────────────────────────────────────────────────────────

    private val activityCallBack = object : SyncCallBack.IActivityCallBack {
        override fun onStart() {}
        override fun onStop() {}
        override fun onSuccess() {}
        override fun onFailed() { listener?.onSyncFailed() }
        override fun onGetActivityData(data: HealthActivity?) {
            data?.let { listener?.onActivityDay(it.toDomain()) }
        }
    }

    private val healthCallBack = object : SyncCallBack.IHealthCallBack {
        override fun onStart() {}
        override fun onStop() {}
        override fun onSuccess() { listener?.onSyncComplete() }
        override fun onFailed() { listener?.onSyncFailed() }
        override fun onProgress(percent: Int) { listener?.onSyncProgress(percent) }

        override fun onGetHeartRateData(
            data: HealthHeartRate?,
            items: MutableList<HealthHeartRateItem>?,
            isLast: Boolean,
        ) {
            data?.let { listener?.onHeartRateDay(it.toDomain()) }
        }

        override fun onGetSleepData(
            data: HealthSleep?,
            items: MutableList<HealthSleepItem>?,
        ) {
            data?.let { listener?.onSleepSession(it.toDomain()) }
        }

        override fun onGetSportData(
            data: HealthSport?,
            items: MutableList<HealthSportItem>?,
            isLast: Boolean,
        ) {
            // Workout sessions — mapping deferred with the V3 metrics (needs the watch_workout
            // schema + confirmed HealthSport field set). Logged for now so we can see them land.
            if (data != null) Log.d(TAG, "onGetSportData (workout) received — mapping deferred")
        }

        override fun onGetBloodPressureData(
            data: HealthBloodPressed?,
            items: MutableList<HealthBloodPressedItem>?,
            isLast: Boolean,
        ) {
            if (data != null) Log.d(TAG, "onGetBloodPressureData received — mapping deferred")
        }
    }

    // ── Mappers (SDK type → domain) ─────────────────────────────────────────────────

    private fun HealthActivity.toDomain() = WatchActivityDay(
        date = ymd(year, month, day),
        steps = step,
        distanceMeters = distance,
        calories = calories,
        durationSeconds = durations,
        avgHeartRate = avg_hr_value.nonZero(),
        maxHeartRate = max_hr_value.nonZero(),
        minHeartRate = min_hr_value.nonZero(),
        // The getters apply the SDK's range1..5 fallback (the raw fields can be 0 while the
        // value lives in rangeN), so call them rather than reading the fields directly.
        warmUpMins = getWarmUpMins(),
        burnFatMins = getBurn_fat_mins(),
        aerobicMins = getAerobic_mins(),
        anaerobicMins = getAnaerobicMins(),
        limitMins = getLimit_mins(),
    )

    private fun HealthHeartRate.toDomain() = WatchHeartRateDay(
        date = ymd(year, month, day),
        restingBpm = silentHeart.nonZero(),
        userMaxHr = UserMaxHr.nonZero(),
        warmUpThreshold = warmUpThreshold.nonZero(),
        burnFatThreshold = burn_fat_threshold.nonZero(),
        aerobicThreshold = aerobic_threshold.nonZero(),
        anaerobicThreshold = anaerobicThreshold.nonZero(),
        limitThreshold = limit_threshold.nonZero(),
        warmUpMins = warmUpMins,
        burnFatMins = burn_fat_mins,
        aerobicMins = aerobic_mins,
        anaerobicMins = anaerobicMins,
        limitMins = limit_mins,
    )

    private fun HealthSleep.toDomain() = WatchSleepSession(
        date = ymd(year, month, day),
        totalMinutes = totalSleepMinutes,
        deepMinutes = deepSleepMinutes,
        lightMinutes = lightSleepMinutes,
        awakeMinutes = null, // not a distinct field on v2 HealthSleep; present on V3
        deepCount = deepSleepCount,
        lightCount = lightSleepCount,
        awakeCount = awakeCount,
        score = sleepScore.nonZero(),
        sleepEndHour = sleepEndedTimeH,
        sleepEndMinute = sleepEndedTimeM,
    )

    private companion object {
        const val TAG = "IdoSdkWatchEngine"

        /** Watch ints use 0 as "not measured" for several fields; surface those as null. */
        fun Int.nonZero(): Int? = if (this != 0) this else null

        fun ymd(year: Int, month: Int, day: Int): String =
            "%04d-%02d-%02d".format(year, month, day)
    }
}
