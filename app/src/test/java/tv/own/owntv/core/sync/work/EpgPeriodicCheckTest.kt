package tv.own.owntv.core.sync.work

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.own.owntv.core.epg.EpgAutoRefreshPolicy

/** The periodic EPG check that keeps the guide fresh while the app is never closed. */
class EpgPeriodicCheckTest {

    private val spec = EpgSyncScheduler.periodicCheckRequest().workSpec

    @Test
    fun `runs about hourly, never more often`() {
        assertEquals(EpgAutoRefreshPolicy.CHECK_INTERVAL_MS, spec.intervalDuration)
        assertEquals(15L * 60 * 1000, spec.flexDuration)
    }

    @Test
    fun `waits for a network connection instead of failing offline`() {
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
    }

    @Test
    fun `runs the auto-refresh check worker and never reads as a sync in progress`() {
        assertEquals(EpgAutoRefreshWorker::class.java.name, spec.workerClassName)
        assertFalse(EpgSyncScheduler.periodicCheckRequest().tags.contains(EpgSyncScheduler.TAG_EPG_SYNC))
    }

    @Test
    fun `one unique schedule name, distinct from every per-source sync`() {
        assertEquals("epg-auto-refresh-check", EpgSyncScheduler.PERIODIC_CHECK_NAME)
        assertFalse(EpgSyncScheduler.PERIODIC_CHECK_NAME == EpgSyncScheduler.workName(-1L))
    }
}
