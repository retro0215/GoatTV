package tv.own.owntv.rooms

import android.util.Log
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class SupabaseRoomRepository : RoomRepository {
    private val client = SupabaseClientProvider.client

    override suspend fun fetchRoomsForBrand(brandId: String): RoomResult<List<SocialRoom>> {
        return runCatching {
            val dtos = client.postgrest["rooms"]
                .select {
                    filter { eq("brand_id", brandId) }
                }
                .decodeList<RoomDto>()
            RoomResult.Success(dtos.map { it.toDomain() })
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to fetch rooms for brand $brandId: ${e.message}", e)
            RoomResult.Error(e.message ?: "Failed to fetch rooms", e)
        }
    }

    override suspend fun fetchDisplayName(userId: String): String? {
        return runCatching {
            val profile = client.postgrest["profiles"]
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<SocialProfileDto>()
            profile?.display_name
        }.getOrElse { null }
    }

    override fun observeRoomPhonePresence(roomId: String): Flow<Boolean> {
        return emptyFlow()
    }
}

@Serializable
data class RoomDto(
    val id: String,
    val name: String,
    val status: String? = null,
    @SerialName("starts_at") val startsAt: Long? = null,
    @SerialName("ends_at") val endsAt: Long? = null,
    @SerialName("channel_reference") val channelReference: RoomChannelReferenceDto? = null
) {
    fun toDomain() = SocialRoom(
        id = id,
        name = name,
        status = status,
        startsAt = startsAt,
        endsAt = endsAt,
        channelReference = channelReference?.toDomain()
    )
}

@Serializable
data class RoomChannelReferenceDto(
    @SerialName("channel_name") val channelName: String,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("remote_id") val remoteId: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null
) {
    fun toDomain() = RoomChannelReference(
        channelName = channelName,
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
        remoteId = remoteId,
        logoUrl = logoUrl
    )
}
