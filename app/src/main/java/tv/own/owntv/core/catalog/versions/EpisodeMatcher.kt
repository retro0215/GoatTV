package tv.own.owntv.core.catalog.versions

import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.model.SourceType

/**
 * Which episode of another version of a series is "the same episode" — pure, so every rule is tested.
 *
 * Episodes are matched by season and episode number only. Provider episode ids are never compared:
 * two playlists number their rows independently, and the same id in two panels names unrelated files.
 *
 * Numbering is used only where it can be trusted. It is NOT trusted when:
 * - the episode number is 0 or less (no number at all), or the season is negative;
 * - two rows of the same version share a season and episode number (which one is meant is unknowable);
 * - the episode's own name names a different episode ("S01E05" on a row stored as 1x04);
 * - the app made the number up: an M3U entry with no SxxEyy pattern is numbered by its position and
 *   stored under the show's own name, and a Stalker season whose number the portal never stated is
 *   numbered by its position in the list (its row id then does not carry the season).
 *
 * Known gap: an Xtream panel that leaves `episode_num` blank gets positions filled in when the list is
 * read (XtreamClient.numberBlankEpisodes) and nothing records that afterwards; the name check above
 * catches it whenever episode names carry their number.
 */
object EpisodeMatcher {

    data class Key(val season: Int, val episode: Int)

    sealed interface Match {
        data class Found(val episode: EpisodeEntity) : Match
        /** The other version's numbering is trustworthy and has no such episode. */
        data object Missing : Match
        /** One side's numbering cannot be trusted for this episode; nothing is matched automatically. */
        data object Unmatched : Match
    }

    private val SXXEYY = Regex("(?i)\\bS(\\d{1,2})\\s*[._ -]?\\s*E(\\d{1,4})\\b")
    private val NXN = Regex("(?i)\\b(\\d{1,2})x(\\d{1,4})\\b")

    /** The season and episode an episode's own name states ("S01E05", "1x05"), or null when it states none. */
    fun numbersInName(name: String): Key? {
        val m = SXXEYY.find(name) ?: NXN.find(name) ?: return null
        return Key(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** Whether [episode]'s stored season and episode number are the provider's own (see the class notes). */
    fun hasTrustedNumber(episode: EpisodeEntity, sourceType: SourceType?, seriesName: String): Boolean {
        if (episode.episodeNumber <= 0 || episode.seasonNumber < 0) return false
        numbersInName(episode.name)?.let { stated ->
            if (stated != Key(episode.seasonNumber, episode.episodeNumber)) return false
        }
        return when (sourceType) {
            SourceType.STALKER -> stalkerSeasonStated(episode)
            // Position-numbered entries are stored under the show's own name; a real SxxEyy entry keeps
            // its episode title. An entry titled only by the show is therefore not trusted.
            SourceType.M3U -> !episode.name.trim().equals(seriesName.trim(), ignoreCase = true) || numbersInName(episode.name) != null
            SourceType.XTREAM -> true
            SourceType.LOCAL_BACKUP, null -> false
        }
    }

    /**
     * A Stalker episode's row id is `<season id>:<episode>`, and a classic portal's season id ends in
     * `:<season>` ("280:2"). The season is trusted only when that id states it; otherwise it may be the
     * season's position in the list, which another portal need not share.
     */
    private fun stalkerSeasonStated(episode: EpisodeEntity): Boolean {
        val parts = episode.remoteId?.split(':') ?: return false
        if (parts.size < 3) return false
        return parts[parts.size - 2].toIntOrNull() == episode.seasonNumber && parts.last().toIntOrNull() == episode.episodeNumber
    }

    /** The episodes of one version whose numbering can be trusted, by number. Duplicated numbers are left out. */
    fun trustedIndex(episodes: List<EpisodeEntity>, sourceType: SourceType?, seriesName: String): Map<Key, EpisodeEntity> =
        episodes
            .groupBy { Key(it.seasonNumber, it.episodeNumber) }
            .filterValues { rows -> rows.size == 1 && hasTrustedNumber(rows[0], sourceType, seriesName) }
            .mapValues { it.value[0] }

    /**
     * [from] (an episode of one version, indexed by [fromIndex]) in another version, whose rows are
     * [toEpisodes] indexed by [toIndex]. Missing only when the other version's numbering is trusted all
     * the way and simply has no such number; any doubt is [Match.Unmatched].
     */
    fun match(
        from: EpisodeEntity,
        fromIndex: Map<Key, EpisodeEntity>,
        toEpisodes: List<EpisodeEntity>,
        toIndex: Map<Key, EpisodeEntity>,
    ): Match {
        val key = Key(from.seasonNumber, from.episodeNumber)
        if (fromIndex[key]?.id != from.id) return Match.Unmatched
        toIndex[key]?.let { return Match.Found(it) }
        // Nothing in the other version can be trusted (or nothing loaded): no conclusion either way.
        if (toEpisodes.isEmpty() || toIndex.isEmpty()) return Match.Unmatched
        // An untrusted row in the same season may be this very episode under another number.
        val trustedIds = toIndex.values.mapTo(HashSet()) { it.id }
        val doubtInSeason = toEpisodes.any { it.seasonNumber == key.season && it.id !in trustedIds }
        return if (doubtInSeason) Match.Unmatched else Match.Missing
    }

    /** Where the series page lands after switching version: a season, the episode to focus, and whether the focused one was lost. */
    data class Carry(val season: Int, val episodeId: Long?, val episodeLost: Boolean)

    /**
     * Keep the customer's place across a version switch: the same episode when it can be matched, else
     * the same season when the other version has it, else its first season. [episodeLost] marks a
     * focused episode that could not be carried (missing there, or not safely matched).
     */
    fun carry(
        focused: EpisodeEntity?,
        selectedSeason: Int,
        fromIndex: Map<Key, EpisodeEntity>,
        toEpisodes: List<EpisodeEntity>,
        toIndex: Map<Key, EpisodeEntity>,
    ): Carry {
        if (focused != null) {
            val m = match(focused, fromIndex, toEpisodes, toIndex)
            if (m is Match.Found) return Carry(m.episode.seasonNumber, m.episode.id, episodeLost = false)
        }
        val seasons = toEpisodes.map { it.seasonNumber }.toSet()
        val season = if (selectedSeason in seasons) selectedSeason else seasons.minOrNull() ?: selectedSeason
        return Carry(season, null, episodeLost = focused != null)
    }
}
