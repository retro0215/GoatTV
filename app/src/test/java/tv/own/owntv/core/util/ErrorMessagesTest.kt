package tv.own.owntv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMessagesTest {
    @Test
    fun `known host resolution failure is classified for localized presentation`() {
        assertEquals(
            FriendlySyncFailure.Unreachable,
            classifySyncFailure("Unable to resolve host example.test", online = true),
        )
    }

    /** A guide download whose gzip (or plain body) was cut off is a dropped connection: told so, and retried. */
    @Test
    fun `a cut-off download is an interrupted download, worth a retry`() {
        for (raw in listOf("Unexpected end of ZLIB input stream", "unexpected end of stream")) {
            assertEquals(FriendlySyncFailure.StreamInterrupted, classifySyncFailure(raw, online = true))
            assertEquals(true, isTransientSyncError(raw, online = true))
        }
    }

    @Test
    fun `HTTP 513 is how some panels refuse a login`() {
        val raw = "HTTP 513 for http://panel.invalid/player_api.php"
        assertEquals(FriendlySyncFailure.AuthenticationRejected, classifySyncFailure(raw, online = true))
        assertEquals(false, isTransientSyncError(raw, online = true))
    }

    @Test
    fun `an expired account wins over the 401 it is shaped as`() {
        val raw = "HTTP 401: $XTREAM_ACCOUNT_EXPIRED"
        assertEquals(FriendlySyncFailure.AccountExpired, classifySyncFailure(raw, online = true))
        assertEquals(false, isTransientSyncError(raw, online = true))
    }

    @Test
    fun `unmapped external failure preserves its raw text`() {
        val raw = "Provider-specific failure code X17"

        assertEquals(
            FriendlySyncFailure.Unknown(raw),
            classifySyncFailure(raw, online = true),
        )
    }
}
