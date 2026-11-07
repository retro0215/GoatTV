package tv.own.owntv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.sync.work.EpgSyncState

/**
 * Re-sync EPG must never start (or, under WorkManager's REPLACE policy, restart) a sync that is already
 * running, and the Diagnostic Center must re-derive the live state purely from WorkManager — so leaving
 * and re-opening the screen (or the app) shows the sync that is still going.
 */
class EpgResyncTest {

    @Test
    fun `idle with sources - start`() {
        assertEquals(EpgResync.Decision.START, EpgResync.decide(sourceCount = 1, progress = null))
    }

    @Test
    fun `a running sync is never started again`() {
        val running = EpgResync.progressOf(listOf(EpgSyncState.Syncing(channels = 10, programmes = 40_000)))
        assertEquals(EpgResync.Decision.ALREADY_RUNNING, EpgResync.decide(1, running))
        // Second press while still syncing: still refused.
        assertEquals(EpgResync.Decision.ALREADY_RUNNING, EpgResync.decide(1, running))
    }

    @Test
    fun `no EPG sources - nothing to start`() {
        assertEquals(EpgResync.Decision.NO_SOURCES, EpgResync.decide(0, null))
    }

    @Test
    fun `state reattaches from WorkManager - any running source means in progress`() {
        assertNull(EpgResync.progressOf(emptyList()))
        assertNull(EpgResync.progressOf(listOf(EpgSyncState.Idle, EpgSyncState.Idle)))
        val mixed = EpgResync.progressOf(listOf(EpgSyncState.Idle, EpgSyncState.Syncing(programmes = 1_500)))
        assertEquals(EpgResync.Progress(programmes = 1_500), mixed)
    }

    @Test
    fun `progress is a real processed count, never invented - queued work reads as zero`() {
        // Enqueued (e.g. waiting for network) but not yet reporting: in progress with no count.
        assertEquals(EpgResync.Progress(0), EpgResync.progressOf(listOf(EpgSyncState.Syncing())))
        // The previous total (baseProgrammes) is not this run's progress.
        assertEquals(
            EpgResync.Progress(2_000),
            EpgResync.progressOf(listOf(EpgSyncState.Syncing(programmes = 2_000, baseProgrammes = 600_000))),
        )
    }

    @Test
    fun `sync completes - back to idle, a new start is allowed`() {
        assertEquals(EpgResync.Decision.START, EpgResync.decide(1, EpgResync.progressOf(listOf(EpgSyncState.Idle))))
    }
}
