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
import com.ido.ble.callback.OperateCallBack
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
import com.ido.ble.file.transfer.FileTransferConfig
import com.ido.ble.file.transfer.IFileTransferListener
import com.ido.ble.protocol.model.MusicOperate
import com.veryfit.multi.nativeprotocol.Protocol
import dev.jaredhq.dashboardandroid.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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

    private val _musicControlEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<WatchMusicControlEvent>(extraBufferCapacity = 8)
    override val musicControlEvents: kotlinx.coroutines.flow.SharedFlow<WatchMusicControlEvent> =
        _musicControlEvents.asSharedFlow()

    private val _musicCapabilities = MutableStateFlow(WatchMusicCapabilities())
    override val musicCapabilities: StateFlow<WatchMusicCapabilities> =
        _musicCapabilities.asStateFlow()

    private val _watchMusicLibrary = MutableStateFlow(WatchMusicLibraryState())
    override val watchMusicLibrary: StateFlow<WatchMusicLibraryState> =
        _watchMusicLibrary.asStateFlow()

    private val _watchMusicTransfer = MutableStateFlow(WatchMusicTransferState())
    override val watchMusicTransfer: StateFlow<WatchMusicTransferState> =
        _watchMusicTransfer.asStateFlow()

    private val _watchMusicLibraryMutation = MutableStateFlow(WatchMusicLibraryMutationState())
    override val watchMusicLibraryMutation: StateFlow<WatchMusicLibraryMutationState> =
        _watchMusicLibraryMutation.asStateFlow()

    private enum class FolderUpdateStage { RENAME, REMOVE, ADD }

    private sealed interface MusicOperation {
        data object Idle : MusicOperation
        data object Query : MusicOperation
        data class Import(
            val song: WatchSongImport,
            val musicId: Int? = null,
            val cancelRequested: Boolean = false,
        ) : MusicOperation
        data class Cleanup(
            val song: WatchSongImport,
            val musicId: Int,
            val terminalStatus: WatchMusicTransferStatus,
            val error: String?,
        ) : MusicOperation
        data class Delete(val song: WatchMusicLibraryItem) : MusicOperation
        data class FolderCreate(val folder: WatchMusicFolder) : MusicOperation
        data class FolderUpdate(
            val original: WatchMusicFolder,
            val desiredName: String,
            val desiredMusicIds: List<Int>,
            val additions: List<Int>,
            val retainedAfterRemoval: List<Int>,
            val stage: FolderUpdateStage,
        ) : MusicOperation
        data class FolderDelete(val folder: WatchMusicFolder) : MusicOperation
    }

    @Volatile
    private var musicOperation: MusicOperation = MusicOperation.Idle

    private val musicTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val musicTimeoutRunnable = Runnable { onMusicOperationTimeout() }

    @Volatile
    private var phoneMusicEnabled = false

    @Volatile
    private var appliedPhoneMusicEnabled: Boolean? = null

    @Volatile
    private var latestNowPlaying: WatchNowPlaying? = null

    @Volatile
    private var initialized = false

    /** Raw per-sync delivery tally (incl. callbacks we drop) for the Phase-2 support matrix. */
    private val diagnostics = WatchSyncDiagnostics()

    // W7: the watch gates *display* of any pushed notice on a per-type "message notify state". Until
    // that state is set to ALLOW for a type, the watch silently drops our V3MessageNotice/NewMessageInfo
    // even though the SDK accepts the send. The stock VeryFit app pushes default ALLOW states on connect
    // (RemindDataManager.sendDefaultNotificationState2Device → BLEManager.addMessageNotifyState(list,0,0)).
    // We never sent config, so notices never displayed. Push ALLOW once per connected session. One-shot.
    @Volatile
    private var notifyStatesEnabled = false

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

    // First-connect to this IDO/SiFli family commonly fails once with GATT status 133 — a transient
    // Android stack hiccup, not a real "watch unreachable". Retry the scan→connect a bounded number
    // of times before giving up. Reset on a user-driven connect and on a successful link.
    private var connectAttempts = 0
    private val connectRetryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectRetryRunnable = Runnable {
        if (targetMac == null) return@Runnable
        beginScan()
    }

    // Liveness-reset connect watchdog. The SDK can leave us stuck in the SCANNING/CONNECTING zone
    // with no terminal callback: observed once after a cold boot the link sat ~2 min at onConnecting
    // (Classic-BT/HFP up, BLE/GATT connect never completing) and recovered only on a manual relaunch.
    // The always-on WatchConnectionService.maintain() only retries on DISCONNECTED, so a state stuck
    // at CONNECTING leaves the link silently down until the app is reopened.
    //
    // Crucially (hardware-verified): after a drop the SDK runs its OWN internal auto-reconnect —
    // cycling onConnectStart/onScanFinished every ≤~35s and recovering itself when the watch returns —
    // WITHOUT ever calling our connect()/beginScan(). A watchdog armed only at our beginScan would
    // miss that path entirely (a 12-min watch-off window sat at CONNECTING the whole time and never
    // armed it). So instead of a fixed timeout we (re)arm on EVERY connect-progress callback from
    // either path (see [noteConnectProgress]); the watchdog fires only when progress goes *silent*
    // for CONNECT_STALL_MS while still SCANNING/CONNECTING — the genuine wedge. Healthy retry cycling
    // keeps resetting it, so it never aborts a connect that's still making progress. On a true wedge
    // it tears down the half-open attempt and routes through the SAME retry budget as a GATT-133
    // failure; once the link is up (onConnectSuccess) it's cleared (bind() afterwards is user-gated).
    private val connectWatchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectWatchdogRunnable = Runnable {
        val state = _connectionState.value
        if (targetMac == null ||
            (state != WatchEngineConnectionState.SCANNING && state != WatchEngineConnectionState.CONNECTING)
        ) {
            return@Runnable // attempt already settled (connected/bound/disconnected) — nothing to do
        }
        Log.w(TAG, "connect watchdog: no progress for ${CONNECT_STALL_MS}ms at $state — aborting + retrying")
        runCatching { BLEManager.stopScanDevices() }
        runCatching { BLEManager.disConnect() }
        handleConnectAttemptFailed("connect stall watchdog (silent ${CONNECT_STALL_MS}ms at $state)")
    }

    /**
     * Mark connect progress and (re)arm the stall watchdog. Called from every connect-progress
     * callback regardless of which path (our connect()/beginScan or the SDK's auto-reconnect) drives
     * it; each call pushes the wedge deadline CONNECT_STALL_MS further out, so the watchdog fires
     * only on true silence.
     */
    private fun noteConnectProgress() {
        connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
        connectWatchdogHandler.postDelayed(connectWatchdogRunnable, CONNECT_STALL_MS)
    }

    // The user explicitly rejected/cancelled the bind on the watch face. The always-on service
    // reconnects on a ~60s loop, and every reconnect of an unbound watch re-runs bind() — which
    // re-pops the watch's pairing prompt. Honour the rejection with a cooldown so the prompt isn't
    // spammed; an explicit user connect can be re-tried after it lapses (or app restart).
    @Volatile
    private var bindRejectedAtMs = 0L

    override fun connect(macAddress: String) {
        val sinceReject = System.currentTimeMillis() - bindRejectedAtMs
        if (bindRejectedAtMs != 0L && sinceReject < BIND_REJECT_COOLDOWN_MS) {
            Log.w(
                TAG,
                "connect ignored — bind was rejected on the watch ${sinceReject / 1000}s ago " +
                    "(cooldown ${BIND_REJECT_COOLDOWN_MS / 1000}s, so the watch prompt isn't re-spammed)",
            )
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
            return
        }
        // autoConnect only works for an already-bound device; for a watch bound to VeryFit we
        // must scan → connect(BLEDevice) → bind() to claim it. Start a scan and match our MAC.
        targetMac = macAddress.uppercase()
        connectAttempts = 0
        connectRetryHandler.removeCallbacks(connectRetryRunnable)
        beginScan()
    }

    /** Start (or restart, for a GATT-133 retry) the scan→connect flow toward [targetMac]. */
    private fun beginScan() {
        functionTableCached = false
        noteConnectProgress() // arm/refresh the stall watchdog for this attempt
        Log.i(TAG, "connect($targetMac) → scanning to find + bind")
        _connectionState.value = WatchEngineConnectionState.SCANNING
        BLEManager.startScanDevices()
    }

    /**
     * A connect attempt failed to reach a live link — either the SDK reported [onConnectFailed]
     * (typically a transient GATT-133 first-connect hiccup) or our connect watchdog tripped on a
     * wedged/never-found attempt. Both share one bounded retry budget: re-scan up to
     * [MAX_CONNECT_RETRIES] times with a short backoff, then surface a clean DISCONNECTED so callers
     * (UI / worker / always-on service) see a terminal state and can decide whether to retry. Always
     * clears the watchdog first so a queued timeout can't fire after we've already handled the failure.
     */
    private fun handleConnectAttemptFailed(context: String) {
        connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
        if (targetMac != null && connectAttempts < MAX_CONNECT_RETRIES) {
            connectAttempts++
            Log.w(
                TAG,
                "$context — retry $connectAttempts/$MAX_CONNECT_RETRIES in ${CONNECT_RETRY_DELAY_MS}ms",
            )
            connectRetryHandler.removeCallbacks(connectRetryRunnable)
            connectRetryHandler.postDelayed(connectRetryRunnable, CONNECT_RETRY_DELAY_MS)
            return
        }
        Log.w(TAG, "$context — giving up after ${connectAttempts + 1} attempts")
        connectAttempts = 0
        _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        listener?.onSyncFailed()
    }

    override fun disconnect() {
        abortMusicOperationOnDisconnect()
        functionTableCached = false
        cachedFunctionInfo = null
        pendingSyncAfterFunctionTable = false
        connectAttempts = 0
        connectRetryHandler.removeCallbacks(connectRetryRunnable)
        connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
        _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        BLEManager.disConnect()
    }

    override fun isConnected(): Boolean = BLEManager.isConnected()

    override fun setPhoneMusicEnabled(enabled: Boolean): Boolean {
        phoneMusicEnabled = enabled
        return applyPhoneMusicEnabled()
    }

    override fun pushNowPlaying(nowPlaying: WatchNowPlaying): Boolean {
        latestNowPlaying = nowPlaying
        if (!phoneMusicEnabled || !isConnected() ||
            _connectionState.value != WatchEngineConnectionState.CONNECTED ||
            cachedFunctionInfo?.bleControlMusic != true || musicOperation !is MusicOperation.Idle
        ) {
            return false
        }
        return sendNowPlaying(nowPlaying)
    }

    private fun applyPhoneMusicEnabled(): Boolean {
        if (!isConnected() ||
            _connectionState.value != WatchEngineConnectionState.CONNECTED ||
            musicOperation !is MusicOperation.Idle
        ) {
            appliedPhoneMusicEnabled = null
            return false
        }
        val capabilities = _musicCapabilities.value
        if (phoneMusicEnabled && (!capabilities.known || !capabilities.phoneMusicControl)) {
            return false
        }
        if (appliedPhoneMusicEnabled == phoneMusicEnabled) {
            if (phoneMusicEnabled) latestNowPlaying?.let(::sendNowPlaying)
            return true
        }
        return runCatching {
            BLEManager.setMusicSwitchPending(phoneMusicEnabled)
            BLEManager.setMusicSwitch(phoneMusicEnabled)
            if (phoneMusicEnabled) {
                BLEManager.enterMusicMode()
                latestNowPlaying?.let(::sendNowPlaying)
            } else {
                BLEManager.setMusicControlInfo(
                    com.ido.ble.protocol.model.MusicControlInfo().apply {
                        status = com.ido.ble.protocol.model.MusicControlInfo.STATUS_STOP
                        curTimeSecond = 0
                        totalTimeSecond = 0
                        musicName = ""
                        singerName = ""
                    },
                )
                BLEManager.exitMusicMode()
            }
            appliedPhoneMusicEnabled = phoneMusicEnabled
            Log.i(TAG, "phone music mode enabled=$phoneMusicEnabled")
            true
        }.getOrElse {
            Log.w(TAG, "phone music mode update failed enabled=$phoneMusicEnabled", it)
            false
        }
    }

    private fun sendNowPlaying(nowPlaying: WatchNowPlaying): Boolean = runCatching {
        val info = com.ido.ble.protocol.model.MusicControlInfo().apply {
            status = when (nowPlaying.state) {
                WatchPlaybackState.PLAYING ->
                    com.ido.ble.protocol.model.MusicControlInfo.STATUS_PLAY
                WatchPlaybackState.PAUSED ->
                    com.ido.ble.protocol.model.MusicControlInfo.STATUS_PAUSE
                WatchPlaybackState.STOPPED ->
                    com.ido.ble.protocol.model.MusicControlInfo.STATUS_STOP
            }
            curTimeSecond = nowPlaying.positionSeconds.coerceAtLeast(0)
            totalTimeSecond = nowPlaying.durationSeconds.coerceAtLeast(0)
            musicName = nowPlaying.title
            singerName = if (_musicCapabilities.value.artistName) nowPlaying.artist else ""
        }
        BLEManager.setMusicControlInfo(info)
        Log.i(
            TAG,
            "now-playing pushed state=${nowPlaying.state} " +
                "titleLen=${nowPlaying.title.length} artistLen=${nowPlaying.artist.length}",
        )
        true
    }.getOrElse {
        Log.w(TAG, "now-playing push failed", it)
        false
    }

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
        if (musicOperation !is MusicOperation.Idle) {
            Log.i(TAG, "syncHealth() ignored — onboard music operation active")
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

    override fun refreshWatchMusicLibrary(): Boolean {
        if (!canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle) ||
            !_musicCapabilities.value.onboardMusic
        ) {
            return false
        }
        musicOperation = MusicOperation.Query
        _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
            status = WatchMusicLibraryStatus.LOADING,
            error = null,
        )
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            Log.i(MUSIC_TAG, "library query started")
            BLEManager.queryMusicAndFolderInfo()
            true
        }.getOrElse {
            clearMusicOperationTimeout()
            musicOperation = MusicOperation.Idle
            _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                status = WatchMusicLibraryStatus.ERROR,
                error = "Couldn't query the watch library.",
            )
            Log.w(MUSIC_TAG, "library query dispatch failed", it)
            false
        }
    }

    override fun importWatchSong(song: WatchSongImport): Boolean {
        if (!canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle) ||
            !_musicCapabilities.value.onboardMusic
        ) {
            return false
        }
        val file = File(song.privateCachePath)
        val storage = _watchMusicLibrary.value.storage
        if (!file.isFile || file.length() <= 0 || file.length() != song.sizeBytes ||
            storage == null || !hasWatchMusicCapacity(storage.freeBytes, song.sizeBytes)
        ) {
            file.delete()
            _watchMusicTransfer.value = WatchMusicTransferState(
                status = WatchMusicTransferStatus.FAILED,
                bytesTotal = song.sizeBytes.coerceAtLeast(0),
                error = if (storage != null && !hasWatchMusicCapacity(storage.freeBytes, song.sizeBytes)) {
                    "Not enough free space on the watch."
                } else {
                    "The selected MP3 could not be validated."
                },
            )
            return false
        }
        val validated = song.copy(firmwareFileName = sanitizeWatchMusicFilename(song.firmwareFileName))
        musicOperation = MusicOperation.Import(validated)
        _watchMusicTransfer.value = WatchMusicTransferState(
            status = WatchMusicTransferStatus.RESERVING,
            bytesTotal = validated.sizeBytes,
        )
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            val sdkSong = MusicOperate.MusicFile().apply {
                music_name = validated.firmwareFileName
                singer_name = validated.artist
                music_memory = validated.sizeBytes
            }
            Log.i(MUSIC_TAG, "reservation started bytes=${validated.sizeBytes}")
            BLEManager.addMusicFile(sdkSong)
            true
        }.getOrElse {
            Log.w(MUSIC_TAG, "reservation dispatch failed", it)
            finishWatchSongImport(WatchMusicTransferStatus.FAILED, "Couldn't reserve space on the watch.")
            false
        }
    }

    override fun cancelWatchSongImport() {
        when (val operation = musicOperation) {
            is MusicOperation.Import -> {
                if (operation.musicId == null) {
                    musicOperation = operation.copy(cancelRequested = true)
                    _watchMusicTransfer.value = _watchMusicTransfer.value.copy(
                        status = WatchMusicTransferStatus.CLEANING_UP,
                        error = null,
                    )
                } else {
                    beginReservedSongCleanup(
                        operation.song,
                        operation.musicId,
                        WatchMusicTransferStatus.CANCELLED,
                        null,
                        stopTransfer = true,
                    )
                }
            }
            else -> Unit
        }
    }

    override fun deleteWatchSong(musicId: Int): Boolean {
        val librarySong = _watchMusicLibrary.value.songs.firstOrNull { it.musicId == musicId }
            ?: return false
        if (musicId < 0 ||
            !_musicCapabilities.value.onboardMusic ||
            !canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle)
        ) {
            return false
        }
        musicOperation = MusicOperation.Delete(librarySong)
        _watchMusicTransfer.value = WatchMusicTransferState()
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            Log.i(MUSIC_TAG, "delete started id=$musicId")
            BLEManager.deleteMusicFile(librarySong.toSdkMusicFile())
            true
        }.getOrElse {
            Log.w(MUSIC_TAG, "delete dispatch failed id=$musicId", it)
            clearMusicOperationTimeout()
            musicOperation = MusicOperation.Idle
            _watchMusicLibrary.value = _watchMusicLibrary.value.copy(error = "Couldn't delete the song.")
            false
        }
    }

    override fun createWatchMusicFolder(name: String): Boolean {
        val library = _watchMusicLibrary.value
        if (!canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle) ||
            !_musicCapabilities.value.onboardMusic || library.status != WatchMusicLibraryStatus.READY
        ) {
            return false
        }
        val folderId = nextWatchMusicFolderId(library.folders) ?: run {
            _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
                WatchMusicLibraryMutationStatus.FAILED,
                "The watch supports at most $WATCH_MUSIC_FOLDER_LIMIT playlists.",
            )
            return false
        }
        val safeName = uniqueWatchMusicFolderName(name, library.folders.map { it.name })
        if (safeName.isBlank()) {
            _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
                WatchMusicLibraryMutationStatus.FAILED,
                "Enter a playlist name.",
            )
            return false
        }
        val folder = WatchMusicFolder(folderId, safeName, emptyList())
        musicOperation = MusicOperation.FolderCreate(folder)
        _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
            WatchMusicLibraryMutationStatus.CREATING,
        )
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            Log.i(MUSIC_TAG, "playlist create started id=$folderId")
            BLEManager.addMusicFolder(folder.toSdkMusicFolder())
            true
        }.getOrElse {
            Log.w(MUSIC_TAG, "playlist create dispatch failed id=$folderId", it)
            finishMusicLibraryMutation(false, "Couldn't create the playlist.")
            false
        }
    }

    override fun updateWatchMusicFolder(folderId: Int, name: String, musicIds: List<Int>): Boolean {
        val library = _watchMusicLibrary.value
        val folder = library.folders.firstOrNull { it.folderId == folderId } ?: return false
        if (!canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle) ||
            library.status != WatchMusicLibraryStatus.READY
        ) {
            return false
        }
        val validSongIds = library.songs.mapTo(mutableSetOf()) { it.musicId }
        val plan = planWatchMusicFolderUpdate(
            folder = folder,
            candidateName = name,
            desiredMusicIds = musicIds,
            validSongIds = validSongIds,
            otherFolderNames = library.folders.filterNot { it.folderId == folderId }.map { it.name },
        )
        if (plan == null) {
            _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
                WatchMusicLibraryMutationStatus.FAILED,
                "Enter a valid playlist name and song selection.",
            )
            return false
        }
        val firstStage = when {
            plan.renameRequired -> FolderUpdateStage.RENAME
            plan.removalRequired -> FolderUpdateStage.REMOVE
            plan.additions.isNotEmpty() -> FolderUpdateStage.ADD
            else -> return true
        }
        val operation = MusicOperation.FolderUpdate(
            original = folder,
            desiredName = plan.name,
            desiredMusicIds = plan.desiredMusicIds,
            additions = plan.additions,
            retainedAfterRemoval = plan.retainedAfterRemoval,
            stage = firstStage,
        )
        musicOperation = operation
        _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
            WatchMusicLibraryMutationStatus.UPDATING,
        )
        return dispatchFolderUpdate(operation)
    }

    override fun deleteWatchMusicFolder(folderId: Int): Boolean {
        val folder = _watchMusicLibrary.value.folders.firstOrNull { it.folderId == folderId }
            ?: return false
        if (!canStartWatchMusicOperation(_connectionState.value, musicOperation !is MusicOperation.Idle)) {
            return false
        }
        musicOperation = MusicOperation.FolderDelete(folder)
        _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
            WatchMusicLibraryMutationStatus.DELETING,
        )
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            Log.i(MUSIC_TAG, "playlist delete started id=$folderId")
            BLEManager.deleteMusicFolder(folder.toSdkMusicFolder())
            true
        }.getOrElse {
            Log.w(MUSIC_TAG, "playlist delete dispatch failed id=$folderId", it)
            finishMusicLibraryMutation(false, "Couldn't delete the playlist.")
            false
        }
    }

    private fun dispatchFolderUpdate(operation: MusicOperation.FolderUpdate): Boolean {
        clearMusicOperationTimeout()
        musicOperation = operation
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        return runCatching {
            when (operation.stage) {
                FolderUpdateStage.RENAME -> {
                    Log.i(MUSIC_TAG, "playlist rename started id=${operation.original.folderId}")
                    BLEManager.updateMusicFolder(
                        operation.original.copy(name = operation.desiredName).toSdkMusicFolder(),
                    )
                }
                FolderUpdateStage.REMOVE -> {
                    Log.i(MUSIC_TAG, "playlist membership removal started id=${operation.original.folderId}")
                    BLEManager.removeMusicFromFolder(
                        operation.original.copy(
                            name = operation.desiredName,
                            musicIds = operation.retainedAfterRemoval,
                        ).toSdkMusicFolder(),
                    )
                }
                FolderUpdateStage.ADD -> {
                    Log.i(MUSIC_TAG, "playlist membership addition started id=${operation.original.folderId}")
                    BLEManager.moveMusicIntoFolder(
                        operation.original.copy(
                            name = operation.desiredName,
                            musicIds = operation.additions,
                        ).toSdkMusicFolder(),
                    )
                }
            }
            true
        }.getOrElse {
            Log.w(MUSIC_TAG, "playlist update dispatch failed id=${operation.original.folderId}", it)
            finishMusicLibraryMutation(false, "Couldn't update the playlist.")
            false
        }
    }

    private fun continueFolderUpdate(completedStage: FolderUpdateStage, success: Boolean) {
        val operation = musicOperation as? MusicOperation.FolderUpdate ?: return
        if (operation.stage != completedStage) return
        if (!success) {
            finishMusicLibraryMutation(false, "The watch rejected the playlist update.")
            return
        }
        val nextStage = when (completedStage) {
            FolderUpdateStage.RENAME -> when {
                operation.retainedAfterRemoval.size != operation.original.musicIds.size ->
                    FolderUpdateStage.REMOVE
                operation.additions.isNotEmpty() -> FolderUpdateStage.ADD
                else -> null
            }
            FolderUpdateStage.REMOVE ->
                if (operation.additions.isNotEmpty()) FolderUpdateStage.ADD else null
            FolderUpdateStage.ADD -> null
        }
        if (nextStage == null) finishMusicLibraryMutation(true, null)
        else dispatchFolderUpdate(operation.copy(stage = nextStage))
    }

    private fun finishMusicLibraryMutation(success: Boolean, error: String?) {
        clearMusicOperationTimeout()
        musicOperation = MusicOperation.Idle
        _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
            if (success) WatchMusicLibraryMutationStatus.SUCCEEDED
            else WatchMusicLibraryMutationStatus.FAILED,
            error,
        )
        refreshLibraryAfterMutation()
        schedulePhoneMusicApply()
    }

    override fun release() {
        abortMusicOperationOnDisconnect()
        connectRetryHandler.removeCallbacks(connectRetryRunnable)
        connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
        val cm = CallBackManager.getManager()
        cm.unregisterScanCallBack(scanCallBack)
        cm.unregisterConnectCallBack(connectCallBack)
        cm.unregisterBindCallBack(bindCallBack)
        cm.unregisterGetDeviceInfoCallBack(deviceInfoCallBack)
        cm.unregisterDeviceControlAppCallBack(deviceControlCallBack)
        cm.unregisterOperateCallBack(operateCallBack)
        cm.unregisterOperateMusicCallBack(operateMusicCallBack)
        initialized = false
    }

    override fun sendNotification(notification: WatchNotification): Boolean {
        if (!isConnected()) {
            Log.w(TAG, "sendNotification ignored — not connected")
            return false
        }
        return try {
            // An incoming call uses the watch's dedicated call API (renders the native answer/reject
            // screen), NOT the message path — this is how VeryFit does it
            // (MsgNotificationHelper.sendCallReminder2DeviceNew → setIncomingCallInfo). Pair with
            // [stopIncomingCall] when the call ends.
            // NOTE (hardware-verified, Active 4 Pro): this watch is *also* bonded as a Bluetooth
            // Hands-Free (HFP) device, so the wrist answer/reject is actually executed by Android's
            // telephony stack over HFP — it never reaches [deviceControlCallBack] / the app's
            // [WatchControlEvent] path (that path stays as a fallback for watches that deliver call
            // control over the IDO GATT channel instead of HFP).
            if (notification.category == WatchNotificationCategory.CALL) {
                val callInfo = com.ido.ble.protocol.model.IncomingCallInfo().apply {
                    name = notification.appName.ifBlank { " " }
                    phoneNumber = ""
                }
                Log.i(TAG, "sendNotification (incoming call) len=${notification.body.length}")
                BLEManager.setIncomingCallInfo(callInfo)
                return true
            }
            // Mirror VeryFit's capability gate (MsgNotificationHelper.formNotificationAndSend2Device):
            //  1. Watches flagged V3_support_set_v3_notify_add_app_name take the NEW NotificationPara
            //     path (`isSupportV3Notify()` → sendNotification2DeviceV3). The Active 4 Pro is one of
            //     these — its firmware *ignores* the legacy setV3MessageNotice, which is why mirroring
            //     silently failed on hardware. The notice carries the app name in `items` itself, so we
            //     don't need the full notice-app/icon DB push for the standard SMS/message types.
            //  2. Otherwise: ex_table_main10_v3_notify_msg → legacy V3MessageNotice; else NewMessageInfo.
            // The function table is fetched once per connected session (see [syncHealth]); if it hasn't
            // arrived yet we fall back to the newer message path (the common case).
            val useNotificationPara = cachedFunctionInfo?.V3_support_set_v3_notify_add_app_name == true
            val useOldV3 = cachedFunctionInfo?.ex_table_main10_v3_notify_msg == true
            if (useNotificationPara) {
                val para = com.ido.ble.protocol.model.NotificationPara().apply {
                    notify_type = v3TypeFor(notification.category)
                    contact = notification.appName
                    msg_data = notification.body
                    phone_number = ""
                    evt_type = 1 // VeryFit sends evt_type=1 for an incoming/new notification
                    // The app-name list the watch renders; one fallback entry mirrors VeryFit's
                    // "no name list → default name" branch (language index 1, the app/sender name).
                    items = listOf(
                        com.ido.ble.protocol.model.NotificationPara.AppNames().apply {
                            language = 1
                            name = notification.appName
                        },
                    )
                    app_items_len = items.size
                }
                Log.i(TAG, "sendNotification (NotificationPara) category=${notification.category} len=${notification.body.length}")
                BLEManager.sendNotification(para)
            } else if (useOldV3) {
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

    override fun stopIncomingCall() {
        if (!isConnected()) return
        runCatching {
            Log.i(TAG, "stopIncomingCall → setStopInComingCall")
            BLEManager.setStopInComingCall()
        }.onFailure { Log.w(TAG, "stopIncomingCall failed", it) }
    }

    override fun stopFindPhone() {
        if (!isConnected()) return
        // Only newer firmwares understand the explicit stop command (VeryFit gates it the same way);
        // older ones just time their find-phone screen out on their own.
        if (cachedFunctionInfo?.support_over_find_phone != true) {
            Log.i(TAG, "stopFindPhone skipped — watch doesn't advertise support_over_find_phone")
            return
        }
        runCatching {
            Log.i(TAG, "stopFindPhone → setStopFindPhone")
            BLEManager.setStopFindPhone(com.ido.ble.protocol.model.StopFindPhone())
        }.onFailure { Log.w(TAG, "stopFindPhone failed", it) }
    }

    // ── Remote camera (watch shutter) ──────────────────────────────────────────────────

    override fun enterCameraMode(): Boolean {
        if (!isConnected()) return false
        return runCatching {
            Log.i(TAG, "enterCameraMode")
            BLEManager.enterCameraMode()
            true
        }.onFailure { Log.w(TAG, "enterCameraMode failed", it) }.getOrDefault(false)
    }

    override fun exitCameraMode() {
        if (!isConnected()) return
        runCatching {
            Log.i(TAG, "exitCameraMode")
            BLEManager.exitCameraMode()
        }.onFailure { Log.w(TAG, "exitCameraMode failed", it) }
    }

    // ── Weather push (phone → watch) ───────────────────────────────────────────────────

    override fun supportsWeatherPush(): Boolean? {
        if (!isConnected()) return null
        val info = cachedFunctionInfo ?: return null
        return info.V3_support_set_v3_weather
    }

    override fun pushWeather(weather: WatchWeather): Boolean {
        if (!isConnected()) return false
        val info = cachedFunctionInfo ?: run {
            Log.w(TAG, "pushWeather skipped — function table not fetched yet")
            return false
        }
        // Only the V3 weather path is implemented; the legacy `weather` flag's WeatherInfo
        // command isn't needed for this hardware (the Active 4 Pro family is V3 throughout).
        if (!info.V3_support_set_v3_weather) {
            Log.w(TAG, "pushWeather skipped — watch lacks V3_support_set_v3_weather")
            return false
        }
        return runCatching {
            if (info.V3_support_set_weather_sun_time && weather.sunrise != null && weather.sunset != null) {
                BLEManager.setWeatherSunTime(
                    com.ido.ble.protocol.model.WeatherSunTime().apply {
                        sunrise_hour = weather.sunrise.hour
                        sunrise_min = weather.sunrise.minute
                        sunset_hour = weather.sunset.hour
                        sunset_min = weather.sunset.minute
                    },
                )
            }
            val v3 = buildWeatherInfoV3(weather, info)
            Log.i(
                TAG,
                "pushWeather → setWeatherDataV3 " +
                    "(${weather.cityName}, ${weather.temperatureC}°C, ${weather.condition}, " +
                    "${weather.hourly.size}h/${weather.daily.size}d)",
            )
            BLEManager.setWeatherDataV3(v3)
            true
        }.onFailure { Log.w(TAG, "pushWeather failed", it) }.getOrDefault(false)
    }

    private fun buildWeatherInfoV3(
        weather: WatchWeather,
        info: SupportFunctionInfo,
    ): com.ido.ble.protocol.model.WeatherInfoV3 {
        val now = java.util.Calendar.getInstance()
        return com.ido.ble.protocol.model.WeatherInfoV3().apply {
            month = now.get(java.util.Calendar.MONTH) + 1
            day = now.get(java.util.Calendar.DAY_OF_MONTH)
            hour = now.get(java.util.Calendar.HOUR_OF_DAY)
            min = now.get(java.util.Calendar.MINUTE)
            sec = now.get(java.util.Calendar.SECOND)
            // VeryFit sends weekday-1 (Calendar.DAY_OF_WEEK is 1=Sunday, so 0 = Sunday on the wire).
            week = now.get(java.util.Calendar.DAY_OF_WEEK) - 1
            city_name = weather.cityName
            weather_type = weatherTypeFor(weather.condition)
            // The V3 wire carries temperature as °C + 100 (keeps sub-zero temps unsigned).
            today_tmp = weather.temperatureC + 100
            today_max_temp = maxOf(weather.maxTempC, weather.temperatureC) + 100
            today_min_temp = minOf(weather.minTempC, weather.temperatureC) + 100
            humidity = weather.humidityPercent ?: 0
            today_uv_intensity = weather.uvIndex ?: 0
            precipitation_probability = weather.precipProbabilityPercent ?: 0
            wind_speed = weather.windSpeedKmh ?: 0
            wind_force = beaufortFor(weather.windSpeedKmh)
            // hPa × 100 on the wire; zeroed when the watch doesn't take the pressure extension.
            atmospheric_pressure =
                if (info.support_set_v3_weatcher_add_atmospheric_pressure) {
                    ((weather.pressureHpa ?: 0f) * 100).toInt()
                } else {
                    0
                }
            weather.sunrise?.let { sunrise_hour = it.hour; sunrise_min = it.minute }
            weather.sunset?.let { sunset_hour = it.hour; sunset_min = it.minute }
            if (info.v3_support_set_v3_weatcher_add_sunrise &&
                weather.sunrise != null && weather.sunset != null
            ) {
                sunrise_item = arrayListOf(
                    com.ido.ble.protocol.model.WeatherInfoV3.SunRiseSet().apply {
                        sunrise_hour = weather.sunrise.hour
                        sunrise_min = weather.sunrise.minute
                        sunset_hour = weather.sunset.hour
                        sunset_min = weather.sunset.minute
                    },
                )
                sunrise_item_num = 1
            }
            future_items = ArrayList(
                weather.daily.map { d ->
                    com.ido.ble.protocol.model.WeatherInfoV3.Future().apply {
                        weather_type = weatherTypeFor(d.condition)
                        max_temp = d.maxTempC + 100
                        min_temp = d.minTempC + 100
                    }
                },
            )
            hours_weather_items = ArrayList(
                weather.hourly.map { h ->
                    com.ido.ble.protocol.model.WeatherInfoV3.Hour24().apply {
                        weather_type = weatherTypeFor(h.condition)
                        temperature = h.temperatureC + 100
                        probability = h.precipProbabilityPercent ?: 0
                    }
                },
            )
        }
    }

    /** Our condition taxonomy → the IDO icon codes (WeatherInfoV3.WEATHER_TYPE_*). */
    private fun weatherTypeFor(condition: WatchWeatherCondition): Int = when (condition) {
        WatchWeatherCondition.CLEAR -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_CLEAR
        WatchWeatherCondition.PARTLY_CLOUDY -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_CLOUDY
        WatchWeatherCondition.OVERCAST -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_OVERCASTSKY
        WatchWeatherCondition.FOG -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_HAZE
        WatchWeatherCondition.DRIZZLE -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_SHOWER
        WatchWeatherCondition.RAIN -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_RAIN
        // No dedicated thunder icon in the classic set — heavy rain reads closest.
        WatchWeatherCondition.HEAVY_RAIN,
        WatchWeatherCondition.THUNDERSTORM,
        -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_RAINSTORM
        WatchWeatherCondition.SLEET -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_SLEET
        WatchWeatherCondition.SNOW -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_SNOW
        WatchWeatherCondition.WINDY -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_GALE
        WatchWeatherCondition.OTHER -> com.ido.ble.protocol.model.WeatherInfoV3.WEATHER_TYPE_OTHER
    }

    /** Beaufort number from km/h — VeryFit's wind_force is the same 0–12 level scale. */
    private fun beaufortFor(windKmh: Int?): Int {
        val v = windKmh ?: return 0
        val thresholds = intArrayOf(1, 5, 11, 19, 28, 38, 49, 61, 74, 88, 102, 117)
        thresholds.forEachIndexed { i, t -> if (v < t) return i }
        return 12
    }

    /**
     * Enable the watch's per-type message-notify state so it will actually *display* the notices we
     * push (W7). Mirrors VeryFit's `RemindDataManager.sendDefaultNotificationState2Device`, which sends
     * a list of [MessageNotifyState] with `notify_state = ALLOW` via `addMessageNotifyState(list, 0, 0)`
     * on connect. Without it the watch silently drops every V3MessageNotice/NewMessageInfo even though
     * the SDK accepts the send. Pushed once per connected session (after a sync, so it never interleaves
     * with the order-sensitive data-pull commands) for the categories we mirror.
     */
    private fun enableMessageNotifyStates() {
        if (notifyStatesEnabled || !isConnected()) return
        runCatching {
            val states = listOf(
                WatchNotificationCategory.CALL,
                WatchNotificationCategory.MISSED_CALL,
                WatchNotificationCategory.SMS,
                WatchNotificationCategory.GENERIC,
            ).map { category ->
                com.ido.ble.protocol.model.MessageNotifyState().apply {
                    evt_type = v3TypeFor(category)
                    notify_state = com.ido.ble.protocol.model.NotifyType.ALLOW
                }
            }
            BLEManager.addMessageNotifyState(states, 0, 0)
            notifyStatesEnabled = true
            Log.i(TAG, "enabled message notify states (ALLOW) for ${states.size} categories")
        }.onFailure { Log.w(TAG, "enableMessageNotifyStates failed", it) }
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
        cm.registerOperateCallBack(operateCallBack)
        cm.registerOperateMusicCallBack(operateMusicCallBack)
        // NOTE: no registerSyncActivity/HealthCallBack here. The orchestrated syncAllData() path
        // delivers every record through the SyncPara.ISyncDataListener (syncDataListener) below;
        // registering the CallBackManager SyncCallBacks too would double-deliver each record.
    }

    // ── On-watch music library + serialized mutation state machine ─────────────────────

    private val operateCallBack = object : OperateCallBack.ICallBack {
        override fun onQueryResult(type: OperateCallBack.OperateType?, value: Any?) {
            if (type != OperateCallBack.OperateType.MUSIC_AND_FOLDER ||
                musicOperation !is MusicOperation.Query
            ) {
                return
            }
            clearMusicOperationTimeout()
            musicOperation = MusicOperation.Idle
            val info = value as? MusicOperate.MusicAndFolderInfo
            if (info == null) {
                _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                    status = WatchMusicLibraryStatus.ERROR,
                    error = "The watch returned an invalid library response.",
                )
                Log.w(MUSIC_TAG, "library query returned unexpected payload")
                return
            }
            _watchMusicLibrary.value = mapWatchMusicLibrary(
                WatchMusicLibraryInput(
                    totalBytes = info.all_memory,
                    usedBytes = info.used_memory,
                    freeBytes = info.useful_memory,
                    songs = info.music_items.orEmpty().map { song ->
                        WatchMusicLibraryItem(
                            musicId = song.music_id,
                            fileName = song.music_name.orEmpty(),
                            artist = song.singer_name.orEmpty(),
                            sizeBytes = song.music_memory,
                        )
                    },
                    folders = info.folder_items.orEmpty().map { folder ->
                        WatchMusicFolder(
                            folderId = folder.folder_id,
                            name = folder.folder_name.orEmpty(),
                            musicIds = folder.music_index.orEmpty().distinct(),
                        )
                    },
                ),
            )
            Log.i(
                MUSIC_TAG,
                "library query complete count=${info.music_items.orEmpty().size} " +
                    "used=${info.used_memory} free=${info.useful_memory}",
            )
            applyPhoneMusicEnabled()
        }

        override fun onAddResult(type: OperateCallBack.OperateType?, success: Boolean) {}
        override fun onDeleteResult(type: OperateCallBack.OperateType?, success: Boolean) {}
        override fun onModifyResult(type: OperateCallBack.OperateType?, success: Boolean) {}
        override fun onSetResult(type: OperateCallBack.OperateType?, success: Boolean) {}
    }

    private val operateMusicCallBack = object : OperateCallBack.IMusicCallBack {
        override fun onAddMusic(type: OperateCallBack.OperateType?, success: Boolean, musicId: Int) {
            val operation = musicOperation as? MusicOperation.Import ?: return
            if (!success || musicId < 0) {
                Log.w(MUSIC_TAG, "reservation failed")
                finishWatchSongImport(
                    if (operation.cancelRequested) WatchMusicTransferStatus.CANCELLED
                    else WatchMusicTransferStatus.FAILED,
                    if (operation.cancelRequested) null else "The watch rejected the song reservation.",
                )
                return
            }
            if (operation.cancelRequested) {
                beginReservedSongCleanup(
                    operation.song,
                    musicId,
                    WatchMusicTransferStatus.CANCELLED,
                    null,
                    stopTransfer = false,
                )
                return
            }
            clearMusicOperationTimeout()
            musicOperation = operation.copy(musicId = musicId)
            _watchMusicTransfer.value = _watchMusicTransfer.value.copy(
                status = WatchMusicTransferStatus.TRANSFERRING,
                progressPercent = 0,
            )
            scheduleMusicTimeout(MUSIC_TRANSFER_TIMEOUT_MS)
            runCatching {
                val config = FileTransferConfig.getDefaultMusicFileConfig(
                    operation.song.privateCachePath,
                    musicFileTransferListener,
                ).apply {
                    firmwareSpecName = operation.song.firmwareFileName
                }
                Log.i(MUSIC_TAG, "BLE transfer requested id=$musicId bytes=${operation.song.sizeBytes}")
                BLEManager.startTranCommonFile(config)
            }.onFailure {
                Log.w(MUSIC_TAG, "BLE transfer dispatch failed id=$musicId", it)
                beginReservedSongCleanup(
                    operation.song,
                    musicId,
                    WatchMusicTransferStatus.FAILED,
                    "Couldn't start the BLE transfer.",
                    stopTransfer = false,
                )
            }
        }

        override fun onDeleteMusic(type: OperateCallBack.OperateType?, success: Boolean) {
            when (val operation = musicOperation) {
                is MusicOperation.Cleanup -> {
                    Log.i(MUSIC_TAG, "reserved-row cleanup complete success=$success id=${operation.musicId}")
                    finishWatchSongImport(operation.terminalStatus, operation.error)
                }
                is MusicOperation.Delete -> {
                    clearMusicOperationTimeout()
                    musicOperation = MusicOperation.Idle
                    _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                        error = if (success) null else "The watch could not delete the song.",
                    )
                    Log.i(MUSIC_TAG, "delete complete success=$success id=${operation.song.musicId}")
                    refreshLibraryAfterMutation()
                }
                else -> Unit
            }
        }

        override fun onAddFolder(type: OperateCallBack.OperateType?, success: Boolean) {
            val operation = musicOperation as? MusicOperation.FolderCreate ?: return
            Log.i(MUSIC_TAG, "playlist create complete success=$success id=${operation.folder.folderId}")
            finishMusicLibraryMutation(success, if (success) null else "The watch rejected the playlist.")
        }

        override fun onDeleteFolder(type: OperateCallBack.OperateType?, success: Boolean) {
            val operation = musicOperation as? MusicOperation.FolderDelete ?: return
            Log.i(MUSIC_TAG, "playlist delete complete success=$success id=${operation.folder.folderId}")
            finishMusicLibraryMutation(success, if (success) null else "The watch could not delete the playlist.")
        }

        override fun onDeleteFolderMusic(type: OperateCallBack.OperateType?, success: Boolean) {
            continueFolderUpdate(FolderUpdateStage.REMOVE, success)
        }

        override fun onImportFolder(type: OperateCallBack.OperateType?, success: Boolean) {
            continueFolderUpdate(FolderUpdateStage.ADD, success)
        }

        override fun onInvalid(type: OperateCallBack.OperateType?, success: Boolean) {
            if (musicOperation is MusicOperation.FolderCreate ||
                musicOperation is MusicOperation.FolderUpdate ||
                musicOperation is MusicOperation.FolderDelete
            ) {
                finishMusicLibraryMutation(false, "The watch rejected the playlist operation.")
            }
        }

        override fun onModifyFolder(type: OperateCallBack.OperateType?, success: Boolean) {
            continueFolderUpdate(FolderUpdateStage.RENAME, success)
        }
    }

    private val musicFileTransferListener = object : IFileTransferListener {
        override fun onStart() {
            if (musicOperation is MusicOperation.Import) Log.i(MUSIC_TAG, "BLE transfer started")
        }

        override fun onProgress(percent: Int) {
            if (musicOperation !is MusicOperation.Import) return
            _watchMusicTransfer.value = reduceWatchMusicProgress(_watchMusicTransfer.value, percent)
            Log.i(MUSIC_TAG, "BLE transfer progress=${_watchMusicTransfer.value.progressPercent}")
        }

        override fun onSuccess() {
            val operation = musicOperation as? MusicOperation.Import ?: return
            clearMusicOperationTimeout()
            Log.i(MUSIC_TAG, "BLE transfer succeeded id=${operation.musicId}")
            finishWatchSongImport(WatchMusicTransferStatus.SUCCEEDED, null)
        }

        override fun onFailed(error: String?) {
            val operation = musicOperation as? MusicOperation.Import ?: return
            val musicId = operation.musicId
            Log.w(MUSIC_TAG, "BLE transfer failed id=$musicId errorPresent=${!error.isNullOrBlank()}")
            if (musicId == null) {
                finishWatchSongImport(WatchMusicTransferStatus.FAILED, "The BLE transfer failed.")
            } else {
                beginReservedSongCleanup(
                    operation.song,
                    musicId,
                    WatchMusicTransferStatus.FAILED,
                    "The BLE transfer failed.",
                    stopTransfer = false,
                )
            }
        }
    }

    private fun beginReservedSongCleanup(
        song: WatchSongImport,
        musicId: Int,
        terminalStatus: WatchMusicTransferStatus,
        error: String?,
        stopTransfer: Boolean,
    ) {
        clearMusicOperationTimeout()
        musicOperation = MusicOperation.Cleanup(song, musicId, terminalStatus, error)
        _watchMusicTransfer.value = _watchMusicTransfer.value.copy(
            status = WatchMusicTransferStatus.CLEANING_UP,
            error = null,
        )
        if (stopTransfer) runCatching { BLEManager.stopTranCommonFile() }
        scheduleMusicTimeout(MUSIC_COMMAND_TIMEOUT_MS)
        runCatching {
            BLEManager.deleteMusicFile(
                MusicOperate.MusicFile().apply {
                    music_id = musicId
                    music_name = song.firmwareFileName
                    singer_name = song.artist
                    music_memory = song.sizeBytes
                },
            )
        }.onFailure {
            Log.w(MUSIC_TAG, "reserved-row cleanup dispatch failed id=$musicId", it)
            finishWatchSongImport(terminalStatus, error)
        }
    }

    private fun finishWatchSongImport(status: WatchMusicTransferStatus, error: String?) {
        val song = when (val operation = musicOperation) {
            is MusicOperation.Import -> operation.song
            is MusicOperation.Cleanup -> operation.song
            else -> null
        }
        clearMusicOperationTimeout()
        musicOperation = MusicOperation.Idle
        song?.let { runCatching { File(it.privateCachePath).delete() } }
        _watchMusicTransfer.value = WatchMusicTransferState(
            status = status,
            progressPercent = if (status == WatchMusicTransferStatus.SUCCEEDED) 100 else null,
            bytesTotal = song?.sizeBytes ?: _watchMusicTransfer.value.bytesTotal,
            error = error,
        )
        refreshLibraryAfterMutation()
        schedulePhoneMusicApply()
    }

    private fun refreshLibraryAfterMutation() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { refreshWatchMusicLibrary() },
            MUSIC_REFRESH_DELAY_MS,
        )
    }

    private fun scheduleMusicTimeout(delayMillis: Long) {
        musicTimeoutHandler.removeCallbacks(musicTimeoutRunnable)
        musicTimeoutHandler.postDelayed(musicTimeoutRunnable, delayMillis)
    }

    private fun clearMusicOperationTimeout() {
        musicTimeoutHandler.removeCallbacks(musicTimeoutRunnable)
    }

    private fun onMusicOperationTimeout() {
        when (val operation = musicOperation) {
            MusicOperation.Idle -> Unit
            MusicOperation.Query -> {
                musicOperation = MusicOperation.Idle
                _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                    status = WatchMusicLibraryStatus.ERROR,
                    error = "The watch library query timed out.",
                )
                Log.w(MUSIC_TAG, "library query timed out")
            }
            is MusicOperation.Import -> {
                if (operation.musicId != null) {
                    beginReservedSongCleanup(
                        operation.song,
                        operation.musicId,
                        if (operation.cancelRequested) WatchMusicTransferStatus.CANCELLED
                        else WatchMusicTransferStatus.FAILED,
                        if (operation.cancelRequested) null else "The watch transfer timed out.",
                        stopTransfer = true,
                    )
                } else {
                    finishWatchSongImport(
                        if (operation.cancelRequested) WatchMusicTransferStatus.CANCELLED
                        else WatchMusicTransferStatus.FAILED,
                        if (operation.cancelRequested) null else "The watch reservation timed out.",
                    )
                }
            }
            is MusicOperation.Cleanup -> {
                Log.w(MUSIC_TAG, "reserved-row cleanup timed out id=${operation.musicId}")
                finishWatchSongImport(operation.terminalStatus, operation.error)
            }
            is MusicOperation.Delete -> {
                musicOperation = MusicOperation.Idle
                _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                    error = "Deleting the song timed out.",
                )
                refreshLibraryAfterMutation()
            }
            is MusicOperation.FolderCreate,
            is MusicOperation.FolderUpdate,
            is MusicOperation.FolderDelete,
            -> finishMusicLibraryMutation(false, "The playlist operation timed out.")
        }
    }

    private fun abortMusicOperationOnDisconnect() {
        val operation = musicOperation
        clearMusicOperationTimeout()
        if (operation is MusicOperation.Import && operation.musicId != null) {
            runCatching { BLEManager.stopTranCommonFile() }
        }
        val song = when (operation) {
            is MusicOperation.Import -> operation.song
            is MusicOperation.Cleanup -> operation.song
            else -> null
        }
        song?.let { runCatching { File(it.privateCachePath).delete() } }
        if (song != null) {
            _watchMusicTransfer.value = WatchMusicTransferState(
                status = if (operation is MusicOperation.Import && operation.cancelRequested) {
                    WatchMusicTransferStatus.CANCELLED
                } else {
                    WatchMusicTransferStatus.FAILED
                },
                bytesTotal = song.sizeBytes,
                error = if (operation is MusicOperation.Import && operation.cancelRequested) null
                else "The watch disconnected during the operation.",
            )
        }
        if (operation is MusicOperation.FolderCreate ||
            operation is MusicOperation.FolderUpdate ||
            operation is MusicOperation.FolderDelete
        ) {
            _watchMusicLibraryMutation.value = WatchMusicLibraryMutationState(
                WatchMusicLibraryMutationStatus.FAILED,
                "The watch disconnected during the playlist operation.",
            )
        }
        if (operation !is MusicOperation.Idle) {
            _watchMusicLibrary.value = _watchMusicLibrary.value.copy(
                status = WatchMusicLibraryStatus.UNAVAILABLE,
                error = null,
            )
        }
        musicOperation = MusicOperation.Idle
    }

    /** The native delete path dereferences music_name; passing only an id crashes in JNI. */
    private fun WatchMusicLibraryItem.toSdkMusicFile(): MusicOperate.MusicFile =
        MusicOperate.MusicFile().also { sdkSong ->
            sdkSong.music_id = musicId
            sdkSong.music_name = fileName
            sdkSong.singer_name = artist
            sdkSong.music_memory = sizeBytes
        }

    private fun WatchMusicFolder.toSdkMusicFolder(): MusicOperate.MusicFolder =
        MusicOperate.MusicFolder().also { sdkFolder ->
            sdkFolder.folder_id = folderId
            sdkFolder.folder_name = name
            sdkFolder.music_index = musicIds.toMutableList()
            sdkFolder.music_num = musicIds.size
        }

    // ── Scan → connect → bind lifecycle ──────────────────────────────────────────────

    private val scanCallBack = object : ScanCallBack.ICallBack {
        override fun onStart() { noteConnectProgress(); Log.i(TAG, "scan started (target=$targetMac)") }
        override fun onScanFinished() {
            noteConnectProgress()
            Log.i(TAG, "scan finished")
            // Still SCANNING here means the whole scan completed WITHOUT finding the target (a
            // match would have moved us to CONNECTING). Fail the attempt now — retrying after the
            // short backoff — instead of letting the 90s stall watchdog time the silence out. The
            // watchdog window is for mid-CONNECT wedges; a finished-but-empty scan is a known outcome
            // and waiting it out left the UI stuck on "Scanning…" for minutes (pairing regression
            // vs the original single-shot scan, which surfaced the miss immediately).
            if (targetMac != null && _connectionState.value == WatchEngineConnectionState.SCANNING) {
                handleConnectAttemptFailed("scan finished without finding $targetMac")
            }
        }
        override fun onFindDevice(device: BLEDevice?) {
            noteConnectProgress()
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
            noteConnectProgress()
            Log.i(TAG, "onConnectStart $mac")
            _connectionState.value = WatchEngineConnectionState.CONNECTING
        }
        override fun onConnecting(mac: String?) { noteConnectProgress(); Log.i(TAG, "onConnecting $mac") }
        override fun onRetry(times: Int, mac: String?) { noteConnectProgress(); Log.i(TAG, "onRetry $times $mac") }

        override fun onConnectSuccess(mac: String?) {
            // Link is up — clear the GATT-133 retry budget so any *later* transient drop gets a
            // fresh set of attempts of its own, and disarm the connect watchdog (past the wedge window).
            connectAttempts = 0
            connectRetryHandler.removeCallbacks(connectRetryRunnable)
            connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
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
            // System (Classic BT) bond — separate from the SDK's GATT bind. Call audio/HFP and the
            // watch appearing in Android's Bluetooth settings ride on it. The pre-SDK clean-room
            // stack requested it (createBond) but that was lost when the stack was deleted
            // (19bef55); since then nothing ever asked Android to pair. Deferred a beat so the
            // pairing prompt never races the link setup / bind window.
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ ensureSystemBond() }, SYSTEM_BOND_DELAY_MS)
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
            // The WATCH's authoritative "you are not bound to me" — fired when another app
            // (VeryFit) re-claimed it since our last session. The SDK's local DB still says
            // isBind()=true, so onConnectSuccess's stale-flag check skips bind(): the app then
            // connects but has NO control and the watch never releases its buffered health data
            // (real data loss once VeryFit pulls it). Trust the watch over the local flag and
            // re-bind to reclaim — may prompt a confirmation on the watch face (onNeedAuth).
            Log.w(TAG, "onDeviceInNotBindStatus $mac — watch says we're NOT bound (stale local bind); re-binding to reclaim")
            _connectionState.value = WatchEngineConnectionState.BINDING
            runCatching { BLEManager.bind() }
                .onFailure { Log.w(TAG, "re-bind after onDeviceInNotBindStatus failed", it) }
        }

        override fun onConnectFailed(reason: ConnectFailedReason?, mac: String?) {
            handleConnectAttemptFailed("onConnectFailed reason=$reason mac=$mac")
        }

        override fun onConnectBreak(mac: String?) {
            Log.w(TAG, "onConnectBreak $mac")
            abortMusicOperationOnDisconnect()
            functionTableCached = false
            cachedFunctionInfo = null
            pendingSyncAfterFunctionTable = false
            notifyStatesEnabled = false
            appliedPhoneMusicEnabled = null
            _musicCapabilities.value = WatchMusicCapabilities()
            // The link is down for good; a wedge-watchdog from this attempt no longer applies.
            connectWatchdogHandler.removeCallbacks(connectWatchdogRunnable)
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onInDfuMode(device: BLEDevice?) { Log.w(TAG, "onInDfuMode") }
    }

    /**
     * Ensure the watch is bonded at the ANDROID level (Classic BT pairing — the system dialog).
     * Restores the deleted clean-room stack's behaviour: without this bond the watch never shows
     * in Bluetooth settings and HFP call audio can't route. No-op when already bonded/bonding.
     */
    private fun ensureSystemBond() {
        val mac = targetMac ?: return
        if (!isConnected()) return
        runCatching {
            val adapter =
                (app.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                    ?.adapter ?: return
            val device = adapter.getRemoteDevice(mac)
            when (device.bondState) {
                android.bluetooth.BluetoothDevice.BOND_NONE -> {
                    Log.i(TAG, "system bond missing for $mac → createBond() (Android shows the pairing prompt)")
                    Log.i(TAG, "createBond dispatched=${device.createBond()}")
                }
                android.bluetooth.BluetoothDevice.BOND_BONDING ->
                    Log.i(TAG, "system bond for $mac already in progress")
                else -> Log.i(TAG, "system bond for $mac already present")
            }
        }.onFailure { Log.w(TAG, "ensureSystemBond failed (BLUETOOTH_CONNECT granted?)", it) }
    }

    private val bindCallBack = object : BindCallBack.ICallBack {
        override fun onSuccess() {
            Log.i(TAG, "BIND SUCCESS — watch claimed by our app; fetching function table then syncing")
            bindRejectedAtMs = 0L
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
            Log.w(TAG, "bind REJECTED on the watch — pausing auto-reconnect (cooldown)")
            bindRejectedAtMs = System.currentTimeMillis()
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onCancel() {
            Log.w(TAG, "bind cancelled — pausing auto-reconnect (cooldown)")
            bindRejectedAtMs = System.currentTimeMillis()
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun onFailed(error: BindCallBack.BindFailedError?) {
            Log.w(TAG, "bind FAILED: $error")
            _connectionState.value = WatchEngineConnectionState.DISCONNECTED
            listener?.onSyncFailed()
        }
    }

    // ── Device → app control (W7 call control) ────────────────────────────────────────
    // Call actions flow to WatchConnectionService. Music actions flow to the app-owned media-session
    // controller, which allows a short grace period for native AVRCP before applying a GATT fallback.
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
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.OPEN_CAMERA ->
                    WatchControlEvent.CAMERA_OPEN
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.CLOSE_CAMERA ->
                    WatchControlEvent.CAMERA_CLOSE
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.TAKE_ONE_PHOTO,
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.TAKE_MULTI_PHOTO,
                ->
                    WatchControlEvent.CAMERA_TAKE_PHOTO
                else -> null
            }
            if (event != null) {
                Log.i(TAG, "device control: $type → $event")
                _controlEvents.tryEmit(event)
            }
            val musicEvent = when (type) {
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.START ->
                    WatchMusicControlEvent.PLAY
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.PAUSE ->
                    WatchMusicControlEvent.PAUSE
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.STOP ->
                    WatchMusicControlEvent.STOP
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.NEXT ->
                    WatchMusicControlEvent.NEXT
                com.ido.ble.callback.DeviceControlAppCallBack.DeviceControlEventType.PREVIOUS ->
                    WatchMusicControlEvent.PREVIOUS
                else -> null
            }
            if (musicEvent != null) {
                Log.i(TAG, "device music control: $type")
                _musicControlEvents.tryEmit(musicEvent)
            }
        }

        override fun onAntiLostNotice(on: Boolean, time: Long) {}

        // Protocol events 570/571: the watch started (on=true) or cancelled (on=false) find-phone.
        override fun onFindPhone(on: Boolean, time: Long) {
            Log.i(TAG, "onFindPhone on=$on")
            _controlEvents.tryEmit(
                if (on) WatchControlEvent.FIND_PHONE_START else WatchControlEvent.FIND_PHONE_STOP,
            )
        }

        override fun onOneKeySOS(on: Boolean, time: Long) {}
    }

    // ── Device info: function table gate ──────────────────────────────────────────────
    // Extends BaseGetDeviceInfoCallBack (no-op defaults) so we only handle onGetFunctionTable.
    private val deviceInfoCallBack = object : BaseGetDeviceInfoCallBack() {
        override fun onGetFunctionTable(info: SupportFunctionInfo?) {
            functionTableCached = info != null
            cachedFunctionInfo = info
            _musicCapabilities.value = WatchMusicCapabilities(
                known = info != null,
                phoneMusicControl = info?.bleControlMusic == true,
                artistName = info?.V3_music_control_02_add_singer_name == true,
                onboardMusic = info?.V3_support_v3_ble_music == true,
            )
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
            } else if (info != null) {
                applyPhoneMusicEnabled()
                if (info.V3_support_v3_ble_music == true) refreshLibraryAfterMutation()
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
            enableMessageNotifyStates()
            schedulePhoneMusicApply()
            refreshLibraryAfterMutation()
            listener?.onSyncComplete()
        }
        override fun onFailed(type: SyncPara.SyncFailedType?) {
            // Often the benign post-transfer conn-param step failing after all data is delivered
            // (roadmap W4) — the link is usually still up, so reflect actual connectivity.
            Log.w(TAG, "syncAllData onFailed: $type")
            logSyncDiagnostics()
            _connectionState.value = postSyncState()
            enableMessageNotifyStates()
            schedulePhoneMusicApply()
            refreshLibraryAfterMutation()
            listener?.onSyncFailed()
        }
    }

    /** Keep music commands out of the ordered health sync and the notify-state command beside it. */
    private fun schedulePhoneMusicApply() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { applyPhoneMusicEnabled() },
            500L,
        )
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
            // Raw-SDK sleep ground-truth capture. The domain model + dashboard schema now DO carry
            // rem_*/avg_* (toDomain() below maps them; landing in watch_sleep_sessions verified
            // 2026-06-28), so this no longer fills a gap in toDomain(). It is kept deliberately, not
            // removed: it logs the raw HealthSleepV3 fields at the SDK boundary and — together with
            // the decoded `WatchHealthUpload: SLEEP` line — is our no-loss capture path, because the
            // upload has no local retry (a failed POST drops the batch). DEBUG-gated, logcat-only
            // (developer-only health data per CLAUDE.md — never uploaded model-facing).
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
        // never fires here). The full downstream pipeline IS wired — domain [WatchHeartRateReading],
        // [WatchHealthListener.onHeartRateReading], the uploader buffer, [WatchHeartRateReadingDto],
        // the dashboard `watch_heart_rate_readings` table + route, and the Watch-screen count.
        //
        // Per-item wall-clock: each [HealthHeartRateSecondItem] carries `heartRateVal` + `offset`. The
        // offset is NOT an absolute time — it is a **delta in seconds since the previous item**, so the
        // wall-clock is the RUNNING SUM of offsets across the whole list:
        //   cum += item.offset ;  recordedAt = midnight(year, month, day) + cum seconds   (startTime=0, unused)
        // Gaps wider than a byte are split into `offset=255, heartRateVal=0` continuation sentinels
        // (a long unworn stretch shows up as a run of consecutive 255s); the hr=0 entries fail the bpm
        // guard so they emit nothing, but their offset MUST still accumulate or every later sample drifts.
        //
        // This is HARDWARE-MEASURED (2026-06-29), not the earlier `getOffset` guess — that oracle read
        // was the manual one-click-measure WRITER path, which is absolute; the device-SYNC payload is
        // delta-encoded (offsetRange=[0..255], byte deltas). Verified against the raw `ido-logs` JSON
        // with two independent wall-clock anchors: a full day (2026-06-28) accumulates to 86109 s → last
        // sample 23:55:09 (≈ a 24 h span), and the partial current day (2026-06-29) accumulates to
        // 38721 s → last sample 10:45:21, ~4 min before the 10:49:40 sync. Both pin the unit to seconds.
        // Probe kept below for regression visibility. Debug-gated (decoded data).
        override fun onGetHealthHeartRateSecondData(
            data: com.ido.ble.data.manage.database.HealthHeartRateSecond?,
            isLast: Boolean,
        ) {
            if (data == null || data.year == 0) return
            val items = data.items ?: emptyList()
            val itemCount = items.size
            // offset is a per-item delta in seconds; accumulate over ALL items, emit on plausible bpm.
            var mapped = 0
            var cum = 0
            items.forEach { item ->
                cum += item.offset
                if (item.heartRateVal !in 1..250) return@forEach
                val cumSeconds = cum
                runCatching {
                    WatchTime.localDateTime(data.year, data.month, data.day, cumSeconds)
                }.onSuccess { ts ->
                    listener?.onHeartRateReading(WatchHeartRateReading(recordedAt = ts, bpm = item.heartRateVal))
                    mapped++
                }
            }
            diagnostics.record(
                WatchSyncDiagnostics.HEART_RATE_SECOND,
                parentRecords = 1,
                itemSamples = itemCount,
                mappedReadings = mapped,
            )
            if (BuildConfig.DEBUG) {
                val vals = items.map { it.heartRateVal }
                val offsets = items.map { it.offset }
                val nonZero = vals.count { it > 0 }
                val firstNonZero = vals.indexOfFirst { it > 0 }
                val lastNonZero = vals.indexOfLast { it > 0 }
                // Per-item offset is the field never logged before — the key to the timestamp model.
                // Log head/tail as offset=hr pairs, the offset range, and the dominant offset step.
                val head = items.take(6).joinToString(",") { "${it.offset}=${it.heartRateVal}" }
                val tail = items.takeLast(6).joinToString(",") { "${it.offset}=${it.heartRateVal}" }
                val steps = offsets.zipWithNext { a, b -> b - a }
                val stepHisto = steps.groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(4).joinToString(",") { "${it.key}x${it.value}" }
                val fiveMin = data.five_min_data ?: emptyList()
                val fiveHead = fiveMin.take(6).joinToString(",")
                val intervals = data.hrInterval?.take(4)?.joinToString(",") { "m${it.minute}/t${it.threshold}" }
                // hr_data high/low entries DO carry wall-clock hour:minute — the ground truth to align
                // the offset→time mapping against. Log all of them (typically a handful).
                val hrTimes = data.hr_data?.joinToString(",") { "${it.hour}:${it.minute}=${it.heart_rate}(t${it.type})" }
                Log.d(
                    TAG,
                    "heartRateSecond probe: date=${WatchTime.ymd(data.year, data.month, data.day)} " +
                        "startTime=${data.startTime} items=$itemCount nonZero=$nonZero " +
                        "firstNZidx=$firstNonZero lastNZidx=$lastNonZero " +
                        "offsetRange=[${offsets.minOrNull()}..${offsets.maxOrNull()}] stepHisto=[$stepHisto] " +
                        "fiveMin=${fiveMin.size}(avg=${data.five_min_avg_data},max=${data.five_min_max_data},min=${data.five_min_min_data}) fiveHead=[$fiveHead] " +
                        "hrInterval=${data.hrInterval?.size ?: 0}[$intervals] hrDataCount=${data.hr_data_count} " +
                        "silentHR=${data.silentHR}\n" +
                        "  itemsHead=[$head]\n  itemsTail=[$tail]\n  hrData=[$hrTimes]",
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
        // One complete route per call (VeryFit's SyncDeviceDataProxy.processGpsData consumes it the
        // same way, ignoring isLast — that flag only marks the end of the multi-record stream).
        override fun onGetGpsData(data: com.ido.ble.gps.database.HealthGps?, items: MutableList<com.ido.ble.gps.database.HealthGpsItem>?, isLast: Boolean) {
            if (data == null) return
            diagnostics.record(WatchSyncDiagnostics.GPS_V2, parentRecords = 1, itemSamples = items?.size ?: 0)
            val points = items.orEmpty().mapNotNull { item ->
                val lat = item.latitude ?: return@mapNotNull null
                val lon = item.longitude ?: return@mapNotNull null
                // (0,0) is the watch's "no fix" filler, not a real position off the Ghanaian coast.
                if (lat == 0.0 && lon == 0.0) null else WatchGpsPoint(latitude = lat, longitude = lon)
            }
            if (points.isEmpty()) return
            listener?.onGpsRoute(
                WatchGpsRoute(
                    startDateTime = WatchTime.ymdhms(
                        data.year ?: 0,
                        data.month ?: 0,
                        data.day ?: 0,
                        data.hour ?: 0,
                        data.minute ?: 0,
                        data.second ?: 0,
                    ),
                    intervalSeconds = data.data_interval,
                    points = points,
                ),
            )
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
        // The notification-send path this watch needs (see [sendNotification]); logged once per connect.
        Log.i(
            TAG,
            "W7 notify path: v3_add_app_name=${readFunctionFlag("V3_support_set_v3_notify_add_app_name")}, " +
                "ex_v3_notify_msg=${readFunctionFlag("ex_table_main10_v3_notify_msg")}",
        )
        Log.i(
            TAG,
            "music capabilities: control=${readFunctionFlag("bleControlMusic")}, " +
                "artist=${readFunctionFlag("V3_music_control_02_add_singer_name")}, " +
                "onboard=${readFunctionFlag("V3_support_v3_ble_music")}, " +
                "appSpp=${readFunctionFlag("support_app_connect_with_spp")}",
        )
        // On-watch UI customization surface (sport-list + menu reorder/select; see
        // docs/plans — these gate setSportModeSortInfoV3 / setMenuListV3 feasibility).
        Log.i(
            TAG,
            "customization flags: sport_sort=${readFunctionFlag("sport_mode_sort")}, " +
                "sport_show_num=${readFunctionFlag("sport_show_num")}, " +
                "v3_sports_type=${readFunctionFlag("ex_table_main7_v3_sports_type")}, " +
                "v3_sport_sort_field=${readFunctionFlag("V3_support_v3_get_sport_sort_field")}, " +
                "no_add_delete=${readFunctionFlag("not_support_delete_add_sport_sort")}, " +
                "sport100=${readFunctionFlag("V3_set_100_sport_sort")}, " +
                "menu_list_v3=${readFunctionFlag("support_protocol_v3_menu_list")}, " +
                "get_menu_v3=${readFunctionFlag("V3_get_menu_list")}, " +
                "menu_main7=${readFunctionFlag("ex_main7_menu_list")}, " +
                "shortcut=${readFunctionFlag("shortcut")}, " +
                "weather_v3=${readFunctionFlag("V3_support_set_v3_weather")}, " +
                "over_find_phone=${readFunctionFlag("support_over_find_phone")}",
        )
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
        // v2 HealthSleep carries no fall-asleep time and these devices don't report naps (one night
        // per date), so midnight-of-wake-date is a stable, idempotent dedup key for the upsert.
        startDateTime = WatchTime.ymdhms(year, month, day, 0, 0, 0),
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
        // Real sleep onset — the discriminator that keeps a nap and the main night (same wake date)
        // as separate rows server-side. 0-year is the SDK's empty sentinel → leave null.
        startDateTime = if (fall_asleep_year == 0) {
            null
        } else {
            WatchTime.ymdhms(
                fall_asleep_year, fall_asleep_month, fall_asleep_day,
                fall_asleep_hour, fall_asleep_minte, 0,
            )
        },
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
        override fun onHeartRateReading(reading: WatchHeartRateReading) { logPrivateRecord("HEART_RATE_SECOND", reading) }
        override fun onSyncProgress(percent: Int) { Log.i(TAG, "sync progress $percent%") }
        override fun onSyncComplete() { Log.i(TAG, "sync complete") }
        override fun onSyncFailed() { Log.w(TAG, "sync failed") }

        private fun logPrivateRecord(kind: String, record: Any) {
            if (BuildConfig.DEBUG) Log.i(TAG, "$kind $record")
        }
    }

    private companion object {
        const val MUSIC_TAG = "WatchMusicTransfer"
        const val MUSIC_COMMAND_TIMEOUT_MS = 30_000L
        const val MUSIC_TRANSFER_TIMEOUT_MS = 10 * 60_000L
        const val MUSIC_REFRESH_DELAY_MS = 750L
        const val TAG = "IdoSdkWatchEngine"

        /** Bounded scan→connect retries after a transient GATT-133 first-connect failure. */
        const val MAX_CONNECT_RETRIES = 2
        const val CONNECT_RETRY_DELAY_MS = 2_000L

        /** How long a watch-face bind rejection suppresses auto-reconnect (prompt anti-spam). */
        const val BIND_REJECT_COOLDOWN_MS = 5 * 60_000L

        /** Beat between the GATT link settling and requesting the system (Classic) bond. */
        const val SYSTEM_BOND_DELAY_MS = 3_000L

        /**
         * How long the connect may go with NO progress callback while still SCANNING/CONNECTING
         * before the stall watchdog declares it wedged and forces a teardown+retry. It's a *silence*
         * window, reset on every progress callback (see [noteConnectProgress]) — so it must sit above
         * the SDK's healthy retry cadence: hardware showed onConnectStart/onScanFinished landing every
         * ≤~35s during a watch-off auto-reconnect, so 90s gives ~2.5× margin and only a genuine
         * (callback-silent) wedge trips it. A normal connect reaches onConnectSuccess in seconds and
         * clears the watchdog long before this; bind() happens afterwards and is outside the window.
         */
        const val CONNECT_STALL_MS = 90_000L

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
