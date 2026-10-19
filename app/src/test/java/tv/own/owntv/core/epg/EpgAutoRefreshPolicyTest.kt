package tv.own.owntv.core.epg

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.settings.data.EpgAutoRefresh
import java.util.concurrent.atomic.AtomicInteger

/**
 * The provider guide on a customer's box went 10 days without refreshing: onboarding registers it with no
 * auto-refresh choice stored, and a missing choice meant Off.
 */
class EpgAutoRefreshPolicyTest {

    private val hour = 3_600_000L
    private val now = 1_000L * 24 * hour

    @Test
    fun `a source with no stored choice refreshes every 24 hours`() {
        assertEquals(EpgAutoRefresh.HOURS_24, EpgAutoRefreshPolicy.effective(emptyMap(), -1L))
        assertEquals(EpgAutoRefresh.HOURS_24, EpgAutoRefreshPolicy.effective(mapOf(-2L to EpgAutoRefresh.OFF), -1L))
    }

    @Test
    fun `an explicit Off and custom intervals are kept`() {
        val stored = mapOf(-1L to EpgAutoRefresh.OFF, -2L to EpgAutoRefresh.HOURS_6, -3L to EpgAutoRefresh.STARTUP)
        assertEquals(EpgAutoRefresh.OFF, EpgAutoRefreshPolicy.effective(stored, -1L))
        assertEquals(EpgAutoRefresh.HOURS_6, EpgAutoRefreshPolicy.effective(stored, -2L))
        assertEquals(EpgAutoRefresh.STARTUP, EpgAutoRefreshPolicy.effective(stored, -3L))
    }

    @Test
    fun `the 10-day-old guide is due, a guide refreshed this morning is not`() {
        val mode = EpgAutoRefreshPolicy.effective(emptyMap(), -1L)
        assertTrue(EpgAutoRefreshPolicy.isDue(mode, now - 10 * 24 * hour, now, coldStart = false))
        assertFalse(EpgAutoRefreshPolicy.isDue(mode, now - 5 * hour, now, coldStart = true))
        assertTrue(EpgAutoRefreshPolicy.isDue(mode, now - 24 * hour, now, coldStart = false))
    }

