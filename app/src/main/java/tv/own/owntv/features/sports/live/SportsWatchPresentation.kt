package tv.own.owntv.features.sports.live

import androidx.compose.runtime.Immutable

/**
 * One Where to Watch entry as shown to the user: channel name, the network / category where useful
 * and a 4K/UHD tag. Deliberately carries NO implementation details (remote id, EPG id, confidence,
 * match reason, provider namespace, Sports channel id).
 */
@Immutable
data class SportsWatchRow(
    /** Local channel id: used only to act on the row, never displayed. */
    val localChannelId: Long,
    val name: String,
    /** Broadcast network for this feed when the event lists it ("ESPN"); null when unknown. */
    val network: String?,
    val category: String?,
    val uhd: Boolean,
    /** The feed the user already picked for this event this session. */
    val selected: Boolean,
)

/** Compact scoreboard drawn over event video for a few seconds (event data only — no Game Center I/O). */
@Immutable
data class SportsScoreboardOverlay(
    val awayLabel: String,
    val awayScore: String?,
    val homeLabel: String,
    val homeScore: String?,
    /** Live detail as sent ("3rd 4:32", "72'"); null when absent. */
    val detail: String?,
    val halftime: Boolean,
)

internal object SportsWatchPresentation {

    private val UHD = Regex("""\b(?:4k|uhd|2160p)\b""", RegexOption.IGNORE_CASE)

    fun isUhd(name: String): Boolean = UHD.containsMatchIn(name)

    /** Rows in backend rank order (the resolver preserved it; never re-sorted here). */
    fun rows(event: SportsEvent, channels: List<ResolvedSportsChannel>, selectedChannelId: Long?): List<SportsWatchRow> =
        channels.map { r ->
            val broadcast = r.ref.networkKey?.let { key ->
                event.broadcasts.firstOrNull { it.networkKey.equals(key, ignoreCase = true) }?.name
            }
            SportsWatchRow(
                localChannelId = r.channel.id,
                name = r.channel.name,
                network = broadcast,
                category = r.ref.category?.takeIf { it.isNotBlank() },
                uhd = isUhd(r.channel.name) || isUhd(r.ref.name),
                selected = r.channel.id == selectedChannelId,
            )
        }

    /** Null for non-team events (fight cards keep their own title) or before scores exist. */
    fun scoreboard(event: SportsEvent): SportsScoreboardOverlay? {
        val away = event.away ?: return null
        val home = event.home ?: return null
        val status = SportsEventPresentation.status(event) as? SportsEventPresentation.Status.Live
        return SportsScoreboardOverlay(
            awayLabel = away.abbreviation ?: away.shortName ?: away.name,
            awayScore = away.score.takeIf { event.showsScores },
            homeLabel = home.abbreviation ?: home.shortName ?: home.name,
            homeScore = home.score.takeIf { event.showsScores },
            detail = status?.detail,
            halftime = status?.halftime == true,
        )
    }
}
