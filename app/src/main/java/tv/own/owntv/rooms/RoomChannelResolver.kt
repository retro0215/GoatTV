package tv.own.owntv.rooms

import kotlinx.coroutines.flow.first
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.settings.data.SettingsRepository

sealed interface ChannelResolutionResult {
    data class Resolved(val channel: ChannelEntity) : ChannelResolutionResult
    object Unavailable : ChannelResolutionResult
    object Ambiguous : ChannelResolutionResult
}

class RoomChannelResolver(
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao? = null,
    private val settingsRepository: SettingsRepository? = null
) {
    suspend fun resolve(channelRef: RoomChannelReference?): ChannelResolutionResult {
        if (channelRef == null) return ChannelResolutionResult.Unavailable

        suspend fun disambiguate(matches: List<ChannelEntity>): ChannelResolutionResult {
            if (matches.isEmpty()) return ChannelResolutionResult.Unavailable
            if (matches.size == 1) return ChannelResolutionResult.Resolved(matches.first())

            val activeSourceId = runCatching {
                settingsRepository?.defaultSourceId?.first()
            }.getOrNull()?.takeIf { it > 0 } ?: runCatching {
                val profileId = settingsRepository?.activeProfileIdNow() ?: -1L
                if (profileId >= 0 && sourceDao != null) {
                    sourceDao.observeForProfile(profileId).first().firstOrNull()?.id
                } else null
            }.getOrNull()

            if (activeSourceId != null) {
                val activeMatches = matches.filter { it.sourceId == activeSourceId }
                if (activeMatches.size == 1) {
                    return ChannelResolutionResult.Resolved(activeMatches.first())
                }
            }

            return ChannelResolutionResult.Ambiguous
        }

        // 1. remoteId exact match
        if (!channelRef.remoteId.isNullOrBlank()) {
            val matches = channelDao.findByRemoteId(channelRef.remoteId)
            if (matches.isNotEmpty()) {
                return disambiguate(matches)
            }
        }

        // 2. epgChannelId exact match
        if (!channelRef.epgChannelId.isNullOrBlank()) {
            val matches = channelDao.findByEpgChannelId(channelRef.epgChannelId)
            if (matches.isNotEmpty()) {
                return disambiguate(matches)
            }
        }

        // 3. streamUrl exact match
        if (!channelRef.streamUrl.isNullOrBlank()) {
            val matches = channelDao.findByStreamUrl(channelRef.streamUrl)
            if (matches.isNotEmpty()) {
                return disambiguate(matches)
            }
        }

        // 4. normalized channelName fallback
        if (!channelRef.channelName.isBlank()) {
            val matches = channelDao.findByNameCaseInsensitive(channelRef.channelName.trim())
            if (matches.isNotEmpty()) {
                return disambiguate(matches)
            }
        }

        return ChannelResolutionResult.Unavailable
    }
}
