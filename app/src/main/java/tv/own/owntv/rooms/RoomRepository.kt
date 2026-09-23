package tv.own.owntv.rooms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed class RoomResult<out T> {
    data class Success<out T>(val data: T) : RoomResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : RoomResult<Nothing>()
}

data class RoomChannelReference(
    val channelName: String,
    val streamUrl: String?,
    val epgChannelId: String?,
    val remoteId: String?,
    val logoUrl: String?
)

data class SocialRoom(
    val id: String,
    val name: String,
    val status: String?,
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    val channelReference: RoomChannelReference? = null
)

interface RoomRepository {
    suspend fun fetchRoomsForBrand(brandId: String): RoomResult<List<SocialRoom>>
    suspend fun fetchDisplayName(userId: String): String? = null
    suspend fun leaveRoom(roomId: String) {}
    suspend fun checkClaimedRoomAccess(roomId: String): Boolean = false
    fun observeRoomPhonePresence(roomId: String): Flow<Boolean> = emptyFlow()
}
