package tv.own.owntv.core.sync

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.6.1 (Shield): a panel that keeps accepting an expired login answers every list with nothing. The
 * catalog-shrink guard only protects sources above PRUNE_MIN_ROWS and a force-clean sync bypasses it, so a
 * small or force-synced catalog would have been wiped by those empty lists. A pass whose account looks
 * lapsed ([SyncStatsCollector.holdPrune]) deletes nothing at all; every other pass prunes as before.
 */
class LapsedAccountPruneHoldTest {

    private val support = SyncSupport(
        categoryDao = mockk(relaxed = true),
        channelDao = mockk(relaxed = true),
        movieDao = mockk(relaxed = true),
        seriesDao = mockk(relaxed = true),
        sourceDao = mockk(relaxed = true),
        customize = mockk(relaxed = true),
        settings = mockk(relaxed = true),
    )

    /** Runs a prune of a source holding [stored] rows after a pass that saw [seen] of them; returns what was deleted. */
    private fun prune(stored: Int, seen: Int, force: Boolean, hold: Boolean): List<String> = runBlocking {
        val existing = (1..stored).map { "r$it" }
        val deleted = mutableListOf<String>()
        val stats = SyncStatsCollector(sourceId = 7).apply { forcePrune = force; holdPrune = hold }
        support.pruneRemoteIds(
            label = "MOVIES",
            sourceId = 7,
            seenRemoteIds = existing.take(seen).toSet(),
            stats = stats,
            loadExisting = { existing },
            deleteRemoteIds = { _, ids -> deleted += ids },
        )
        deleted
    }

    @Test
    fun `a lapsed account's empty pass deletes nothing from a small catalog`() {
        assertTrue(prune(stored = 40, seen = 0, force = false, hold = true).isEmpty())
        assertTrue(prune(stored = 100, seen = 0, force = false, hold = true).isEmpty())
    }

    @Test
    fun `a lapsed account's empty pass deletes nothing even when forced`() {
        assertTrue(prune(stored = 170_000, seen = 0, force = true, hold = true).isEmpty())
        assertTrue(prune(stored = 40, seen = 0, force = true, hold = true).isEmpty())
    }

    @Test
    fun `a lapsed account with content still keeps its stale rows until a normal pass`() {
        assertTrue(prune(stored = 1_000, seen = 990, force = false, hold = true).isEmpty())
    }

    @Test
    fun `without the hold, pruning is unchanged`() {
        assertEquals(40, prune(stored = 40, seen = 0, force = false, hold = false).size) // small source
        assertEquals(10, prune(stored = 1_000, seen = 990, force = false, hold = false).size) // normal delta
        assertEquals(170_000, prune(stored = 170_000, seen = 0, force = true, hold = false).size) // forced
        assertTrue(prune(stored = 170_000, seen = 0, force = false, hold = false).isEmpty()) // shrink guard
    }
}
