package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class SportsTimeLabelsTest {

    private val newYork = TimeZone.getTimeZone("America/New_York")
    private fun ms(iso: String) = SportsApiParser.parseIsoUtcMs(iso)!!

    @Test
    fun `day offsets are computed in the device zone, not UTC`() {
        val now = ms("2026-10-02T22:00:00Z") // 6:00 PM in New York, Oct 2
        assertEquals(0, SportsTimeLabels.dayOffset(ms("2026-10-03T02:00:00Z"), now, newYork)) // 10 PM Oct 2 local
        assertEquals(1, SportsTimeLabels.dayOffset(ms("2026-10-03T16:00:00Z"), now, newYork))
        assertEquals(-1, SportsTimeLabels.dayOffset(ms("2026-10-01T23:00:00Z"), now, newYork))
        assertEquals(3, SportsTimeLabels.dayOffset(ms("2026-10-05T17:00:00Z"), now, newYork))
    }

    @Test
    fun `DST transitions do not shift the day count`() {
        val now = ms("2026-10-31T16:00:00Z") // Sat Oct 31, EDT
        assertEquals(1, SportsTimeLabels.dayOffset(ms("2026-11-01T17:00:00Z"), now, newYork)) // Sun Nov 1, EST
        assertEquals(1, SportsTimeLabels.dayOffset(ms("2026-11-02T04:30:00Z"), now, newYork)) // 11:30 PM Sun Nov 1 EST (after fall-back)
        assertEquals(2, SportsTimeLabels.dayOffset(ms("2026-11-02T05:30:00Z"), now, newYork)) // 12:30 AM Mon Nov 2 EST
    }
}