    @Test
    fun `never synced is due, Off never is`() {
        assertTrue(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.HOURS_24, null, now, coldStart = false))
        assertFalse(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.OFF, null, now, coldStart = true))
        assertFalse(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.OFF, now - 100 * 24 * hour, now, coldStart = true))
    }

    @Test
    fun `startup mode only on a cold start`() {
        assertTrue(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.STARTUP, now - hour, now, coldStart = true))
        assertFalse(EpgAutoRefreshPolicy.isDue(EpgAutoRefresh.STARTUP, now - 100 * hour, now, coldStart = false))
    }

    @Test
    fun `an automatic run skips a source refreshed while it waited`() {
        assertFalse(EpgAutoRefreshPolicy.stillNeeded(now - 60_000L, now))
        assertTrue(EpgAutoRefreshPolicy.stillNeeded(now - 25 * hour, now))
        assertTrue(EpgAutoRefreshPolicy.stillNeeded(null, now))
    }

    @Test
    fun `a failing source is attempted at most once an hour per source`() {
        val spacing = EpgAutoRefreshPolicy.AttemptSpacing()
        assertTrue(spacing.tryAcquire(-1L, 0L))
        assertFalse("every return to the app must not re-download", spacing.tryAcquire(-1L, 5 * 60_000L))
        assertTrue("other sources are independent", spacing.tryAcquire(-2L, 5 * 60_000L))
        assertTrue(spacing.tryAcquire(-1L, hour))
    }

    // ---- Continuous refresh: the periodic check runs about hourly while the app stays open. ----

    /** Runs the check every [periodMs] for [hours] (clock-controlled) and returns when each source was enqueued. */
    private fun simulateAppLeftOpen(
        sources: List<EpgAutoRefreshPolicy.SourceState>,
        stored: Map<Long, EpgAutoRefresh>,
        hours: Int,
        periodMs: Long = EpgAutoRefreshPolicy.CHECK_INTERVAL_MS,
        syncSucceeds: Boolean = true,
    ): Map<Long, List<Long>> {
        val spacing = EpgAutoRefreshPolicy.AttemptSpacing()
        val last = sources.associate { it.id to it.lastSyncAt }.toMutableMap()
        val enqueued = sources.associate { it.id to mutableListOf<Long>() }
        var t = 0L
        while (t <= hours * hour) {
            val state = last.map { (id, at) -> EpgAutoRefreshPolicy.SourceState(id, at) }
            for (id in EpgAutoRefreshPolicy.dueSources(state, stored, now + t, coldStart = false, spacing, elapsedMs = t)) {
                enqueued.getValue(id) += t
                if (syncSucceeds) last[id] = now + t
            }
            t += periodMs
        }
        return enqueued
    }

    @Test
    fun `an unset source refreshes after 24 hours with the app never closed`() {
        val runs = simulateAppLeftOpen(listOf(EpgAutoRefreshPolicy.SourceState(-1L, now)), emptyMap(), hours = 72)
        assertEquals(listOf(24 * hour, 48 * hour, 72 * hour), runs.getValue(-1L))
    }

    @Test
    fun `a stale guide refreshes on the first check, then daily`() {
        val runs = simulateAppLeftOpen(listOf(EpgAutoRefreshPolicy.SourceState(-1L, now - 10 * 24 * hour)), emptyMap(), hours = 30)
        assertEquals(listOf(0L, 24 * hour), runs.getValue(-1L))
    }

    @Test
    fun `explicit Off never refreshes and custom intervals keep their period while open`() {
        val stored = mapOf(-1L to EpgAutoRefresh.OFF, -2L to EpgAutoRefresh.HOURS_6, -3L to EpgAutoRefresh.HOURS_48)
        val sources = listOf(-1L, -2L, -3L).map { EpgAutoRefreshPolicy.SourceState(it, now) }
        val runs = simulateAppLeftOpen(sources, stored, hours = 48)
        assertEquals(emptyList<Long>(), runs.getValue(-1L))
        assertEquals((1..8).map { it * 6 * hour }, runs.getValue(-2L))
        assertEquals(listOf(48 * hour), runs.getValue(-3L))
    }

    @Test
    fun `a periodic run arriving a little early does not skip a failing source for two hours`() {
        val runs = simulateAppLeftOpen(
            listOf(EpgAutoRefreshPolicy.SourceState(-1L, null)), emptyMap(), hours = 4,
            periodMs = 55 * 60_000L, syncSucceeds = false,
        )
        assertEquals("a failing feed is retried about hourly, never more", 5, runs.getValue(-1L).size)
    }

    @Test
    fun `resume right after a periodic check does not enqueue the same source twice`() {
        val spacing = EpgAutoRefreshPolicy.AttemptSpacing()
        val stale = listOf(EpgAutoRefreshPolicy.SourceState(-1L, now - 30 * hour))
        assertEquals(listOf(-1L), EpgAutoRefreshPolicy.dueSources(stale, emptyMap(), now, false, spacing, 0L))
        assertEquals(emptyList<Long>(), EpgAutoRefreshPolicy.dueSources(stale, emptyMap(), now + 60_000L, false, spacing, 60_000L))
    }

    @Test
    fun `after an app restart only persisted state decides - a fresh guide is not re-downloaded`() {
        val restarted = EpgAutoRefreshPolicy.AttemptSpacing() // a new process remembers no attempts
        val stored = mapOf(-2L to EpgAutoRefresh.OFF) // persisted choices survive the restart
        val sources = listOf(EpgAutoRefreshPolicy.SourceState(-1L, now - 2 * hour), EpgAutoRefreshPolicy.SourceState(-2L, now - 30 * hour))
        assertEquals(emptyList<Long>(), EpgAutoRefreshPolicy.dueSources(sources, stored, now, coldStart = true, restarted, 0L))
    }

    @Test
    fun `failed refresh - network errors retry a bounded number of times, other errors do not`() {
        assertTrue(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 0))
        assertTrue(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 2))
        assertFalse(EpgAutoRefreshPolicy.shouldRetry(networkError = true, runAttemptCount = 3))
        assertFalse(EpgAutoRefreshPolicy.shouldRetry(networkError = false, runAttemptCount = 0))
    }

    @Test
    fun `imports never overlap`() = runBlocking(Dispatchers.Default) {
        val gate = EpgImportGate()
        val running = AtomicInteger(0)
        var maxConcurrent = 0
        val jobs = (1..4).map {
            async {
                gate.exclusive {
                    val n = running.incrementAndGet()
                    synchronized(this@EpgAutoRefreshPolicyTest) { maxConcurrent = maxOf(maxConcurrent, n) }
                    delay(30)
                    running.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }
        assertEquals(1, maxConcurrent)
    }

    @Test
    fun `a queued automatic import does nothing when the guide was refreshed meanwhile`() = runBlocking {
        val gate = EpgImportGate()
        var lastSyncAt: Long? = now - 30 * hour
        gate.exclusive { lastSyncAt = now } // e.g. onboarding's import finishing first
        val result = gate.exclusiveIfStillNeeded({ EpgAutoRefreshPolicy.stillNeeded(lastSyncAt, now + 1_000L) }) { 593_031 }
        assertNull(result)
        val due = gate.exclusiveIfStillNeeded({ EpgAutoRefreshPolicy.stillNeeded(now - 30 * hour, now) }) { 593_031 }
        assertEquals(593_031, due)
    }
}
