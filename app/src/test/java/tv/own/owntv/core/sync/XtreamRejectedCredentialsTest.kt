package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
        // An accepted login with a lapsed date is left to the Home warning, as before.
        XtreamSyncer.requireAccepted(XtAccountDetails(authenticated = true, status = "Expired", expiryMs = now - day), now)
        XtreamSyncer.requireAccepted(XtAccountDetails(authenticated = null), now)
        XtreamSyncer.requireAccepted(null, now)
    }

    @Test
    fun `the refusal message carries no account details`() {
        val e = refusal(XtAccountDetails(authenticated = false, status = "Expired"))
        assertEquals("HTTP 401: Xtream account expired", e.message)
        val wrong = refusal(XtAccountDetails(authenticated = false))
        assertEquals("HTTP 401: panel rejected the username or password", wrong.message)
    }
}
