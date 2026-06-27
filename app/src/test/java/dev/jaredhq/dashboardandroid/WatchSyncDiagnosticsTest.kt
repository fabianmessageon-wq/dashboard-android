package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.watch.engine.WatchSyncDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the per-sync raw-delivery tally that backs the Phase-2 metric support matrix. */
class WatchSyncDiagnosticsTest {

    @Test
    fun accumulatesAcrossMultipleRecordsPerMetric() {
        val d = WatchSyncDiagnostics()
        d.record(WatchSyncDiagnostics.BODY_ENERGY, parentRecords = 1, itemSamples = 200, mappedReadings = 200)
        d.record(WatchSyncDiagnostics.BODY_ENERGY, parentRecords = 1, itemSamples = 51, mappedReadings = 51)

        val tally = d.snapshot().getValue(WatchSyncDiagnostics.BODY_ENERGY)
        assertEquals(2, tally.parentRecords)
        assertEquals(251, tally.itemSamples)
        assertEquals(251, tally.mappedReadings)
    }

    @Test
    fun emptyByDefaultAndAfterReset() {
        val d = WatchSyncDiagnostics()
        assertTrue(d.isEmpty())
        assertEquals("no SDK records delivered", d.summary())

        d.record(WatchSyncDiagnostics.STRESS, parentRecords = 1, itemSamples = 24, mappedReadings = 24)
        assertFalse(d.isEmpty())

        d.reset()
        assertTrue(d.isEmpty())
        assertEquals("no SDK records delivered", d.summary())
    }

    @Test
    fun summaryListsMetricsInInsertionOrderWithCounts() {
        val d = WatchSyncDiagnostics()
        d.record(WatchSyncDiagnostics.BODY_ENERGY, parentRecords = 2, itemSamples = 251, mappedReadings = 251)
        d.record(WatchSyncDiagnostics.STRESS, parentRecords = 2, itemSamples = 24, mappedReadings = 24)
        d.record(WatchSyncDiagnostics.GPS_V3, parentRecords = 1) // delivered but dropped

        assertEquals(
            "body_energy(p=2,i=251,m=251); stress(p=2,i=24,m=24); gps_v3(p=1,i=0,m=0)",
            d.summary(),
        )
    }

    @Test
    fun droppedMetricsAreThoseDeliveredButNeverMapped() {
        val d = WatchSyncDiagnostics()
        d.record(WatchSyncDiagnostics.BODY_ENERGY, parentRecords = 1, itemSamples = 10, mappedReadings = 10)
        d.record(WatchSyncDiagnostics.GPS_V3, parentRecords = 1, itemSamples = 5, mappedReadings = 0)
        d.record(WatchSyncDiagnostics.ECG, parentRecords = 2, itemSamples = 0, mappedReadings = 0)

        assertEquals(
            listOf(WatchSyncDiagnostics.GPS_V3, WatchSyncDiagnostics.ECG),
            d.droppedMetrics(),
        )
    }

    @Test
    fun snapshotIsAnIndependentCopy() {
        val d = WatchSyncDiagnostics()
        d.record(WatchSyncDiagnostics.HRV, parentRecords = 1, itemSamples = 3, mappedReadings = 3)

        val snap = d.snapshot()
        d.record(WatchSyncDiagnostics.HRV, parentRecords = 1, itemSamples = 1, mappedReadings = 1)

        // The earlier snapshot must not see the later mutation.
        assertEquals(1, snap.getValue(WatchSyncDiagnostics.HRV).parentRecords)
        assertEquals(2, d.snapshot().getValue(WatchSyncDiagnostics.HRV).parentRecords)
    }
}
