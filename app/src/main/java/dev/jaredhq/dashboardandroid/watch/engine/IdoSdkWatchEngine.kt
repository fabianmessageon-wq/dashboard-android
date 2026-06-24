package dev.jaredhq.dashboardandroid.watch.engine

import android.app.Application
import android.util.Log
import com.ido.ble.BLEManager
import com.ido.ble.InitParam
import com.ido.ble.bluetooth.connect.ConnectFailedReason
import com.ido.ble.bluetooth.device.BLEDevice
import com.ido.ble.business.sync.ISyncDataListener
import com.ido.ble.business.sync.ISyncProgressListener
import com.ido.ble.business.sync.SyncPara
import com.ido.ble.callback.BaseGetDeviceInfoCallBack
import com.ido.ble.callback.BindCallBack
import com.ido.ble.callback.CallBackManager
import com.ido.ble.callback.ConnectCallBack
import com.ido.ble.callback.ScanCallBack
import com.ido.ble.data.manage.database.HealthActivity
import com.ido.ble.data.manage.database.HealthActivityV3
import com.ido.ble.data.manage.database.HealthBloodPressed
import com.ido.ble.data.manage.database.HealthBloodPressedItem
import com.ido.ble.data.manage.database.HealthHeartRate
import com.ido.ble.data.manage.database.HealthHeartRateItem
import com.ido.ble.data.manage.database.HealthSleep
import com.ido.ble.data.manage.database.HealthSleepItem
import com.ido.ble.data.manage.database.HealthSleepV3
import com.ido.ble.data.manage.database.HealthSport
import com.ido.ble.data.manage.database.HealthSportItem
import com.ido.ble.protocol.model.SupportFunctionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private val _connectionState =
        MutableStateFlow(WatchEngineConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<WatchEngineConnectionState> =
        _connectionState.asStateFlow()

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

    // The SDK's post-connect "encrypted handshake" (encryptedAtConnectedIfFunctionInfoIsNull)
    // needs the device function/capability table; a fresh SDK DB lacks it and sync fails with
    // "supportFunctionInfo is null". We fetch it via getFunctionTables() once per connected
    // session and only sync after onGetFunctionTable caches it. (Roadmap W4 / ADR 0001.)
    @Volatile
    private var functionTableCached = false

    @Volatile
    private var pendingSyncAfterFunctionTable = false

    override fun connect(macAddress: String) {
        // autoConnect only works for an already-bound device; for a watch bound to VeryFit we
        // must scan → connect(BLEDevice) → bind() to claim it. Start a scan and match our MAC.
        targetMac = macAddress.uppercase()
        functionTableCached = false
        Log.i(TAG, "connect($macAddress) → scanning to find + bind")
        _connectionState.value = WatchEngineConnectionState.SCANNING
        BLEManager.startScanDevices()
    }

    override fun disconnect() {
        functionTableCached = false
        pendingSyncAfterFunctionTable = false
        _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        BLEManager.disConnect()
    }

    override fun isConnected(): Boolean = BLEManager.isConnected()

    override fun syncHealth() {
        if (!isConnected()) {
            Log.w(TAG, "syncHealth() ignored — not connected")
            listener?.onSyncFailed()
            return
        }
        if (!functionTableCached) {
            // Without the device function table the encrypted handshake fails; fetch it first
            // and resume the sync from onGetFunctionTable below.
            Log.i(TAG, "function table not cached → getFunctionTables() before sync")
            pendingSyncAfterFunctionTable = true
            BLEManager.getFunctionTables()
            return
        }
        startSync()
    }

    private fun startSync() {
        // Orchestrated, sequential sync — the exact path the stock VeryFit app uses
        // (DeviceManagerPresenter / BaseHomeFragmentPresenter): one SyncPara drives config →
        // health (HR/sleep/sport) → activity → V3 as ordered tasks. Firing
        // startSyncHealthData()/startSyncActivityData() concurrently instead made the watch NAK
        // the interleaved data-pull commands with status=3. Data arrives via the ISyncDataListener.
        Log.i(TAG, "syncAllData (config+health+activity+V3, sequential)")
        _connectionState.value = WatchEngineConnectionState.SYNCING
        val para = SyncPara().apply {
            // Config sync *pushes* phone settings to the watch; we only read health, and on this
            // device it fails ("sync config failed!13"), so skip it (VeryFit's restart path does
            // the same). The data tasks are unaffected.
            isNeedSyncConfigData = false
            timeoutMillisecond = 300_000L    // 5 min, matching VeryFit's home-sync timeout
            iSyncProgressListener = syncProgressListener
            iSyncDataListener = syncDataListener
        }
        BLEManager.syncAllData(para)
    }

    /** Connection state to settle on once a sync run ends — CONNECTED if the link is still up. */
    private fun postSyncState(): WatchEngineConnectionState =
        if (isConnected()) WatchEngineConnectionState.CONNECTED
        else WatchEngineConnectionState.DISCONNECTED

    private fun registerCallbacks() {
        val cm = CallBackManager.getManager()
        cm.registerScanCallBack(scanCallBack)
        cm.registerConnectCallBack(connectCallBack)
        cm.registerBindCallBack(bindCallBack)
        cm.registerGetDeviceInfoCallBack(deviceInfoCallBack)
        // NOTE: no registerSyncActivity/HealthCallBack here. The orchestrated syncAllData() path
        // delivers every record through the SyncPara.ISyncDataListener (syncDataListener) below;
        // registering the CallBackManager SyncCallBacks too would double-deliver each record.
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
                _connectionState.value = WatchEngineConnectionState.CONNECTING
                BLEManager.stopScanDevices()
                BLEManager.connect(device)
            }
        }
    }

    private val connectCallBack = object : ConnectCallBack.ICallBack {
        override fun onConnectStart(mac: String?) {
            Log.i(TAG, "onConnectStart $mac")
            _connectionState.value = WatchEngineConnectionState.CONNECTING
        }
        override fun onConnecting(mac: String?) { Log.i(TAG, "onConnecting $mac") }
        override fun onRetry(times: Int, mac: String?) { Log.i(TAG, "onRetry $times $mac") }

        override fun onConnectSuccess(mac: String?) {
            val bound = runCatching { BLEManager.isBind() }.getOrDefault(false)
            Log.i(TAG, "onConnectSuccess $mac bound=$bound")
            if (!bound) {
                // Fresh SDK DB isn't bound to this watch (VeryFit owns the original bond).
                // Bind to claim it — may prompt a confirmation on the watch face (onNeedAuth).
                Log.i(TAG, "not bound → bind()")
                _connectionState.value = WatchEngineConnectionState.BINDING
                BLEManager.bind()
            } else {
                _connectionState.value = WatchEngineConnectionState.CONNECTED
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
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
            listener?.onSyncFailed()
        }

        override fun onConnectBreak(mac: String?) {
            Log.w(TAG, "onConnectBreak $mac")
            functionTableCached = false
            pendingSyncAfterFunctionTable = false
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onInDfuMode(device: BLEDevice?) { Log.w(TAG, "onInDfuMode") }
    }

    private val bindCallBack = object : BindCallBack.ICallBack {
        override fun onSuccess() {
            Log.i(TAG, "BIND SUCCESS — watch claimed by our app; fetching function table then syncing")
            _connectionState.value = WatchEngineConnectionState.CONNECTED
            // Now that bind has actually completed, the device will release its data. Give the
            // link a beat to settle (Classic profiles re-attach right after bind), then syncHealth()
            // which first pulls the function table (getFunctionTables) before the actual sync.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ syncHealth() }, 1_500)
        }
        override fun onNeedAuth(code: Int) {
            Log.w(TAG, "BIND needs confirmation on the WATCH FACE now (code=$code) — tap to allow")
            _connectionState.value = WatchEngineConnectionState.AWAITING_WATCH_CONFIRMATION
        }
        override fun onReject() {
            Log.w(TAG, "bind REJECTED on the watch")
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onCancel() {
            Log.w(TAG, "bind cancelled")
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onFailed(error: BindCallBack.BindFailedError?) {
            Log.w(TAG, "bind FAILED: $error")
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
            listener?.onSyncFailed()
        }
    }

    // ── Device info: function table gate ──────────────────────────────────────────────
    // Extends BaseGetDeviceInfoCallBack (no-op defaults) so we only handle onGetFunctionTable.
    private val deviceInfoCallBack = object : BaseGetDeviceInfoCallBack() {
        override fun onGetFunctionTable(info: SupportFunctionInfo?) {
            functionTableCached = info != null
            Log.i(TAG, "onGetFunctionTable received (cached=$functionTableCached)")
            if (pendingSyncAfterFunctionTable) {
                pendingSyncAfterFunctionTable = false
                if (functionTableCached) {
                    startSync()
                } else {
                    Log.w(TAG, "function table is null — cannot complete sync")
                    listener?.onSyncFailed()
                }
            }
        }
    }

    // ── Orchestrated sync listeners (BLEManager.syncAllData) ──────────────────────────

    /** Lifecycle of the whole ordered sync run (config → health → activity → V3). */
    private val syncProgressListener = object : ISyncProgressListener {
        override fun onStart() { Log.i(TAG, "syncAllData onStart") }
        override fun onProgress(percent: Int) { listener?.onSyncProgress(percent) }
        override fun onSuccess() {
            Log.i(TAG, "syncAllData onSuccess")
            _connectionState.value = postSyncState()
            listener?.onSyncComplete()
        }
        override fun onFailed(type: SyncPara.SyncFailedType?) {
            // Often the benign post-transfer conn-param step failing after all data is delivered
            // (roadmap W4) — the link is usually still up, so reflect actual connectivity.
            Log.w(TAG, "syncAllData onFailed: $type")
            _connectionState.value = postSyncState()
            listener?.onSyncFailed()
        }
    }

    /**
     * Receives every decoded record from the orchestrated sync. The v2 metrics we ship today
     * (daily activity, heart-rate summary, sleep) are mapped to domain; workouts, BP, and the full
     * V3 metric set are no-op sinks for now — wiring them is W6 and only needs a body here, since
     * the data already arrives through this one listener.
     */
    private val syncDataListener = object : ISyncDataListener {
        override fun onGetActivityData(data: HealthActivity?) {
            data?.let { listener?.onActivityDay(it.toDomain()) }
        }

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
            if (data != null) Log.d(TAG, "onGetSportData (workout) — mapping deferred (W6)")
        }

        override fun onGetBloodPressureData(
            data: HealthBloodPressed?,
            items: MutableList<HealthBloodPressedItem>?,
            isLast: Boolean,
        ) {
            if (data != null) Log.d(TAG, "onGetBloodPressureData — mapping deferred (W6)")
        }

        // ── V3 health (this watch's real path) ──
        override fun onGetHealthSleepV3Data(data: HealthSleepV3?) {
            // The sync stream can include empty/sentinel records (year 0, all-zero) — skip them.
            if (data == null || data.get_up_year == 0) return
            listener?.onSleepSession(data.toDomain())
        }

        override fun onGetHealthActivityV3Data(data: HealthActivityV3?) {
            if (data == null || data.year == 0) return
            listener?.onWorkout(data.toWorkout())
        }

        // ── V3 intraday point metrics (SpO2 / HRV / respiratory / body energy / temperature) ──
        // Each arrives as a parent day record + a list of within-day item samples; we emit one
        // domain reading per item with a resolved wall-clock timestamp (see [localDateTime]).
        // Zero/sentinel values and empty item lists are skipped.

        override fun onGetHealthSpO2Data(
            data: com.ido.ble.data.manage.database.HealthSpO2?,
            items: MutableList<com.ido.ble.data.manage.database.HealthSpO2Item>?,
            isLast: Boolean,
        ) {
            if (data == null || data.year == 0 || items.isNullOrEmpty()) return
            items.forEach { item ->
                if (item.value <= 0) return@forEach
                // SpO2 item.offset is the sample's minute-of-day within the parent day.
                runCatching {
                    localDateTime(data.year, data.month, data.day, item.offset * 60)
                }.onSuccess { ts ->
                    listener?.onSpo2Reading(WatchSpo2Reading(recordedAt = ts, percent = item.value))
                }
            }
        }

        override fun onGetHealthHRV(data: com.ido.ble.data.manage.database.HealthHRVdata?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            data.items.forEach { item ->
                if (item.hrvValue <= 0) return@forEach
                // minOffset is minutes from the day's startTime (both minute-of-day units).
                runCatching {
                    localDateTime(data.year, data.month, data.day, (data.startTime + item.minOffset) * 60)
                }.onSuccess { ts ->
                    listener?.onHrvReading(WatchHrvReading(recordedAt = ts, hrvMs = item.hrvValue))
                }
            }
        }

        override fun onGetHealthRespiratoryRate(data: com.ido.ble.data.manage.database.HealthRespiratoryRate?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            data.items.forEach { item ->
                if (item.respid <= 0) return@forEach
                // respiratory item.start_time is the sample's minute-of-day.
                runCatching {
                    localDateTime(data.year, data.month, data.day, item.start_time * 60)
                }.onSuccess { ts ->
                    listener?.onRespiratoryReading(
                        WatchRespiratoryReading(recordedAt = ts, breathsPerMinute = item.respid)
                    )
                }
            }
        }

        override fun onGetHealthBodyPower(data: com.ido.ble.data.manage.database.HealthBodyPower?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            data.items.forEach { item ->
                if (item.value <= 0) return@forEach
                // body-power item.offset is minutes from the day's start_time.
                runCatching {
                    localDateTime(data.year, data.month, data.day, (data.start_time + item.offset) * 60)
                }.onSuccess { ts ->
                    listener?.onBodyEnergyReading(WatchBodyEnergyReading(recordedAt = ts, energy = item.value))
                }
            }
        }

        override fun onGetHealthTemperature(data: com.ido.ble.data.manage.database.HealthTemperature?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            // Temperature carries an explicit base time (hour/minute/sec) and per-item offset whose
            // unit (second vs minute) is given by time_offset_unit — no guessing here.
            val baseSeconds = data.hour * 3600 + data.minute * 60 + data.sec
            val unitSeconds =
                if (data.time_offset_unit == com.ido.ble.data.manage.database.HealthTemperature.TIME_OFFSET_UNIT_MINUTE) 60 else 1
            data.items.forEach { item ->
                if (item.value <= 0) return@forEach
                // Raw value is centi-degrees Celsius (e.g. 3650 → 36.50 °C).
                runCatching {
                    localDateTime(data.year, data.month, data.day, baseSeconds + item.offset * unitSeconds)
                }.onSuccess { ts ->
                    listener?.onTemperatureReading(
                        WatchTemperatureReading(recordedAt = ts, celsius = item.value / 100.0)
                    )
                }
            }
        }

        // ── Still logged-only (need domain models / dashboard columns): GPS, drink plan, V3 sport
        // (a per-minute daily activity rollup, distinct from HealthActivityV3 workout sessions),
        // BP V3, body composition, noise, pressure, swimming, ECG, emotion-health. ──
        override fun onGetHealthSportV3Data(data: com.ido.ble.data.manage.database.HealthSportV3?) {
            if (data != null) Log.d(TAG, "V3 sport (daily activity rollup) received — mapping deferred")
        }
        override fun onGetDrinkPlan(data: com.ido.ble.protocol.model.DrinkPlanData?) {}
        override fun onGetGpsData(data: com.ido.ble.gps.database.HealthGps?, items: MutableList<com.ido.ble.gps.database.HealthGpsItem>?, isLast: Boolean) {}
        override fun onGetHealthBloodPressure(data: com.ido.ble.data.manage.database.HealthBloodPressureV3?) {}
        override fun onGetHealthBodyCompositionData(data: com.ido.ble.data.manage.database.HealthBodyComposition?) {}
        override fun onGetHealthGpsV3Data(data: com.ido.ble.data.manage.database.HealthGpsV3?) {}
        override fun onGetHealthHeartRateSecondData(data: com.ido.ble.data.manage.database.HealthHeartRateSecond?, isLast: Boolean) {}
        override fun onGetHealthNoiseData(data: com.ido.ble.data.manage.database.HealthNoise?) {}
        override fun onGetHealthPressureData(data: com.ido.ble.data.manage.database.HealthPressure?, items: MutableList<com.ido.ble.data.manage.database.HealthPressureItem>?, isLast: Boolean) {}
        override fun onGetHealthSwimmingData(data: com.ido.ble.data.manage.database.HealthSwimming?) {}
        override fun onGetHealthV3EcgData(data: com.ido.ble.data.manage.database.HealthV3Ecg?) {}
        override fun onGetHealthV3EmotionHealthData(data: com.ido.ble.data.manage.database.HealthV3EmotionHealth?) {}
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

    // ── V3 mappers (the Active 4 Pro's real health path) ──

    private fun HealthSleepV3.toDomain() = WatchSleepSession(
        // V3 keys the night by wake-up ("get up") time.
        date = ymd(get_up_year, get_up_month, get_up_day),
        totalMinutes = total_sleep_time_mins,
        deepMinutes = deep_mins,
        lightMinutes = light_mins,
        awakeMinutes = wake_mins,        // V3 reports awake minutes distinctly (v2 did not)
        deepCount = deep_count,
        lightCount = light_count,
        awakeCount = wake_count,
        score = sleep_score.nonZero(),
        sleepEndHour = get_up_hour,
        sleepEndMinute = get_up_minte,   // SDK field spelling
        // NOTE: V3 also carries rem_mins/rem_count + sleep_avg_hr/spo2/respir — surfaced once the
        // domain model + dashboard schema gain those columns (W6/W5).
    )

    private fun HealthActivityV3.toWorkout() = WatchWorkout(
        startDateTime = ymdhms(year, month, day, hour, minute, second),
        endDateTime = ymdhms(end_year, end_month, end_day, end_hour, end_minute, end_sec),
        type = act_type,
        durationSeconds = durations.nonZero(),
        calories = calories.nonZero(),
        distanceMeters = distance.nonZero(),
        steps = step.nonZero(),
        avgHeartRate = avg_hr_value.nonZero(),
        maxHeartRate = max_hr_value.nonZero(),
        minHeartRate = min_hr_value.nonZero(),
        avgSpeed = avg_speed.nonZero(),
        maxSpeed = max_speed.nonZero(),
        trainingEffect = training_effect.nonZero(),
        vo2Max = vO2max.nonZero(),
    )

    /** Logs every decoded metric + sync lifecycle to logcat (tag [TAG]). Observability default. */
    private object LoggingWatchHealthListener : WatchHealthListener {
        override fun onActivityDay(day: WatchActivityDay) { Log.i(TAG, "ACTIVITY $day") }
        override fun onHeartRateDay(day: WatchHeartRateDay) { Log.i(TAG, "HEART_RATE $day") }
        override fun onSleepSession(session: WatchSleepSession) { Log.i(TAG, "SLEEP $session") }
        override fun onWorkout(workout: WatchWorkout) { Log.i(TAG, "WORKOUT $workout") }
        override fun onSpo2Reading(reading: WatchSpo2Reading) { Log.i(TAG, "SPO2 $reading") }
        override fun onHrvReading(reading: WatchHrvReading) { Log.i(TAG, "HRV $reading") }
        override fun onRespiratoryReading(reading: WatchRespiratoryReading) { Log.i(TAG, "RESP $reading") }
        override fun onTemperatureReading(reading: WatchTemperatureReading) { Log.i(TAG, "TEMP $reading") }
        override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) { Log.i(TAG, "BODY_ENERGY $reading") }
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

        fun ymdhms(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
            "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)

        private val LOCAL_DT_FMT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /**
         * Resolve a "YYYY-MM-DD HH:MM:SS" wall-clock string from a day + an offset in seconds from
         * that day's local midnight. Goes through [java.time.LocalDate] so a sample that rolls past
         * midnight carries to the next day correctly. Throws on an invalid date — callers wrap in
         * runCatching so one bad record can't abort the sync.
         */
        fun localDateTime(year: Int, month: Int, day: Int, secondsFromMidnight: Int): String =
            java.time.LocalDate.of(year, month, day)
                .atStartOfDay()
                .plusSeconds(secondsFromMidnight.toLong())
                .format(LOCAL_DT_FMT)
    }
}
