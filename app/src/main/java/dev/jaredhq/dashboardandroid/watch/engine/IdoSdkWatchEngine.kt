package dev.jaredhq.dashboardandroid.watch.engine

import android.app.Application
import android.util.Log
import com.ido.ble.BLEManager
import com.ido.ble.InitParam
import com.ido.ble.bluetooth.connect.ConnectFailedReason
import com.ido.ble.bluetooth.device.BLEDevice
import com.ido.ble.callback.BindCallBack
import com.ido.ble.callback.CallBackManager
import com.ido.ble.callback.ConnectCallBack
import com.ido.ble.callback.ScanCallBack
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

    // Defaults to a logcat listener so a sync is observable on-device before the upload
    // path is wired; ServiceLocator/repository can replace it with the real uploader.
    override var listener: WatchHealthListener? = LoggingWatchHealthListener

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

            // TEST SCAFFOLD (debug builds only): auto-connect to the known watch a few seconds
            // after init so the connect→bind→sync→metrics path can be exercised on-device
            // without UI. Remove once the Watch screen drives connect/sync. Gated off in release.
            if (dev.jaredhq.dashboardandroid.BuildConfig.DEBUG && DEBUG_AUTO_CONNECT_MAC != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.i(TAG, "DEBUG auto-connect → $DEBUG_AUTO_CONNECT_MAC")
                    runCatching { connect(DEBUG_AUTO_CONNECT_MAC) }
                        .onFailure { Log.e(TAG, "debug auto-connect failed", it) }
                }, 4_000)
            }
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

    @Volatile
    private var targetMac: String? = null

    override fun connect(macAddress: String) {
        // autoConnect only works for an already-bound device; for a watch bound to VeryFit we
        // must scan → connect(BLEDevice) → bind() to claim it. Start a scan and match our MAC.
        targetMac = macAddress.uppercase()
        Log.i(TAG, "connect($macAddress) → scanning to find + bind")
        BLEManager.startScanDevices()
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
        cm.registerScanCallBack(scanCallBack)
        cm.registerConnectCallBack(connectCallBack)
        cm.registerBindCallBack(bindCallBack)
        cm.registerSyncActivityCallBack(activityCallBack)
        cm.registerSyncHealthCallBack(healthCallBack)
        // NEXT: cm.registerSyncV3CallBack(v3CallBack) for SpO2/body-composition/stress/etc.
    }

    // ── Scan → connect → bind lifecycle ──────────────────────────────────────────────

    private val scanCallBack = object : ScanCallBack.ICallBack {
        override fun onStart() { Log.i(TAG, "scan started (target=$targetMac)") }
        override fun onScanFinished() { Log.i(TAG, "scan finished") }
        override fun onFindDevice(device: BLEDevice?) {
            val addr = device?.mDeviceAddress ?: return
            Log.d(TAG, "found '${device.mDeviceName}' $addr rssi=${device.mRssi}")
            if (addr.equals(targetMac, ignoreCase = true)) {
                Log.i(TAG, "target watch found → stop scan + connect")
                BLEManager.stopScanDevices()
                BLEManager.connect(device)
            }
        }
    }

    private val connectCallBack = object : ConnectCallBack.ICallBack {
        override fun onConnectStart(mac: String?) { Log.i(TAG, "onConnectStart $mac") }
        override fun onConnecting(mac: String?) { Log.i(TAG, "onConnecting $mac") }
        override fun onRetry(times: Int, mac: String?) { Log.i(TAG, "onRetry $times $mac") }

        override fun onConnectSuccess(mac: String?) {
            val bound = runCatching { BLEManager.isBind() }.getOrDefault(false)
            Log.i(TAG, "onConnectSuccess $mac bound=$bound")
            if (!bound) {
                // Fresh SDK DB isn't bound to this watch (VeryFit owns the original bond).
                // Bind to claim it — may prompt a confirmation on the watch face (onNeedAuth).
                Log.i(TAG, "not bound → bind()")
                BLEManager.bind()
            }
        }

        override fun onInitCompleted(mac: String?) {
            // onInitCompleted fires ~immediately after connect — BEFORE an in-progress bind()
            // completes (~8s later). Syncing here on an unbound device times out. So only sync
            // now if already bound (the reconnect case); a fresh bind syncs from onSuccess below.
            val bound = runCatching { BLEManager.isBind() }.getOrDefault(false)
            if (bound) {
                Log.i(TAG, "onInitCompleted $mac (bound) — starting health sync")
                syncHealth()
            } else {
                Log.i(TAG, "onInitCompleted $mac — awaiting bind before sync")
            }
        }

        override fun onDeviceInNotBindStatus(mac: String?) {
            Log.i(TAG, "onDeviceInNotBindStatus $mac (bind() driven from onConnectSuccess)")
        }

        override fun onConnectFailed(reason: ConnectFailedReason?, mac: String?) {
            Log.w(TAG, "onConnectFailed reason=$reason mac=$mac")
            listener?.onSyncFailed()
        }

        override fun onConnectBreak(mac: String?) { Log.w(TAG, "onConnectBreak $mac") }
        override fun onInDfuMode(device: BLEDevice?) { Log.w(TAG, "onInDfuMode") }
    }

    private val bindCallBack = object : BindCallBack.ICallBack {
        override fun onSuccess() {
            Log.i(TAG, "BIND SUCCESS — watch claimed by our app; starting health sync")
            // Now that bind has actually completed, the device will release its data. Give the
            // link a beat to settle (Classic profiles re-attach right after bind) then sync.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ syncHealth() }, 1_500)
        }
        override fun onNeedAuth(code: Int) {
            Log.w(TAG, "BIND needs confirmation on the WATCH FACE now (code=$code) — tap to allow")
        }
        override fun onReject() { Log.w(TAG, "bind REJECTED on the watch") }
        override fun onCancel() { Log.w(TAG, "bind cancelled") }
        override fun onFailed(error: BindCallBack.BindFailedError?) {
            Log.w(TAG, "bind FAILED: $error")
            listener?.onSyncFailed()
        }
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

    /** Logs every decoded metric + sync lifecycle to logcat (tag [TAG]). Observability default. */
    private object LoggingWatchHealthListener : WatchHealthListener {
        override fun onActivityDay(day: WatchActivityDay) { Log.i(TAG, "ACTIVITY $day") }
        override fun onHeartRateDay(day: WatchHeartRateDay) { Log.i(TAG, "HEART_RATE $day") }
        override fun onSleepSession(session: WatchSleepSession) { Log.i(TAG, "SLEEP $session") }
        override fun onSyncProgress(percent: Int) { Log.i(TAG, "sync progress $percent%") }
        override fun onSyncComplete() { Log.i(TAG, "sync complete") }
        override fun onSyncFailed() { Log.w(TAG, "sync failed") }
    }

    private companion object {
        const val TAG = "IdoSdkWatchEngine"

        /** Debug-only auto-connect target (the Active 4 Pro MAC). Null disables the scaffold. */
        val DEBUG_AUTO_CONNECT_MAC: String? = "F4:91:29:51:C6:45"

        /** Watch ints use 0 as "not measured" for several fields; surface those as null. */
        fun Int.nonZero(): Int? = if (this != 0) this else null

        fun ymd(year: Int, month: Int, day: Int): String =
            "%04d-%02d-%02d".format(year, month, day)
    }
}
