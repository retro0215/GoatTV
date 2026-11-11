package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient.XtAccountDetails
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.core.util.isTransientSyncError

/**
 * A panel that refuses the login answers every list call with an error object, which read as a complete,
 * empty list: the sync "succeeded" with nothing and the playlist was stamped synced. It must fail first,
 * as a wrong login or an expired account (upstream OwnTV_Core 38f30e67).
 */
class XtreamRejectedCredentialsTest {

    private val now = 1_767_225_600_000L // 2026-01-01 00:00 UTC
    private val day = 86_400_000L

    private fun refusal(details: XtAccountDetails): HttpClient.HttpStatusException =
        assertThrows(HttpClient.HttpStatusException::class.java) { XtreamSyncer.requireAccepted(details, now) }

    @Test
    fun `auth 0 fails the sync as a rejected login, not worth a retry`() {
        val e = refusal(XtAccountDetails(authenticated = false))
        assertEquals(401, e.code)
        assertEquals(FriendlySyncFailure.AuthenticationRejected, classifySyncFailure(e.message, online = true))
        assertFalse(isTransientSyncError(e.message, online = true))
    }

    @Test
    fun `auth 0 with an Expired status reports an expired account`() {
        val e = refusal(XtAccountDetails(authenticated = false, status = "Expired"))
        assertEquals(401, e.code)
        assertEquals(FriendlySyncFailure.AccountExpired, classifySyncFailure(e.message, online = true))
        assertFalse(isTransientSyncError(e.message, online = true))
    }

    @Test
    fun `auth 0 with an expiry date in the past reports an expired account`() {
        val e = refusal(XtAccountDetails(authenticated = false, status = "Active", expiryMs = now - day))
        assertEquals(FriendlySyncFailure.AccountExpired, classifySyncFailure(e.message, online = true))
    }

    @Test
    fun `auth 0 with a future expiry is a wrong login`() {
        val e = refusal(XtAccountDetails(authenticated = false, status = "Banned", expiryMs = now + 30 * day))
        assertEquals(FriendlySyncFailure.AuthenticationRejected, classifySyncFailure(e.message, online = true))
    }

    @Test
    fun `accepted, unanswered and unstated logins all go ahead`() {
        XtreamSyncer.requireAccepted(XtAccountDetails(authenticated = true), now)
        // A lapsed date alone is not a refusal: the lists decide (requireContentWhenLapsed).
        XtreamSyncer.requireAccepted(XtAccountDetails(authenticated = true, expiryMs = now - day), now)
        XtreamSyncer.requireAccepted(XtAccountDetails(authenticated = null), now)
        XtreamSyncer.requireAccepted(null, now)
    }

    // ---- 4.6.1: panels that accept an expired login (HTTP 200, no auth 0) and answer with empty lists ----

    private val all = SyncContentTypes(live = true, movies = true, series = true)
    private fun counts(live: Int?, movies: Int?, series: Int?) = buildMap {
        live?.let { put("channels", it) }; movies?.let { put("movies", it) }; series?.let { put("series", it) }
    }

    @Test
    fun `an explicit Expired status fails before any list is requested, even with auth 1`() {
        for (auth in listOf(true, null)) {
            val e = refusal(XtAccountDetails(authenticated = auth, status = "Expired", expiryMs = now - day))
            assertEquals(401, e.code)
            assertEquals(FriendlySyncFailure.AccountExpired, classifySyncFailure(e.message, online = true))
            assertFalse(isTransientSyncError(e.message, online = true))
        }
        // case-insensitive, and with no date at all
        refusal(XtAccountDetails(authenticated = true, status = "expired"))
    }

    @Test
    fun `an explicit Active status outranks a passed date`() {
        val d = XtAccountDetails(authenticated = true, status = "Active", expiryMs = now - day)
        XtreamSyncer.requireAccepted(d, now)
        assertFalse(XtreamSyncer.lapsedWithoutStatus(d, now))
    }

    @Test
    fun `a passed date with no status is lapsed, a future, missing or unreadable one is not`() {
        assertTrue(XtreamSyncer.lapsedWithoutStatus(XtAccountDetails(authenticated = true, expiryMs = now - day), now))
        assertTrue(XtreamSyncer.lapsedWithoutStatus(XtAccountDetails(authenticated = null, status = " ", expiryMs = now - day), now))
        assertFalse(XtreamSyncer.lapsedWithoutStatus(XtAccountDetails(authenticated = true, expiryMs = now + day), now))
        // missing, zero ("unlimited") and malformed exp_date are all parsed to null
        assertFalse(XtreamSyncer.lapsedWithoutStatus(XtAccountDetails(authenticated = true, expiryMs = null), now))
        assertFalse(XtreamSyncer.lapsedWithoutStatus(null, now))
        // auth 0 is handled as a refusal before this is ever asked
        assertFalse(XtreamSyncer.lapsedWithoutStatus(XtAccountDetails(authenticated = false, expiryMs = now - day), now))
    }

    @Test
    fun `lapsed with every list complete and empty fails as an expired account`() {
        val e = assertThrows(HttpClient.HttpStatusException::class.java) {
            XtreamSyncer.requireContentWhenLapsed(true, all, counts(0, 0, 0))
        }
        assertEquals(401, e.code)
        assertEquals("HTTP 401: Xtream account expired", e.message)
        assertEquals(FriendlySyncFailure.AccountExpired, classifySyncFailure(e.message, online = true))
    }

    @Test
    fun `lapsed with usable content keeps the sync`() {
        XtreamSyncer.requireContentWhenLapsed(true, all, counts(0, 12, 0))
        XtreamSyncer.requireContentWhenLapsed(true, all, counts(40_000, 265_000, 36_000))
    }

    @Test
    fun `a list that did not finish proves nothing, and a valid account is never checked`() {
        XtreamSyncer.requireContentWhenLapsed(true, all, counts(0, null, 0)) // movies phase failed
        XtreamSyncer.requireContentWhenLapsed(false, all, counts(0, 0, 0))   // date not passed
    }

    @Test
    fun `a live-only pass is judged on live alone`() {
        val live = SyncContentTypes(live = true, movies = false, series = false)
        assertThrows(HttpClient.HttpStatusException::class.java) {
            XtreamSyncer.requireContentWhenLapsed(true, live, counts(0, null, null))
        }
        XtreamSyncer.requireContentWhenLapsed(true, live, counts(3, null, null))
    }

    @Test
    fun `the refusal message carries no account details`() {
        val e = refusal(XtAccountDetails(authenticated = false, status = "Expired"))
        assertEquals("HTTP 401: Xtream account expired", e.message)
        val wrong = refusal(XtAccountDetails(authenticated = false))
        assertEquals("HTTP 401: panel rejected the username or password", wrong.message)
    }
}
