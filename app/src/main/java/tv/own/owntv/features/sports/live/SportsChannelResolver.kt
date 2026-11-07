package tv.own.owntv.features.sports.live

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.features.settings.data.SettingsRepository

/**
 * Resolves backend-matched Sports channels ([SportsChannelRef]) to the user's LOCAL channels.
 *
 * Contract (Phase C):
 *  1. Only the local ACTIVE source, and only if it is an Xtream source — M3U and Stalker sources are
 *     never eligible (their channel ids are not the provider stream ids the backend matched).
 *  2. Lookup is exactly `ChannelEntity(sourceId = active, remoteId = ref.remoteId)`.
 *  3. Identity is verified before acceptance: if the ref has an EPG id, the local EPG id must agree;
 *     otherwise the normalized channel names must agree. Any mismatch → the channel is rejected.
 *  4. There is NO fallback: no global name search, no other source, no backend URL. Playback keeps
 *     being built locally from the user's own authenticated source (the returned [ChannelEntity]).
 *
 * Backend ranking is preserved (primary feeds first, accepted 4K/UHD alternates after).
 */
class SportsChannelResolver(private val lookup: SportsChannelLookup) {

    suspend fun resolve(refs: List<SportsChannelRef>): SportsChannelResolution {
        if (refs.isEmpty()) return SportsChannelResolution(emptyList(), emptyList())
        val source = lookup.activeSource()
        if (source == null || source.type != SourceType.XTREAM) {
            return SportsChannelResolution(emptyList(), refs.map { RejectedSportsChannel(it, RejectReason.NO_ELIGIBLE_SOURCE) })
        }
        val accepted = ArrayList<ResolvedSportsChannel>()
        val rejected = ArrayList<RejectedSportsChannel>()
        val seenLocal = HashSet<Long>()
        for (ref in refs) {
            val remoteId = ref.remoteId.trim()
            if (remoteId.isEmpty()) { rejected += RejectedSportsChannel(ref, RejectReason.NOT_FOUND); continue }
            val local = lookup.findByRemote(source.id, remoteId)
            when {
                local == null -> rejected += RejectedSportsChannel(ref, RejectReason.NOT_FOUND)
                local.sourceId != source.id -> rejected += RejectedSportsChannel(ref, RejectReason.WRONG_SOURCE)
                !identityAgrees(ref, local) -> rejected += RejectedSportsChannel(ref, RejectReason.IDENTITY_MISMATCH)
                !seenLocal.add(local.id) -> Unit // the same local channel twice: keep the higher-ranked one
                else -> accepted += ResolvedSportsChannel(ref, local)
            }
        }
        return SportsChannelResolution(accepted, rejected)
    }

    companion object {
        /** EPG agreement when the backend knows the EPG id; otherwise normalized name agreement. */
        fun identityAgrees(ref: SportsChannelRef, local: ChannelEntity): Boolean {
            val refEpg = ref.epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
            return if (refEpg != null) {
                val localEpg = local.epgChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
                refEpg.equals(localEpg, ignoreCase = true)
            } else {
                val a = normalizeChannelName(ref.name)
                a.isNotEmpty() && a == normalizeChannelName(local.name)
            }
        }

        private val COUNTRY_PREFIX = Regex("""^\s*(?:\[\s*[a-z]{2,3}\s*]|\(\s*[a-z]{2,3}\s*\)|[a-z]{2,3})\s*[:|]\s*""", RegexOption.IGNORE_CASE)
        private val QUALITY = Regex("""\b(?:hd|fhd|sd|hevc|h26[45]|1080[pi]?|720p|60\s*fps|50\s*fps)\b""", RegexOption.IGNORE_CASE)

        /**
         * Conservative name normalization for the no-EPG case: case, punctuation, a leading country/
         * region tag ("US:", "USA |") and plain quality tags are ignored. 4K/UHD is kept, so a 4K feed
         * never "agrees" with the normal feed.
         */
        fun normalizeChannelName(raw: String): String {
            var s = raw
            repeat(2) { s = s.replace(COUNTRY_PREFIX, "") }
            return s.replace(QUALITY, " ")
                .lowercase()
                .replace("&", " and ")
                .replace("+", " plus ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
        }
    }
}

/** Where the resolver reads local state from (a seam for tests). */
interface SportsChannelLookup {
    /** The user's ACTIVE source for the active profile, or null when none/ambiguous. */
    suspend fun activeSource(): SourceEntity?

    suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity?
}

/**
 * Production lookup: the profile's default source; when no default is set, the profile's only Xtream
 * source (several Xtream sources without a default is ambiguous → null, nothing resolves).
 */
class RoomSportsChannelLookup(
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val channelDao: ChannelDao,
) : SportsChannelLookup {
    override suspend fun activeSource(): SourceEntity? {
        val profileId = settings.activeProfileId.first()
        val sources = sourceRepository.observeSources(profileId).first()
        val defaultId = settings.defaultSourceId.first()
        if (defaultId > 0) return sources.firstOrNull { it.id == defaultId }
        return sources.filter { it.type == SourceType.XTREAM }.singleOrNull()
    }

    override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? =
        channelDao.findByRemote(sourceId, remoteId)
}

data class ResolvedSportsChannel(val ref: SportsChannelRef, val channel: ChannelEntity)

data class RejectedSportsChannel(val ref: SportsChannelRef, val reason: RejectReason)

enum class RejectReason { NO_ELIGIBLE_SOURCE, NOT_FOUND, WRONG_SOURCE, IDENTITY_MISMATCH }

data class SportsChannelResolution(
    val accepted: List<ResolvedSportsChannel>,
    val rejected: List<RejectedSportsChannel>,
)

/** What OK on an event may do once channels are enabled (Phase C2). */
sealed interface SportsWatchAction {
    /** 0 playable channels: no Watch / Select Channel action at all. */
    data object None : SportsWatchAction

    /** Exactly 1: OK / Watch may tune it directly. */
    data class Direct(val channel: ResolvedSportsChannel) : SportsWatchAction

    /** 2+: open Select Channel, in backend rank order. */
    data class Select(val channels: List<ResolvedSportsChannel>) : SportsWatchAction

    companion object {
        fun of(resolved: List<ResolvedSportsChannel>, featureEnabled: Boolean = SportsChannelFeature.ENABLED): SportsWatchAction = when {
            !featureEnabled || resolved.isEmpty() -> None
            resolved.size == 1 -> Direct(resolved.single())
            else -> Select(resolved)
        }
    }
}
