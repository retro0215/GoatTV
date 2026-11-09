package tv.own.owntv.core.util

/** Semantic failure categories. The presentation layer supplies the translated wording. */
sealed interface FriendlySyncFailure {
    data object Offline : FriendlySyncFailure
    data object Generic : FriendlySyncFailure
    data object Timeout : FriendlySyncFailure
    data object Unreachable : FriendlySyncFailure
    data object ConnectionFailed : FriendlySyncFailure
    data object StreamInterrupted : FriendlySyncFailure
    data object MacNotAuthorised : FriendlySyncFailure
    data object InvalidMac : FriendlySyncFailure
    data object PortalHandshakeFailed : FriendlySyncFailure
    data object PortalSessionExpired : FriendlySyncFailure
    data object AuthenticationRejected : FriendlySyncFailure
    /** The provider answered but refused the login because the subscription has expired. */
    data object AccountExpired : FriendlySyncFailure
    data object NotFound : FriendlySyncFailure
    data object ServerError : FriendlySyncFailure
    data object SecureConnectionFailed : FriendlySyncFailure
    data object MalformedGuide : FriendlySyncFailure
    data object PlaylistFileUnavailable : FriendlySyncFailure
    data object PlaylistPathUnsupported : FriendlySyncFailure
    data class Unknown(val rawMessage: String) : FriendlySyncFailure
}

/**
 * Classifies a raw exception / sync message without producing user-facing language. [online] lets
 * callers turn the common "everything failed" case into a semantic offline state instead of a
 * stack-trace-ish string.
 *
 * The English needles below are stable comparison keys emitted by OwnTV's own networking code;
 * translating either the throw sites or these needles would silently break classification.
 */
fun classifySyncFailure(raw: String?, online: Boolean): FriendlySyncFailure = when {
    !online -> FriendlySyncFailure.Offline
    raw.isNullOrBlank() -> FriendlySyncFailure.Generic
    raw.containsAny("timeout", "timed out") -> FriendlySyncFailure.Timeout
    raw.containsAny("Unable to resolve host", "UnknownHost", "No address associated") -> FriendlySyncFailure.Unreachable
    raw.containsAny("Failed to connect", "ECONNREFUSED", "Connection refused", "Connection reset") -> FriendlySyncFailure.ConnectionFailed
    // The last two are a download cut off midway: a gzip body ("…ZLIB…") or a plain one (OkHttp).
    raw.containsAny(
        "stream was reset", "PROTOCOL_ERROR", "StreamReset",
        "Unexpected end of ZLIB input stream", "unexpected end of stream",
    ) -> FriendlySyncFailure.StreamInterrupted
    raw.containsAny("MAC may not be authorized", "MAC not authorized", "Portal rejected", "no valid MAC") -> FriendlySyncFailure.MacNotAuthorised
    raw.containsAny("Portal handshake", "portal API endpoint") -> FriendlySyncFailure.PortalHandshakeFailed
    raw.containsAny("token may have expired", "returned no cmd") -> FriendlySyncFailure.PortalSessionExpired
    // Checked before the HTTP codes: an expired account is refused with a 401-shaped error too.
    raw.contains(XTREAM_ACCOUNT_EXPIRED, ignoreCase = true) -> FriendlySyncFailure.AccountExpired
    // 513 is not a standard status; some Xtream panels answer it, with an empty body, to every call made
    // with a username or password they do not accept.
    raw.containsAny("HTTP 401", "HTTP 403", "HTTP 513") -> FriendlySyncFailure.AuthenticationRejected
    raw.contains("HTTP 404", ignoreCase = true) -> FriendlySyncFailure.NotFound
    raw.containsAny("HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504") -> FriendlySyncFailure.ServerError
    raw.containsAny("CertPath", "SSLHandshake", "trust anchor", "CertificateException") -> FriendlySyncFailure.SecureConnectionFailed
    raw.containsAny("END_TAG", "START_TAG", "XmlPull", "PullParser", "ParserException") -> FriendlySyncFailure.MalformedGuide
    raw.contains("playlist_file_unavailable", ignoreCase = true) -> FriendlySyncFailure.PlaylistFileUnavailable
    raw.contains("playlist_path_unsupported", ignoreCase = true) -> FriendlySyncFailure.PlaylistPathUnsupported
    else -> FriendlySyncFailure.Unknown(raw)
}

/**
 * True when a sync failure looks transient (offline, network blip, server 5xx) — i.e. worth a
 * WorkManager [androidx.work.ListenableWorker.Result.retry] with backoff instead of a terminal failure.
 * Auth/URL problems (401/403/404, malformed data) are permanent: retrying them just hammers the panel.
 */
fun isTransientSyncError(raw: String?, online: Boolean): Boolean = when {
    !online -> true
    raw.isNullOrBlank() -> false
    else -> raw.containsAny(
        "timeout", "timed out",
        "Unable to resolve host", "UnknownHost", "No address associated",
        "Failed to connect", "ECONNREFUSED", "Connection refused", "Connection reset",
        "Software caused connection abort", "unexpected end of stream",
        // A gzip body cut off mid-download (a guide feed dropped by the server).
        "Unexpected end of ZLIB input stream",
        "stream was reset", "PROTOCOL_ERROR", "StreamReset",
        "HTTP 429", "HTTP 500", "HTTP 502", "HTTP 503", "HTTP 504",
    )
}

/**
 * Stable comparison key carried by the error a sync throws when an Xtream panel refuses the login of an
 * expired account (see `XtreamSyncer.requireAccepted`). Never shown to the user.
 */
const val XTREAM_ACCOUNT_EXPIRED = "Xtream account expired"

private fun String.containsAny(vararg needles: String): Boolean =
    needles.any { contains(it, ignoreCase = true) }
