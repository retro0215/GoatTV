package tv.own.owntv.features.home

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure logic for the Home screen subscription warning (v34). Calculates remaining days from
 * the provider's expiry timestamp and returns the semantic UI state.
 */
object SubscriptionWarningCalculator {

    fun calculate(
        expiryMs: Long,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
        zone: ZoneId = ZoneId.systemDefault()
    ): SubscriptionWarningState? {
        // Use the same zone for both to avoid off-by-one errors near midnight.
        val expiryDate = Instant.ofEpochMilli(expiryMs).atZone(zone).toLocalDate()
        val daysUntil = ChronoUnit.DAYS.between(today, expiryDate)

        return when {
            daysUntil > 7 -> null
            daysUntil >= 2 -> SubscriptionWarningState.ExpiringInDays(daysUntil.toInt())
            daysUntil == 1L -> SubscriptionWarningState.ExpiringTomorrow
            daysUntil == 0L -> SubscriptionWarningState.ExpiringToday
            else -> SubscriptionWarningState.Expired
        }
    }
}
