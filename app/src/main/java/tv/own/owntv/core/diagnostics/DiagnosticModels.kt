package tv.own.owntv.core.diagnostics

/**
 * Diagnostic Center (Settings → Diagnostic Center), Phase 1: local checks only. Nothing here is
 * uploaded anywhere, and none of these types can carry a credential, provider/server address, stream
 * URL, token or device identifier — they hold counts, flags and enums, which is what keeps the copied
 * report safe by construction (see [DiagnosticReport]).
 */
enum class DiagStatus { NOT_CHECKED, CHECKING, PASS, WARNING, FAILED }

/** The checks, in the order a full diagnostic runs them and the screen lists them. */
enum class DiagCheck { INTERNET, SERVICE, PLAYLIST, EPG, LIVE_TV, VOD, SPORTS, PLAYER, APP_VERSION, DEVICE }

enum class NetworkTransport { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

/** [latencyMs]: best round trip of the lightweight connectivity check (null when nothing answered). */
data class InternetFact(val reachable: Boolean, val transport: NetworkTransport, val latencyMs: Long? = null)

/** Which kind of playlist the service test talked to; never the address itself. */
enum class ServiceKind { XTREAM, STALKER, M3U, OTHER }

sealed interface ServiceFact {
    /** No playlist has been added yet, so there is no service to test. */
    data object NoSource : ServiceFact
    data class Ok(val kind: ServiceKind) : ServiceFact
    /** The provider answered but refused the account (wrong/expired login). */
    data class Rejected(val kind: ServiceKind) : ServiceFact
    /**
     * The provider could not be reached (DNS, timeout, refused, 5xx). [repeated] = it also failed a retry
     * a moment later, so it is not a one-off blip.
     */
    data class Unreachable(val kind: ServiceKind, val repeated: Boolean = false) : ServiceFact
}

data class PlaylistFact(
    val hasSource: Boolean,
    val channels: Int,
    val movies: Int,
    val series: Int,
    val liveEnabled: Boolean,
    val moviesEnabled: Boolean,
    val seriesEnabled: Boolean,
    /** Last complete sync stamped by the sync engine; null = never completed. */
    val lastSyncAtMs: Long?,
) {
    val total: Int get() = channels + movies + series

    companion object {
        val NONE = PlaylistFact(false, 0, 0, 0, liveEnabled = false, moviesEnabled = false, seriesEnabled = false, lastSyncAtMs = null)
    }
}

data class EpgFact(
    val sourceCount: Int,
    val programmes: Int,
    val guideChannels: Int,
    /** Newest successful EPG sync across sources; null = none recorded. */
    val lastSyncAtMs: Long?,
    /** Whether the most recent sync of any source ended in an error (the text itself is never kept). */
    val lastSyncFailed: Boolean,
    /** How old [lastSyncAtMs] was when the facts were collected; null = no successful sync recorded. */
    val lastSyncAgeMs: Long? = null,
)

/** The global "Movies & Series player". [exclusive] = no automatic hand-over to the other engine. */
enum class VodEngine { MPV, EXO }

data class PlayerFact(val vodEngine: VodEngine, val exclusive: Boolean)

sealed interface SportsFact {
    /** [gameCenterOk] is null when there was no event to ask about. */
    data class Connected(val events: Int, val gameCenterOk: Boolean?) : SportsFact
    data object Unavailable : SportsFact
}

sealed interface UpdateFact {
    data object UpToDate : UpdateFact
    data class Available(val version: String) : UpdateFact
    data object CheckFailed : UpdateFact
    /** An update download was already running, so no new check was started. */
    data object Busy : UpdateFact
}

enum class CodecSupport { SUPPORTED, NOT_REPORTED }

enum class DiagCodec { H264, HEVC, AV1, AAC, AC3, EAC3 }

data class DeviceFact(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val refreshRateHz: Float,
    val codecs: Map<DiagCodec, CodecSupport>,
)

data class AppFact(val brandName: String, val versionName: String, val versionCode: Long)

/** One row of the status list. Facts stay typed; wording is chosen by the screen and the report. */
data class CheckResult(val check: DiagCheck, val status: DiagStatus)

/** Everything one full diagnostic learned. A null fact = that check has not run (yet). */
data class DiagnosticFacts(
    val app: AppFact,
    val internet: InternetFact? = null,
    val service: ServiceFact? = null,
    val playlist: PlaylistFact? = null,
    val epg: EpgFact? = null,
    val sports: SportsFact? = null,
    val player: PlayerFact? = null,
    val update: UpdateFact? = null,
    val device: DeviceFact? = null,
    /** Result of a user-started speed test, if one was run (never run automatically). */
    val speed: SpeedTestResult? = null,
)

enum class Recommendation {
    RESTART_ROUTER,
    SLOW_CONNECTION,
    SERVICE_UNREACHABLE_VPN,
    CHECK_ACCOUNT,
    ADD_PLAYLIST,
    RESYNC_PLAYLIST,
    ADD_EPG,
    RESYNC_EPG,
    SPORTS_LATER,
    UPDATE_APP,
    FREE_STORAGE,
}

data class DiagnosticSummary(val issues: Int, val complete: Boolean) {
    val allPassed: Boolean get() = complete && issues == 0
}
