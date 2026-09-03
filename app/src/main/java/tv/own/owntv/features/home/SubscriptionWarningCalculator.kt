package tv.own.owntv.features.home

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pure logic for the Home screen subscription warning (v34). Calculates remaining days from
 * the provider's expiry timestamp and returns the semantic UI state.
 */
object SubscriptionWarningCalculator {

    fun calculate(
        expiryMs: Long,
        todayMs: Long = System.currentTimeMillis()
    ): SubscriptionWarningState? {
        val dayMs = 86_400_000L
        val daysUntil = (expiryMs / dayMs) - (todayMs / dayMs)

        return when {
            daysUntil > 7 -> null
            daysUntil >= 2 -> SubscriptionWarningState.ExpiringInDays(daysUntil.toInt())
            daysUntil == 1L -> SubscriptionWarningState.ExpiringTomorrow
            daysUntil == 0L -> SubscriptionWarningState.ExpiringToday
            else -> SubscriptionWarningState.Expired
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    fun calculate(
        expiryMs: Long,
        today: LocalDate,
        zone: ZoneOffset
    ): SubscriptionWarningState? {
        val todayMs = today.atStartOfDay().toInstant(zone).toEpochMilli()
        return calculate(expiryMs, todayMs)
    }
}
