package tv.own.owntv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tv.own.owntv.R
import tv.own.owntv.core.util.FriendlySyncFailure

/** Resolve a classified sync failure only at the Compose presentation boundary. */
@Composable
fun FriendlySyncFailure.displayText(): String = when (this) {
    FriendlySyncFailure.Offline -> stringResource(R.string.sync_error_offline)
    FriendlySyncFailure.Generic -> stringResource(R.string.sync_error_generic)
    FriendlySyncFailure.Timeout -> stringResource(R.string.sync_error_timeout)
    FriendlySyncFailure.Unreachable -> stringResource(R.string.sync_error_unreachable)
    FriendlySyncFailure.ConnectionFailed -> stringResource(R.string.sync_error_connection)
    FriendlySyncFailure.StreamInterrupted -> stringResource(R.string.sync_error_interrupted)
    FriendlySyncFailure.MacNotAuthorised -> stringResource(R.string.sync_error_mac)
    FriendlySyncFailure.InvalidMac -> stringResource(R.string.sync_error_invalid_mac)
    FriendlySyncFailure.PortalHandshakeFailed -> stringResource(R.string.sync_error_portal)
    FriendlySyncFailure.PortalSessionExpired -> stringResource(R.string.sync_error_session)
    FriendlySyncFailure.AuthenticationRejected -> stringResource(R.string.sync_error_auth)
    FriendlySyncFailure.AccountExpired -> stringResource(R.string.sync_error_account_expired)
    FriendlySyncFailure.NotFound -> stringResource(R.string.sync_error_not_found)
    FriendlySyncFailure.ServerError -> stringResource(R.string.sync_error_server)
    FriendlySyncFailure.SecureConnectionFailed -> stringResource(R.string.sync_error_secure)
    FriendlySyncFailure.MalformedGuide -> stringResource(R.string.sync_error_malformed_guide)
    FriendlySyncFailure.PlaylistFileUnavailable -> stringResource(R.string.setup_playlist_file_unavailable)
    FriendlySyncFailure.PlaylistPathUnsupported -> stringResource(R.string.setup_playlist_path_unsupported)
    is FriendlySyncFailure.Unknown -> rawMessage
}
