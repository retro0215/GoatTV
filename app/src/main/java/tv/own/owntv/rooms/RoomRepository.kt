package tv.own.owntv.rooms

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import tv.own.owntv.player.RoomSyncStamp
import tv.own.owntv.player.SyncedRoomMessage
import tv.own.owntv.player.SyncedRoomReaction

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

data class RoomRealtimeStream(
    val messages: Flow<SyncedRoomMessage>,
    val reactions: Flow<SyncedRoomReaction>
)

interface RoomRepository {
    suspend fun fetchHistoryMessages(roomId: String): RoomResult<List<SyncedRoomMessage>>
    suspend fun fetchHistoryReactions(roomId: String): RoomResult<List<SyncedRoomReaction>>
    suspend fun insertMessage(roomId: String, body: String, displayName: String?, stamp: RoomSyncStamp): RoomResult<SyncedRoomMessage>
    suspend fun insertReaction(roomId: String, emoji: String, stamp: RoomSyncStamp): RoomResult<SyncedRoomReaction>
    suspend fun fetchRoomsForBrand(brandId: String): RoomResult<List<SocialRoom>>
    suspend fun fetchDisplayName(userId: String): String?
    fun subscribeRoom(roomId: String): RoomRealtimeStream
    fun subscribeMessages(roomId: String): Flow<SyncedRoomMessage>
    fun subscribeReactions(roomId: String): Flow<SyncedRoomReaction>
    suspend fun leaveRoom(roomId: String) {}
}
