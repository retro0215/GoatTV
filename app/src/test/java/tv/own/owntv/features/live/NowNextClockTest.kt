package tv.own.owntv.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.parser.XtEpgEntry

/** Now / Next must move on at programme boundaries without polling. */
class NowNextClockTest {

    private val t = 1_800_000_000_000L
    private val min = 60_000L
    private fun e(start: Long, stop: Long, title: String = "x") = XtEpgEntry(title, null, start, stop)

    @Test fun `wakes when the current programme ends`() {
        val data = EpgNowNext(now = e(t - 30 * min, t + 10 * min), next = e(t + 10 * min, t + 40 * min))
        assertEquals(t + 10 * min, NowNextClock.nextChangeAt(data, t))
        assertEquals(10 * min + NowNextClock.SLACK_MS, NowNextClock.delayUntil(t + 10 * min, t))
    }

    @Test fun `in a guide gap, wakes when the next programme starts`() {
        val data = EpgNowNext(now = null, next = e(t + 5 * min, t + 35 * min))
        assertEquals(t + 5 * min, NowNextClock.nextChangeAt(data, t))
    }

    @Test fun `no guide means nothing to wait for`() {
        assertNull(NowNextClock.nextChangeAt(null, t))
        assertNull(NowNextClock.nextChangeAt(EpgNowNext(now = null, next = null), t))
    }

    @Test fun `boundaries already passed are not waited for again`() {
        val data = EpgNowNext(now = e(t - 60 * min, t - 1), next = e(t - 1, t + 30 * min))
        assertNull(NowNextClock.nextChangeAt(data, t))
    }

    @Test fun `a past boundary is never a negative sleep`() {
        assertEquals(NowNextClock.SLACK_MS, NowNextClock.delayUntil(t - 5_000, t))
    }

    @Test fun `cached now-next is stale once Now ends or Next begins`() {
        val data = EpgNowNext(now = e(t - 30 * min, t + 10 * min), next = e(t + 10 * min, t + 40 * min))
        assertFalse(NowNextClock.isStale(data, t))
        assertFalse(NowNextClock.isStale(data, t + 10 * min - 1))
        assertTrue(NowNextClock.isStale(data, t + 10 * min))
        assertTrue(NowNextClock.isStale(EpgNowNext(now = null, next = e(t, t + min)), t))
    }
}
