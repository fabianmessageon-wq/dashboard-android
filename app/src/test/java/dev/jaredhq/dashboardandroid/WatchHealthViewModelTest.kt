package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ui.watch.WatchHealthViewModel
import dev.jaredhq.dashboardandroid.watch.engine.WatchBloodPressureReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchStressReading
import dev.jaredhq.dashboardandroid.watch.engine.WatchUploadOutcome
import dev.jaredhq.dashboardandroid.watch.engine.WatchWorkout
import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicCapabilities
import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicLibraryState
import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicLibraryMutationState
import dev.jaredhq.dashboardandroid.watch.engine.WatchMusicTransferState
import dev.jaredhq.dashboardandroid.watch.music.PhoneMusicState
import dev.jaredhq.dashboardandroid.watch.music.WatchMusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WatchHealthViewModel] behaviour: mirrors engine connection state, registers/unregisters its UI
 * listener, tallies records per sync (resetting on each new run), and snapshots a finished sync.
 * The engine is faked; the viewModelScope runs on a [StandardTestDispatcher] installed as Main.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchHealthViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeEngine : WatchEngine {
        override var listener: WatchHealthListener? = null
        val conn = MutableStateFlow(WatchEngineConnectionState.DISCONNECTED)
        override val connectionState: StateFlow<WatchEngineConnectionState> = conn
        override val controlEvents =
            kotlinx.coroutines.flow.MutableSharedFlow<dev.jaredhq.dashboardandroid.watch.engine.WatchControlEvent>()
        override val musicControlEvents =
            kotlinx.coroutines.flow.MutableSharedFlow<dev.jaredhq.dashboardandroid.watch.engine.WatchMusicControlEvent>()
        val musicCaps = MutableStateFlow(WatchMusicCapabilities())
        override val musicCapabilities: StateFlow<WatchMusicCapabilities> = musicCaps
        override val watchMusicLibrary = MutableStateFlow(WatchMusicLibraryState())
        override val watchMusicTransfer = MutableStateFlow(WatchMusicTransferState())
        override val watchMusicLibraryMutation = MutableStateFlow(WatchMusicLibraryMutationState())
        var connectTarget: String? = null
        var disconnects = 0
        override fun init() {}
        override fun connect(macAddress: String) {
            connectTarget = macAddress
            conn.value = WatchEngineConnectionState.SCANNING
        }
        override fun disconnect() {
            disconnects++
            conn.value = WatchEngineConnectionState.DISCONNECTED
        }
        override fun isConnected(): Boolean =
            conn.value == WatchEngineConnectionState.CONNECTED ||
                conn.value == WatchEngineConnectionState.SYNCING
        override fun syncHealth() { conn.value = WatchEngineConnectionState.SYNCING }
    }

    private class FakeMusicController : WatchMusicController {
        val mutableState = MutableStateFlow(PhoneMusicState())
        override val state: StateFlow<PhoneMusicState> = mutableState
        override fun setEnabled(enabled: Boolean) {
            mutableState.value = mutableState.value.copy(enabled = enabled)
        }
        override fun start(notificationListener: android.content.ComponentName) {}
        override fun stop() {}
    }

    private fun vmWith(
        engine: FakeEngine,
        registered: Array<WatchHealthListener?> = arrayOf(null),
        uploadReg: Array<((WatchUploadOutcome) -> Unit)?> = arrayOf(null),
        musicController: FakeMusicController = FakeMusicController(),
    ) =
        WatchHealthViewModel(
            engine = engine,
            deviceId = "F4:91:29:51:C6:45",
            registerUiListener = { registered[0] = it },
            registerUploadListener = { uploadReg[0] = it },
            musicController = musicController,
        )

    private val workout = WatchWorkout(
        startDateTime = "2026-06-24 08:00:00", endDateTime = "2026-06-24 08:30:00", type = 1,
        durationSeconds = 1800, calories = 200, distanceMeters = 4000, steps = 5000,
        avgHeartRate = 130, maxHeartRate = 150, minHeartRate = 90, avgSpeed = null,
        maxSpeed = null, trainingEffect = null, vo2Max = null,
    )

    @Test
    fun mirrorsConnectionStateAndTargetsDeviceOnConnect() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = vmWith(engine)
        vm.onPermissionsGranted()

        vm.connect()
        advanceUntilIdle()

        assertEquals("F4:91:29:51:C6:45", engine.connectTarget)
        assertEquals(WatchEngineConnectionState.SCANNING, vm.state.value.connection)
    }

    @Test
    fun connectWithoutPermissionsDoesNotTouchEngine() = runTest(dispatcher) {
        val engine = FakeEngine()
        val vm = vmWith(engine)

        vm.connect()
        advanceUntilIdle()

        assertNull(engine.connectTarget)
        assertNotNull(vm.state.value.permissionRationale)
    }

    @Test
    fun talliesRecordsThenSnapshotsOnComplete() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        val listener = requireNotNull(registered[0]) { "VM should register a UI listener" }

        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()
        assertTrue(vm.state.value.syncing)

        listener.onWorkout(workout)
        listener.onWorkout(workout)
        listener.onSpo2Reading(WatchSpo2Reading("2026-06-24 08:05:00", percent = 97))
        assertEquals(3, vm.state.value.liveCounts.total)

        listener.onSyncComplete()
        val last = requireNotNull(vm.state.value.lastSync)
        assertTrue(last.succeeded)
        assertEquals(2, last.counts.workouts)
        assertEquals(1, last.counts.spo2)
        assertEquals(3, last.counts.total)
    }

    @Test
    fun liveCountsResetOnNextSyncButLastSyncRetained() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        val listener = requireNotNull(registered[0])

        // First sync delivers one record then completes.
        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()
        listener.onWorkout(workout)
        listener.onSyncComplete()
        assertEquals(1, vm.state.value.lastSync!!.counts.total)

        // Settle back to connected, then a second sync starts → live tally clears.
        engine.conn.value = WatchEngineConnectionState.CONNECTED
        advanceUntilIdle()
        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()

        assertEquals(0, vm.state.value.liveCounts.total)
        assertNotNull(vm.state.value.lastSync) // prior summary still shown
    }

    @Test
    fun failedSyncStillSnapshotsCounts() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        val listener = requireNotNull(registered[0])

        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()
        listener.onWorkout(workout)
        listener.onSyncFailed()

        val last = requireNotNull(vm.state.value.lastSync)
        assertFalse(last.succeeded)
        assertEquals(1, last.counts.total) // benign end-of-run failure must not lose the data
    }

    @Test
    fun bpAndStressCallbacksIncrementCountsAndTotal() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        val listener = requireNotNull(registered[0])

        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()

        listener.onBloodPressureReading(WatchBloodPressureReading("2026-06-24 10:00:00", 120, 80))
        listener.onStressReading(WatchStressReading("2026-06-24 10:01:00", 40))
        listener.onStressReading(WatchStressReading("2026-06-24 10:02:00", 42))

        val counts = vm.state.value.liveCounts
        assertEquals(1, counts.bloodPressure)
        assertEquals(2, counts.stress)
        assertEquals(3, counts.total) // BP + stress now contribute to the tally the screen shows
    }

    @Test
    fun uploadOutcomeSuccessAttachesToMostRecentLastSync() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val uploadReg = arrayOf<((WatchUploadOutcome) -> Unit)?>(null)
        val vm = vmWith(engine, registered, uploadReg)
        val listener = requireNotNull(registered[0])
        val reportUpload = requireNotNull(uploadReg[0]) { "VM should register an upload listener" }

        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()
        listener.onStressReading(WatchStressReading("2026-06-24 10:00:00", 40))
        listener.onSyncComplete()

        reportUpload(WatchUploadOutcome(succeeded = true, sentCount = 1, storedCount = 1, error = null))

        val status = requireNotNull(vm.state.value.lastSync?.upload)
        assertTrue(status.succeeded)
        assertEquals(1, status.sentCount)
        assertEquals(1, status.storedCount)
        assertNull(status.error)
    }

    @Test
    fun uploadOutcomeFailureAttachesWithError() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val uploadReg = arrayOf<((WatchUploadOutcome) -> Unit)?>(null)
        val vm = vmWith(engine, registered, uploadReg)
        val listener = requireNotNull(registered[0])
        val reportUpload = requireNotNull(uploadReg[0])

        engine.conn.value = WatchEngineConnectionState.SYNCING
        advanceUntilIdle()
        listener.onWorkout(workout)
        listener.onSyncComplete()

        reportUpload(WatchUploadOutcome(succeeded = false, sentCount = 5, storedCount = 0, error = "timeout"))

        val status = requireNotNull(vm.state.value.lastSync?.upload)
        assertFalse(status.succeeded)
        assertEquals("timeout", status.error)
        assertEquals(5, status.sentCount)
        assertEquals(0, status.storedCount)
    }

    @Test
    fun uploadOutcomeBeforeAnyLastSyncIsIgnored() = runTest(dispatcher) {
        val engine = FakeEngine()
        val uploadReg = arrayOf<((WatchUploadOutcome) -> Unit)?>(null)
        val vm = vmWith(engine, uploadReg = uploadReg)
        val reportUpload = requireNotNull(uploadReg[0])

        // No sync has finished yet, so there is no summary to attach to.
        assertNull(vm.state.value.lastSync)
        reportUpload(WatchUploadOutcome(succeeded = true, sentCount = 2, storedCount = 2))

        // The outcome is dropped rather than crashing or fabricating a summary.
        assertNull(vm.state.value.lastSync)
    }

    @Test
    fun musicToggleAndCapabilityStateFlowToUi() = runTest(dispatcher) {
        val engine = FakeEngine()
        val music = FakeMusicController()
        val vm = vmWith(engine, musicController = music)

        engine.musicCaps.value = WatchMusicCapabilities(
            known = true,
            phoneMusicControl = true,
            artistName = true,
            onboardMusic = true,
        )
        vm.setPhoneMusicEnabled(true)
        advanceUntilIdle()

        assertTrue(vm.state.value.phoneMusic.enabled)
        assertTrue(vm.state.value.musicCapabilities.phoneMusicControl)
        assertTrue(vm.state.value.musicCapabilities.onboardMusic)
    }

    @Test
    fun onClearedUnregistersUiListener() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        assertNotNull(registered[0])

        vm.viewModelClearedForTest()
        assertNull(registered[0])
    }

    @Test
    fun onClearedUnregistersUploadListener() = runTest(dispatcher) {
        val engine = FakeEngine()
        val uploadReg = arrayOf<((WatchUploadOutcome) -> Unit)?>(null)
        val vm = vmWith(engine, uploadReg = uploadReg)
        assertNotNull(uploadReg[0])

        vm.viewModelClearedForTest()
        assertNull(uploadReg[0])
    }
}

/** onCleared is protected; expose it for the test via an extension into the same module. */
private fun WatchHealthViewModel.viewModelClearedForTest() {
    val m = WatchHealthViewModel::class.java.getDeclaredMethod("onCleared")
    m.isAccessible = true
    m.invoke(this)
}
