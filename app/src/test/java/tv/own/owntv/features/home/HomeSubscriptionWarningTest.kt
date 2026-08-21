package tv.own.owntv.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HomeSubscriptionWarningTest {

    private val today = LocalDate.of(2026, 8, 20)
    private val zone = ZoneOffset.UTC

    private fun expiryMs(daysFromToday: Int): Long {
        return today.plusDays(daysFromToday.toLong())
            .atStartOfDay()
            .toInstant(zone)
            .toEpochMilli()
    }

    @Test
    fun `hidden when 8 days remaining`() {
        assertNull(SubscriptionWarningCalculator.calculate(expiryMs(8), today, zone))
    }

    @Test
    fun `visible when 7 days remaining`() {
        val result = SubscriptionWarningCalculator.calculate(expiryMs(7), today, zone)
        assertEquals(SubscriptionWarningState.ExpiringInDays(7), result)
    }

    @Test
    fun `visible when 2 days remaining`() {
        val result = SubscriptionWarningCalculator.calculate(expiryMs(2), today, zone)
        assertEquals(SubscriptionWarningState.ExpiringInDays(2), result)
    }

    @Test
    fun `tomorrow warning when 1 day remaining`() {
        val result = SubscriptionWarningCalculator.calculate(expiryMs(1), today, zone)
        assertEquals(SubscriptionWarningState.ExpiringTomorrow, result)
    }

    @Test
    fun `today warning when 0 days remaining`() {
        val result = SubscriptionWarningCalculator.calculate(expiryMs(0), today, zone)
        assertEquals(SubscriptionWarningState.ExpiringToday, result)
    }

    @Test
    fun `expired warning when expired yesterday`() {
        val result = SubscriptionWarningCalculator.calculate(expiryMs(-1), today, zone)
        assertEquals(SubscriptionWarningState.Expired, result)
    }
}
