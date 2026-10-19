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
