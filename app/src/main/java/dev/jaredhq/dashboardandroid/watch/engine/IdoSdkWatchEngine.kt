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
import com.ido.ble.data.manage.database.HealthSportV3
import com.ido.ble.protocol.model.SupportFunctionInfo
import com.veryfit.multi.nativeprotocol.Protocol
import dev.jaredhq.dashboardandroid.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // Buffered so an emit from the SDK callback thread never suspends/drops (no active collector yet
    // is fine — the connection service subscribes while running).
    private val _controlEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<WatchControlEvent>(extraBufferCapacity = 8)
    override val controlEvents: kotlinx.coroutines.flow.SharedFlow<WatchControlEvent> =
        _controlEvents.asSharedFlow()

    @Volatile
    private var initialized = false

    /** Raw per-sync delivery tally (incl. callbacks we drop) for the Phase-2 support matrix. */
    private val diagnostics = WatchSyncDiagnostics()

    override fun init() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // SDK lifecycle hooks. onApplicationCreate must run before init().
            BLEManager.onApplicationCreate(app)
            BLEManager.init(buildInitParam())
            // InitParam.isEnableLog only gates the SDK's on-device *file* log (ido-logs/). The
            // native protocol layer (libVeryFitMulti.so) independently prints raw BLE TX/RX frames
            // AND notification contact/body to logcat under tag "DEBUG LOG" regardless of that flag
            // — verified on a release build. In a release/private daily-use build that would leak
            // SMS/caller text and decoded health frames into logcat and `adb bugreport` captures.
            // Silence the native logcat stream off the debug build. (Privacy: W7 + raw-frame
            // redaction; CLAUDE.md "privacy-conscious raw packet logging".)
            runCatching {
                val p = Protocol.getInstance()
                val logPath = (app.filesDir.absolutePath + "/ido-logs").toByteArray()
                // EnableLog(console, file, path): kill the logcat ("DEBUG LOG") + file streams.
                p.EnableLog(BuildConfig.DEBUG, BuildConfig.DEBUG, logPath)
                p.ProtocolSetLogEnable(BuildConfig.DEBUG)
            }.onFailure { Log.w(TAG, "native protocol log toggle failed", it) }
            // Parity with the stock VeryFit app (VeryFitApp.initIdoSdk → setIsNeedRemoveBondBeforeConnect(true)):
            // clear any stale OS-level bond before each connect. Our watch was originally bonded to
            // VeryFit, and on this IDO/SiFli family a stale bond is the classic cause of a connect that
            // suddenly regresses after firmware updates or VeryFit use (see CLAUDE.md stale-bond
            // heuristic). The SDK's own app-level claim is bind()/isBind(), independent of this.
            BLEManager.setIsNeedRemoveBondBeforeConnect(true)
            registerCallbacks()
            initialized = true
            Log.i(TAG, "IDO SDK initialised")
            // The product Watch screen now drives connect/sync via this engine, and
            // WatchSyncWorker drives it in the background — so the old debug auto-connect scaffold
            // is gone (it would race the UI/worker for the single GATT link).
        }
    }

    private fun buildInitParam(): InitParam = InitParam().apply {
        databaseName = "ido-watch.db"
        // The app consumes decoded records directly from SyncPara.ISyncDataListener and uploads
        // them through DashboardRepository; it never queries the SDK's greenDAO health DB. Avoid
        // retaining a second local health database on device.
        isSaveDeviceDataToDB = false
        // Do not enable DB encryption unless SQLCipher dependency/native libs are deliberately
        // added and verified. With SDK DB persistence disabled, this should remain false.
        isEncryptedDBData = false
        // VeryFit enables SDK SharedPreferences string encryption; this path is SDK-internal Java
        // crypto and does not require SQLCipher. Existing installs may need clear-data/rebind if
        // legacy plaintext SDK prefs fail to decrypt.
        isEncryptedSPData = true
        // SDK file logs can include private watch/protocol details. Keep them on only for debug
        // builds; release/private daily-use builds should not retain extra SDK logs on device.
        isEnableLog = BuildConfig.DEBUG
        log_save_path = app.filesDir.absolutePath + "/ido-logs"
        log_save_days = if (BuildConfig.DEBUG) 3 else 0
    }

    @Volatile
    private var targetMac: String? = null

    // The SDK's post-connect "encrypted handshake" (encryptedAtConnectedIfFunctionInfoIsNull)
    // needs the device function/capability table; a fresh SDK DB lacks it and sync fails with
    // "supportFunctionInfo is null". We fetch it via getFunctionTables() once per connected
    // session and only sync after onGetFunctionTable caches it. (Roadmap W4 / ADR 0001.)
    @Volatile
    private var functionTableCached = false

    // Retained so notification sends (W7) can branch on the device's message capability
    // (ex_table_main10_v3_notify_msg) the same way the stock VeryFit app does.
    @Volatile
    private var cachedFunctionInfo: SupportFunctionInfo? = null

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
        cachedFunctionInfo = null
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
        // Never let a second request fire a concurrent syncAllData() (or a duplicate
        // getFunctionTables()) while one is already in flight/pending — the watch NAKs interleaved
        // data-pull commands with status=3 (see [startSync]). Multiple triggers legitimately race
        // here: the connect auto-sync, the always-on service's periodic timer, and a manual "Sync now"
        // can all land while a run is active.
        if (_connectionState.value == WatchEngineConnectionState.SYNCING || pendingSyncAfterFunctionTable) {
            Log.i(TAG, "syncHealth() ignored — a sync is already in progress/pending")
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

    override fun sendNotification(notification: WatchNotification): Boolean {
        if (!isConnected()) {
            Log.w(TAG, "sendNotification ignored — not connected")
            return false
        }
        return try {
            // Mirror VeryFit's capability gate (MsgNotificationHelper.sendNotificationDevice): devices
            // flagged ex_table_main10_v3_notify_msg take the V3 message-notice path; the rest take the
            // newer NewMessageInfo path. Each enumeration has its own per-category type codes (see
            // [v3TypeFor]/[newTypeFor]), so an SMS/call renders as such on the watch. The function
            // table is fetched once per connected session (see [syncHealth]); if it hasn't arrived yet
            // we fall back to the newer path (the common case).
            val useOldV3 = cachedFunctionInfo?.ex_table_main10_v3_notify_msg == true
            if (useOldV3) {
                @Suppress("DEPRECATION")
                val notice = com.ido.ble.protocol.model.V3MessageNotice().apply {
                    evtType = v3TypeFor(notification.category)
                    contact = notification.appName
                    dataText = notification.body
                    // For an incoming call, offer answer/reject/mute on the watch face; the taps come
                    // back via [deviceControlCallBack]. Other categories carry no controls.
                    val isCall = notification.category == WatchNotificationCategory.CALL
                    supportAnswering = isCall
                    supportHangUp = isCall
                    supportMute = isCall
                }
                // Never log the notice contents (contact/dataText = caller name + SMS body) at
                // release level — that would leak private message content to logcat. Path + category
                // + body length give a hardware tester enough to confirm a send without the payload.
                Log.i(TAG, "sendNotification (V3 notice) category=${notification.category} len=${notification.body.length}")
                BLEManager.setV3MessageNotice(notice)
            } else {
                val info = com.ido.ble.protocol.model.NewMessageInfo().apply {
                    type = newTypeFor(notification.category)
                    name = notification.appName
                    content = notification.body
                }
                // Same privacy rule as the V3 path: name/content (caller + SMS body) must not reach
                // logcat in a release build.
                Log.i(TAG, "sendNotification (new message) category=${notification.category} len=${notification.body.length}")
                BLEManager.setNewMessageDetailInfo(info)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendNotification failed", t)
            false
        }
    }

    private fun startSync() {
        // Orchestrated, sequential sync — the exact path the stock VeryFit app uses
        // (DeviceManagerPresenter / BaseHomeFragmentPresenter): one SyncPara drives config →
        // health (HR/sleep/sport) → activity → V3 as ordered tasks. Firing
        // startSyncHealthData()/startSyncActivityData() concurrently instead made the watch NAK
        // the interleaved data-pull commands with status=3. Data arrives via the ISyncDataListener.
        Log.i(TAG, "syncAllData (config+health+activity+V3, sequential)")
        diagnostics.reset()
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
        cm.registerDeviceControlAppCallBack(deviceControlCallBack)
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
            cachedFunctionInfo = null
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

    // ── Device → app control (W7 call control) ────────────────────────────────────────
    // The watch sends these when the user taps answer/reject/mute on an incoming-call notification
    // we pushed with the support* flags set. We translate to domain [WatchControlEvent]s; the app's
    // connection service performs the actual telephony action. Non-call controls (media/camera/volume)
    // are ignored for now.
    private val deviceControlCallBack = object : com.ido.ble.callback.DeviceControlAppCallBack.ICallBack {
        override fun onControlEvent(
            type: com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType?,
            value: Int,
        ) {
            val event = when (type) {
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.ANSWER_PHONE ->
                    WatchControlEvent.ANSWER_CALL
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.REJECT_PHONE ->
                    WatchControlEvent.REJECT_CALL
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.MUTE_PHONE ->
                    WatchControlEvent.MUTE_CALL
                else -> null
            }
            if (event != null) {
                Log.i(TAG, "device control: $type → $event")
                _controlEvents.tryEmit(event)
            }
        }

        override fun onAntiLostNotice(on: Boolean, time: Long) {}
        override fun onFindPhone(on: Boolean, time: Long) {}
        override fun onOneKeySOS(on: Boolean, time: Long) {}
    }

    // ── Device info: function table gate ──────────────────────────────────────────────
    // Extends BaseGetDeviceInfoCallBack (no-op defaults) so we only handle onGetFunctionTable.
    private val deviceInfoCallBack = object : BaseGetDeviceInfoCallBack() {
        override fun onGetFunctionTable(info: SupportFunctionInfo?) {
            functionTableCached = info != null
            cachedFunctionInfo = info
            Log.i(TAG, "onGetFunctionTable received (cached=$functionTableCached)")
            if (info != null) logFunctionTable()
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
            logSyncDiagnostics()
            _connectionState.value = postSyncState()
            listener?.onSyncComplete()
        }
        override fun onFailed(type: SyncPara.SyncFailedType?) {
            // Often the benign post-transfer conn-param step failing after all data is delivered
            // (roadmap W4) — the link is usually still up, so reflect actual connectivity.
            Log.w(TAG, "syncAllData onFailed: $type")
            logSyncDiagnostics()
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
            data?.let {
                diagnostics.record(WatchSyncDiagnostics.ACTIVITY_DAY_V2, parentRecords = 1, mappedReadings = 1)
                listener?.onActivityDay(it.toDomain())
            }
        }

        override fun onGetHeartRateData(
            data: HealthHeartRate?,
            items: MutableList<HealthHeartRateItem>?,
            isLast: Boolean,
        ) {
            data?.let {
                diagnostics.record(
                    WatchSyncDiagnostics.HEART_RATE_DAY_V2,
                    parentRecords = 1, itemSamples = items?.size ?: 0, mappedReadings = 1,
                )
                listener?.onHeartRateDay(it.toDomain())
            }
        }

        override fun onGetSleepData(
            data: HealthSleep?,
            items: MutableList<HealthSleepItem>?,
        ) {
            data?.let {
                diagnostics.record(
                    WatchSyncDiagnostics.SLEEP_V2,
                    parentRecords = 1, itemSamples = items?.size ?: 0, mappedReadings = 1,
                )
                listener?.onSleepSession(it.toDomain())
            }
        }

        override fun onGetSportData(
            data: HealthSport?,
            items: MutableList<HealthSportItem>?,
            isLast: Boolean,
        ) {
            // Delivered but dropped (no clean v2-workout mapping; this device uses the V3 path).
            if (data != null) {
                diagnostics.record(WatchSyncDiagnostics.SPORT_V2, parentRecords = 1, itemSamples = items?.size ?: 0)
                Log.d(TAG, "onGetSportData (workout) — mapping deferred (W6)")
            }
        }

        override fun onGetBloodPressureData(
            data: HealthBloodPressed?,
            items: MutableList<HealthBloodPressedItem>?,
            isLast: Boolean,
        ) {
            // Delivered but dropped (v2 BP; this device reports BP on the V3 path below).
            if (data != null) {
                diagnostics.record(WatchSyncDiagnostics.BLOOD_PRESSURE_V2, parentRecords = 1, itemSamples = items?.size ?: 0)
                Log.d(TAG, "onGetBloodPressureData — mapping deferred (W6)")
            }
        }

        // ── V3 health (this watch's real path) ──
        override fun onGetHealthSleepV3Data(data: HealthSleepV3?) {
            // The sync stream can include empty/sentinel records (year 0, all-zero) — skip them.
            if (data == null || data.get_up_year == 0) return
            diagnostics.record(WatchSyncDiagnostics.SLEEP_V3, parentRecords = 1, mappedReadings = 1)
            // Ground-truth capture for the W5/W6 sleep-field expansion: the toDomain() mapper drops
            // rem_* and sleep_avg_* today, so log the raw richer fields here. DEBUG-gated, logcat-only
            // (developer-only health data per CLAUDE.md — never uploaded model-facing). Remove once the
            // domain model + dashboard schema carry these columns.
            if (BuildConfig.DEBUG) {
                Log.d(
                    TAG,
                    "sleepV3 capture: date=${WatchTime.ymd(data.get_up_year, data.get_up_month, data.get_up_day)} " +
                        "getUp=${data.get_up_hour}:${data.get_up_minte} total=${data.total_sleep_time_mins} " +
                        "deep=${data.deep_mins}(${data.deep_count}) light=${data.light_mins}(${data.light_count}) " +
                        "wake=${data.wake_mins}(${data.wake_count}) rem=${data.rem_mins}(${data.rem_count}) " +
                        "score=${data.sleep_score} avgHr=${data.sleep_avg_hr_value} " +
                        "avgSpo2=${data.sleep_avg_spo2_value} avgRespir=${data.sleep_avg_respir_rate_value}",
                )
            }
            listener?.onSleepSession(data.toDomain())
        }

        override fun onGetHealthActivityV3Data(data: HealthActivityV3?) {
            if (data == null || data.year == 0) return
            diagnostics.record(WatchSyncDiagnostics.WORKOUT_V3, parentRecords = 1, mappedReadings = 1)
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
            var mapped = 0
            items.forEach { item ->
                if (item.value <= 0) return@forEach
                // SpO2 item.offset is the sample's minute-of-day within the parent day.
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, item.offset * 60)
                }.onSuccess { ts ->
                    listener?.onSpo2Reading(WatchSpo2Reading(recordedAt = ts, percent = item.value))
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.SPO2, parentRecords = 1, itemSamples = items.size, mappedReadings = mapped)
        }

        override fun onGetHealthHRV(data: com.ido.ble.data.manage.database.HealthHRVdata?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            var mapped = 0
            data.items.forEach { item ->
                if (item.hrvValue <= 0) return@forEach
                // minOffset is minutes from the day's startTime (both minute-of-day units).
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, (data.startTime + item.minOffset) * 60)
                }.onSuccess { ts ->
                    listener?.onHrvReading(WatchHrvReading(recordedAt = ts, hrvMs = item.hrvValue))
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.HRV, parentRecords = 1, itemSamples = data.items.size, mappedReadings = mapped)
        }

        override fun onGetHealthRespiratoryRate(data: com.ido.ble.data.manage.database.HealthRespiratoryRate?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            var mapped = 0
            data.items.forEach { item ->
                if (item.respid <= 0) return@forEach
                // respiratory item.start_time is the sample's minute-of-day.
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, item.start_time * 60)
                }.onSuccess { ts ->
                    listener?.onRespiratoryReading(
                        WatchRespiratoryReading(recordedAt = ts, breathsPerMinute = item.respid)
                    )
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.RESPIRATORY, parentRecords = 1, itemSamples = data.items.size, mappedReadings = mapped)
        }

        override fun onGetHealthBodyPower(data: com.ido.ble.data.manage.database.HealthBodyPower?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            var mapped = 0
            data.items.forEach { item ->
                if (item.value <= 0) return@forEach
                // body-power item.offset is minutes from the day's start_time.
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, (data.start_time + item.offset) * 60)
                }.onSuccess { ts ->
                    listener?.onBodyEnergyReading(WatchBodyEnergyReading(recordedAt = ts, energy = item.value))
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.BODY_ENERGY, parentRecords = 1, itemSamples = data.items.size, mappedReadings = mapped)
        }

        override fun onGetHealthTemperature(data: com.ido.ble.data.manage.database.HealthTemperature?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            // Temperature carries an explicit base time (hour/minute/sec) and per-item offset whose
            // unit (second vs minute) is given by time_offset_unit — no guessing here.
            val baseSeconds = data.hour * 3600 + data.minute * 60 + data.sec
            val unitSeconds =
                if (data.time_offset_unit == com.ido.ble.data.manage.database.HealthTemperature.TIME_OFFSET_UNIT_MINUTE) 60 else 1
            var mapped = 0
            data.items.forEach { item ->
                if (item.value <= 0) return@forEach
                // Raw value is centi-degrees Celsius (e.g. 3650 → 36.50 °C).
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, baseSeconds + item.offset * unitSeconds)
                }.onSuccess { ts ->
                    listener?.onTemperatureReading(
                        WatchTemperatureReading(recordedAt = ts, celsius = item.value / 100.0)
                    )
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.TEMPERATURE, parentRecords = 1, itemSamples = data.items.size, mappedReadings = mapped)
        }

        // V3 blood pressure → one WatchBloodPressureReading per sample. Each item carries
        // sys_blood/dias_blood (mmHg) and an `offset` minute within the parent day; resolved like the
        // other point metrics as (day start_time + offset) minutes from midnight (the offset-unit
        // caveat applies; the date prefix is always exact). Skip sentinel/zero rows.
        override fun onGetHealthBloodPressure(data: com.ido.ble.data.manage.database.HealthBloodPressureV3?) {
            if (data == null || data.year == 0 || data.items.isNullOrEmpty()) return
            var mapped = 0
            data.items.forEach { item ->
                if (item.sys_blood <= 0 || item.dias_blood <= 0) return@forEach
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, (data.start_time + item.offset) * 60)
                }.onSuccess { ts ->
                    listener?.onBloodPressureReading(
                        WatchBloodPressureReading(
                            recordedAt = ts,
                            systolic = item.sys_blood,
                            diastolic = item.dias_blood,
                        )
                    )
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.BLOOD_PRESSURE_V3, parentRecords = 1, itemSamples = data.items.size, mappedReadings = mapped)
        }

        // IDO "pressure" is the mental-stress metric (0–100); each item is one sample with a value and
        // an `offset` minute from the day's startTime. Emit one WatchStressReading per item.
        // (HealthV3EmotionHealth is a *categorical* mood code — PLEASANT/CALM/UNPLEASANT — not a 0–100
        // score, so it is intentionally NOT mapped here; it stays logged-only.)
        override fun onGetHealthPressureData(
            data: com.ido.ble.data.manage.database.HealthPressure?,
            items: MutableList<com.ido.ble.data.manage.database.HealthPressureItem>?,
            isLast: Boolean,
        ) {
            if (data == null || data.year == 0 || items.isNullOrEmpty()) return
            var mapped = 0
            items.forEach { item ->
                if (item.value <= 0) return@forEach
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, (data.startTime + item.offset) * 60)
                }.onSuccess { ts ->
                    listener?.onStressReading(WatchStressReading(recordedAt = ts, stressScore = item.value))
                    mapped++
                }
            }
            diagnostics.record(WatchSyncDiagnostics.STRESS, parentRecords = 1, itemSamples = items.size, mappedReadings = mapped)
        }

        // V3 daily activity rollup → WatchActivityDay. This V3-only watch never fires the v2
        // onGetActivityData, so HealthSportV3's day totals are THE source of daily steps/distance/
        // calories. We map the day totals (the per-minute `items` buckets are ignored) and reuse the
        // existing activityDays upload path. Skip empty/sentinel days.
        override fun onGetHealthSportV3Data(data: HealthSportV3?) {
            if (data == null || data.year == 0) return
            if (data.total_step <= 0L && data.total_distances <= 0 && data.total_activity_calories <= 0) return
            diagnostics.record(WatchSyncDiagnostics.ACTIVITY_DAY_V3, parentRecords = 1, mappedReadings = 1)
            listener?.onActivityDay(data.toActivityDay())
        }

        // V3 "heart rate second" record carries the Active 4 Pro's intraday HR (the v2 daily-HR path
        // never fires here). silentHR reads 0; the real data is the items[] series of bare HR values
        // with no per-sample time. Before mapping it (would need a new point metric + dashboard
        // column), confirm the sample interval. Probe logs date/startTime/count + first/last values
        // and any hr_data high/low entries (those DO carry hour:minute) so the spacing can be pinned
        // from real evidence. Debug-gated (the values are decoded health data). Mapping deferred.
        override fun onGetHealthHeartRateSecondData(
            data: com.ido.ble.data.manage.database.HealthHeartRateSecond?,
            isLast: Boolean,
        ) {
            if (data == null || data.year == 0) return
            val itemCount = data.items?.size ?: 0
            diagnostics.record(WatchSyncDiagnostics.HEART_RATE_SECOND, parentRecords = 1, itemSamples = itemCount)
            if (BuildConfig.DEBUG) {
                val vals = data.items?.map { it.heartRateVal } ?: emptyList()
                val nonZero = vals.count { it > 0 }
                val firstNonZero = vals.indexOfFirst { it > 0 }
                val lastNonZero = vals.indexOfLast { it > 0 }
                val hrTimes = data.hr_data?.take(4)?.map { "${it.hour}:${it.minute}=${it.heart_rate}(t${it.type})" }
                Log.d(
                    TAG,
                    "heartRateSecond probe: date=${WatchTime.ymd(data.year, data.month, data.day)} " +
                        "startTime=${data.startTime} items=$itemCount nonZero=$nonZero " +
                        "firstNZidx=$firstNonZero lastNZidx=$lastNonZero " +
                        "fiveMin=${data.five_min_data?.size ?: 0} hrDataCount=${data.hr_data_count} " +
                        "silentHR=${data.silentHR} hrData=$hrTimes",
                )
            }
        }

        // ── Delivered but dropped — no domain model / dashboard column yet, or no clean mapping:
        //   GPS, drink plan, body composition (needs a paired bio-impedance scale + int→real value
        //   scale to confirm — won't fire on a wrist watch), noise, swimming, ECG, and emotion-health
        //   (a categorical mood code, not a 0–100 score). Each records a diagnostics tally so a real
        //   sync reveals whether the Active 4 Pro emits it (Phase 2). ──
        override fun onGetDrinkPlan(data: com.ido.ble.protocol.model.DrinkPlanData?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.DRINK_PLAN, parentRecords = 1)
        }
        override fun onGetGpsData(data: com.ido.ble.gps.database.HealthGps?, items: MutableList<com.ido.ble.gps.database.HealthGpsItem>?, isLast: Boolean) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.GPS_V2, parentRecords = 1, itemSamples = items?.size ?: 0)
        }
        override fun onGetHealthBodyCompositionData(data: com.ido.ble.data.manage.database.HealthBodyComposition?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.BODY_COMPOSITION, parentRecords = 1)
        }
        override fun onGetHealthGpsV3Data(data: com.ido.ble.data.manage.database.HealthGpsV3?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.GPS_V3, parentRecords = 1)
        }
        override fun onGetHealthNoiseData(data: com.ido.ble.data.manage.database.HealthNoise?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.NOISE, parentRecords = 1)
        }
        override fun onGetHealthSwimmingData(data: com.ido.ble.data.manage.database.HealthSwimming?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.SWIMMING, parentRecords = 1)
        }
        override fun onGetHealthV3EcgData(data: com.ido.ble.data.manage.database.HealthV3Ecg?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.ECG, parentRecords = 1)
        }
        override fun onGetHealthV3EmotionHealthData(data: com.ido.ble.data.manage.database.HealthV3EmotionHealth?) {
            if (data != null) diagnostics.record(WatchSyncDiagnostics.EMOTION, parentRecords = 1)
        }
    }

    // ── Phase-2 instrumentation: diagnostics summary + metric confidence ──────────────

    /**
     * Read a boolean `SupportFunctionInfo` capability flag by field name, reflectively (the struct
     * has 600+ fields; reflection keeps the metric→flag map data-driven in [WatchMetric]). Returns
     * null when the table hasn't arrived, the metric has no flag, or the field name doesn't resolve.
     */
    private fun readFunctionFlag(field: String?): Boolean? {
        if (field.isNullOrEmpty()) return null
        val info = cachedFunctionInfo ?: return null
        return runCatching { info.javaClass.getField(field).getBoolean(info) }.getOrNull()
    }

    /** Log which health-relevant capability flags the connected watch advertises (counts-only). */
    private fun logFunctionTable() {
        val flags = WatchMetric.entries
            .mapNotNull { m -> m.functionTableField?.let { "${m.diagnosticsKey}=${readFunctionFlag(it)}" } }
            .joinToString(", ")
        Log.i(TAG, "function table (health flags): $flags")
    }

    /**
     * Emit the counts-only sync tally, the delivered-but-dropped list, and a per-metric confidence
     * line — the raw evidence for the Phase-2 metric support matrix. Privacy-safe (no values), so it
     * stays on in release. "Emitted" uses mapped readings for the mapped metrics and parent records
     * for the dropped sinks, so a watch that *delivers* a dropped metric still shows it as emitted.
     */
    private fun logSyncDiagnostics() {
        Log.i(TAG, "sync diagnostics: ${diagnostics.summary()}")
        val dropped = diagnostics.droppedMetrics()
        if (dropped.isNotEmpty()) Log.i(TAG, "sync delivered-but-dropped: ${dropped.joinToString()}")
        val snap = diagnostics.snapshot()
        val matrix = WatchMetric.entries.joinToString("; ") { m ->
            val tally = snap[m.diagnosticsKey]
            val emitted = tally != null && (tally.mappedReadings > 0 || (!m.uploaded && tally.parentRecords > 0))
            "${m.diagnosticsKey}=${m.confidence(readFunctionFlag(m.functionTableField), emitted)}"
        }
        Log.i(TAG, "metric confidence: $matrix")
    }

    // ── Mappers (SDK type → domain) ─────────────────────────────────────────────────

    private fun HealthActivity.toDomain() = WatchActivityDay(
        date = WatchTime.ymd(year, month, day),
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
        date = WatchTime.ymd(year, month, day),
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
        date = WatchTime.ymd(year, month, day),
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
        date = WatchTime.ymd(get_up_year, get_up_month, get_up_day),
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
        // V3-only richer fields. Stage minutes/counts are passed raw (0 = genuinely no REM that
        // night, matching deep/light/awake); the averages use nonZero() because 0 there means "not
        // measured", not a real reading.
        remMinutes = rem_mins,
        remCount = rem_count,
        avgHeartRate = sleep_avg_hr_value.nonZero(),
        avgSpo2 = sleep_avg_spo2_value.nonZero(),
        avgRespiratoryRate = sleep_avg_respir_rate_value.nonZero(),
    )

    private fun HealthSportV3.toActivityDay() = WatchActivityDay(
        date = WatchTime.ymd(year, month, day),
        steps = total_step.toInt().nonZero(),
        distanceMeters = total_distances.nonZero(),
        // Active calories for the day. `total_rest_activity_calories` (resting burn) is reported
        // separately; the dashboard's single `calories` column tracks the active total. Exact
        // semantics + the `total_active_time` unit are on-device-verifiable (W6 timestamp caveat).
        calories = total_activity_calories.nonZero(),
        durationSeconds = total_active_time.nonZero(),
        // HealthSportV3 carries no HR summary or zone minutes (those live on v2 HealthActivity /
        // HealthHeartRate, which this device doesn't emit) — leave null.
        avgHeartRate = null,
        maxHeartRate = null,
        minHeartRate = null,
        warmUpMins = null,
        burnFatMins = null,
        aerobicMins = null,
        anaerobicMins = null,
        limitMins = null,
    )

    private fun HealthActivityV3.toWorkout() = WatchWorkout(
        startDateTime = WatchTime.ymdhms(year, month, day, hour, minute, second),
        endDateTime = WatchTime.ymdhms(end_year, end_month, end_day, end_hour, end_minute, end_sec),
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

    /** Logs decoded metric bodies only in debug builds; lifecycle messages stay visible. */
    private object LoggingWatchHealthListener : WatchHealthListener {
        override fun onActivityDay(day: WatchActivityDay) { logPrivateRecord("ACTIVITY", day) }
        override fun onHeartRateDay(day: WatchHeartRateDay) { logPrivateRecord("HEART_RATE", day) }
        override fun onSleepSession(session: WatchSleepSession) { logPrivateRecord("SLEEP", session) }
        override fun onWorkout(workout: WatchWorkout) { logPrivateRecord("WORKOUT", workout) }
        override fun onSpo2Reading(reading: WatchSpo2Reading) { logPrivateRecord("SPO2", reading) }
        override fun onHrvReading(reading: WatchHrvReading) { logPrivateRecord("HRV", reading) }
        override fun onRespiratoryReading(reading: WatchRespiratoryReading) { logPrivateRecord("RESP", reading) }
        override fun onTemperatureReading(reading: WatchTemperatureReading) { logPrivateRecord("TEMP", reading) }
        override fun onBodyEnergyReading(reading: WatchBodyEnergyReading) { logPrivateRecord("BODY_ENERGY", reading) }
        override fun onBloodPressureReading(reading: WatchBloodPressureReading) { logPrivateRecord("BLOOD_PRESSURE", reading) }
        override fun onStressReading(reading: WatchStressReading) { logPrivateRecord("STRESS", reading) }
        override fun onSyncProgress(percent: Int) { Log.i(TAG, "sync progress $percent%") }
        override fun onSyncComplete() { Log.i(TAG, "sync complete") }
        override fun onSyncFailed() { Log.w(TAG, "sync failed") }

        private fun logPrivateRecord(kind: String, record: Any) {
            if (BuildConfig.DEBUG) Log.i(TAG, "$kind $record")
        }
    }

    private companion object {
        const val TAG = "IdoSdkWatchEngine"

        /** Category → NewMessageInfo type (modern path). No incoming-call type exists here, so a live
         *  CALL falls back to GENERIC and relies on the body text ("Incoming call · …"). */
        fun newTypeFor(category: WatchNotificationCategory): Int = when (category) {
            WatchNotificationCategory.SMS -> com.ido.ble.protocol.model.NewMessageInfo.TYPE_SMS
            WatchNotificationCategory.EMAIL -> com.ido.ble.protocol.model.NewMessageInfo.TYPE_EMAIL
            WatchNotificationCategory.MISSED_CALL -> com.ido.ble.protocol.model.NewMessageInfo.TYPE_MISSED_CALL
            WatchNotificationCategory.CALL, WatchNotificationCategory.GENERIC ->
                com.ido.ble.protocol.model.NewMessageInfo.TYPE_GENERAL
        }

        /** Category → V3MessageNotice type (legacy V3 path) — this enumeration *does* have a live
         *  TYPE_CALL, so an incoming call renders as a call there. */
        @Suppress("DEPRECATION")
        fun v3TypeFor(category: WatchNotificationCategory): Int = when (category) {
            WatchNotificationCategory.SMS -> com.ido.ble.protocol.model.V3MessageNotice.TYPE_SMS
            WatchNotificationCategory.EMAIL -> com.ido.ble.protocol.model.V3MessageNotice.TYPE_EMAIL
            WatchNotificationCategory.CALL -> com.ido.ble.protocol.model.V3MessageNotice.TYPE_CALL
            WatchNotificationCategory.MISSED_CALL -> com.ido.ble.protocol.model.V3MessageNotice.TYPE_MISSED_CALL
            WatchNotificationCategory.GENERIC -> com.ido.ble.protocol.model.V3MessageNotice.TYPE_GENERAL
        }

        // Date/time + nonZero helpers moved to WatchTime.kt (JVM-unit-tested). `nonZero` is a
        // top-level extension in that file, so its bare call sites here resolve unchanged.
    }
}
