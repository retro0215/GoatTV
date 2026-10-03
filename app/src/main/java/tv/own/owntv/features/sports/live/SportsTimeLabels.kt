package tv.own.owntv.features.sports.live

import java.util.Calendar
import java.util.TimeZone

/** Pure date helpers for event cards (unit-tested; no java.time on minSdk 24). */
internal object SportsTimeLabels {

    /** Calendar-day difference between [startMs] and [nowMs] in [zone]: 0 today, 1 tomorrow, -1 yesterday… */
    fun dayOffset(startMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val a = Calendar.getInstance(zone).apply { timeInMillis = nowMs; truncateToDay() }
        val b = Calendar.getInstance(zone).apply { timeInMillis = startMs; truncateToDay() }
        // Count whole days between local midnights (DST-safe: compare calendar fields, not millis/24h).
        var offset = 0
        val step = if (b.before(a)) -1 else 1
        while (a.get(Calendar.YEAR) != b.get(Calendar.YEAR) || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR)) {
            a.add(Calendar.DAY_OF_YEAR, step)
            offset += step
            if (offset > 400 || offset < -400) break
        }
        return offset
    }

    private fun Calendar.truncateToDay() {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
}
