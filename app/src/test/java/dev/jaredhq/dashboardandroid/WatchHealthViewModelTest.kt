package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.ui.watch.WatchHealthViewModel
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngine
import dev.jaredhq.dashboardandroid.watch.engine.WatchEngineConnectionState
import dev.jaredhq.dashboardandroid.watch.engine.WatchHealthListener
import dev.jaredhq.dashboardandroid.watch.engine.WatchSpo2Reading
import dev.jaredhq.dashboardandroid.watch.engine.WatchWorkout
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

    private fun vmWith(engine: FakeEngine, registered: Array<WatchHealthListener?> = arrayOf(null)) =
        WatchHealthViewModel(
            engine = engine,
            deviceId = "F4:91:29:51:C6:45",
            registerUiListener = { registered[0] = it },
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
    fun onClearedUnregistersUiListener() = runTest(dispatcher) {
        val engine = FakeEngine()
        val registered = arrayOf<WatchHealthListener?>(null)
        val vm = vmWith(engine, registered)
        assertNotNull(registered[0])

        vm.viewModelClearedForTest()
        assertNull(registered[0])
    }
}

/** onCleared is protected; expose it for the test via an extension into the same module. */
private fun WatchHealthViewModel.viewModelClearedForTest() {
    val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
    m.isAccessible = true
    m.invoke(this)
}
