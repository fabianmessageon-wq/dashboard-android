package dev.jaredhq.dashboardandroid

import dev.jaredhq.dashboardandroid.watch.engine.MetricConfidence
import dev.jaredhq.dashboardandroid.watch.engine.WatchMetric
import dev.jaredhq.dashboardandroid.watch.engine.WatchSyncDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coverage for the durable metric-confidence ladder. */
class WatchMetricSupportTest {

    @Test
    fun mappedMetricEmittedOnRealSyncReachesShownInUi() {
        // Body energy: 251 samples on the real Active 4 Pro sync.
        assertEquals(
            MetricConfidence.SHOWN_IN_UI,
            WatchMetric.BODY_ENERGY.confidence(functionTableSupported = true, emittedOnRealSync = true),
        )
    }

    @Test
    fun functionTableSupportedButNotYetEmittedStopsAtFunctionTable() {
        // SpO2: watch advertises support, but this sync delivered none.
        assertEquals(
            MetricConfidence.FUNCTION_TABLE_SUPPORTED,
            WatchMetric.SPO2.confidence(functionTableSupported = true, emittedOnRealSync = false),
        )
    }

    @Test
    fun noFunctionTableAndNoEmissionIsSdkModelOnly() {
        assertEquals(
            MetricConfidence.SDK_MODEL_ONLY,
            WatchMetric.SPO2.confidence(functionTableSupported = null, emittedOnRealSync = false),
        )
        assertEquals(
            MetricConfidence.SDK_MODEL_ONLY,
            WatchMetric.SPO2.confidence(functionTableSupported = false, emittedOnRealSync = false),
        )
    }

    @Test
    fun droppedMetricCapsAtEmittedEvenWhenWatchSendsIt() {
        // GPS is delivered by the SDK but not uploaded / shown — so even emitted it can't climb past
        // EMITTED_ON_REAL_SYNC. This is the honest "the watch sent it but we drop it" state.
        assertEquals(
            MetricConfidence.EMITTED_ON_REAL_SYNC,
            WatchMetric.GPS.confidence(functionTableSupported = true, emittedOnRealSync = true),
        )
    }

    @Test
    fun emissionOutranksMissingFunctionTableFlag() {
        // Respiratory has no SupportFunctionInfo flag (null) but is proven by emission.
        assertEquals(
            MetricConfidence.SHOWN_IN_UI,
            WatchMetric.RESPIRATORY.confidence(functionTableSupported = null, emittedOnRealSync = true),
        )
    }

    @Test
    fun everyUploadedMetricIsAlsoShownInUi() {
        // Invariant the ladder relies on: uploaded ⇒ shownInUi for the mapped set.
        WatchMetric.entries.filter { it.uploaded }.forEach {
            assertTrue("${it.name} uploaded but not shownInUi", it.shownInUi)
        }
    }

    @Test
    fun everyMetricDiagnosticsKeyIsNonBlankAndUnique() {
        val keys = WatchMetric.entries.map { it.diagnosticsKey }
        assertTrue(keys.none { it.isBlank() })
        assertEquals(keys.size, keys.toSet().size)
        // Spot-check a couple resolve to the shared WatchSyncDiagnostics constants.
        assertEquals(WatchSyncDiagnostics.BODY_ENERGY, WatchMetric.BODY_ENERGY.diagnosticsKey)
        assertEquals(WatchSyncDiagnostics.STRESS, WatchMetric.STRESS.diagnosticsKey)
    }
}
