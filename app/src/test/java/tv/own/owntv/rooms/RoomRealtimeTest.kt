package tv.own.owntv.rooms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.PlayerType
import tv.own.owntv.player.RoomSyncStamp
import tv.own.owntv.player.SyncConfidence
import tv.own.owntv.player.SyncedRoomMessage
import tv.own.owntv.player.SyncedRoomReaction

class MockRoomRepository : RoomRepository {
    var isAuthenticated = true
    val messages = mutableListOf<SyncedRoomMessage>()
    val reactions = mutableListOf<SyncedRoomReaction>()
    val messageFlow = MutableSharedFlow<SyncedRoomMessage>()
    val reactionFlow = MutableSharedFlow<SyncedRoomReaction>()

    override suspend fun fetchHistoryMessages(roomId: String): RoomResult<List<SyncedRoomMessage>> {
        return RoomResult.Success(messages.filter { it.roomId == roomId })
    }

    override suspend fun fetchHistoryReactions(roomId: String): RoomResult<List<SyncedRoomReaction>> {
        return RoomResult.Success(reactions.filter { it.roomId == roomId })
    }

    override suspend fun fetchDisplayName(userId: String): String? {
        return null
    }

    override suspend fun insertMessage(
        roomId: String,
        body: String,
        displayName: String?,
        stamp: RoomSyncStamp
    ): RoomResult<SyncedRoomMessage> {
        if (!isAuthenticated) return RoomResult.Error("User not authenticated")
        val msg = SyncedRoomMessage(
            id = "msg-${System.currentTimeMillis()}",
            roomId = roomId,
            userId = "authenticated-user-id",
            displayName = displayName,
            body = body,
            createdAtMs = System.currentTimeMillis(),
            senderContentTimestampMs = stamp.contentTimestampMs,
            senderWallClockMs = stamp.wallClockMs,
            senderLiveOffsetMs = stamp.liveOffsetMs,
            senderConfidence = stamp.confidence,
            senderPlayerType = stamp.playerType
        )
        messages.add(msg)
        return RoomResult.Success(msg)
    }

    override suspend fun fetchRoomsForBrand(brandId: String): RoomResult<List<SocialRoom>> {
        return RoomResult.Success(emptyList())
    }

    override suspend fun insertReaction(
        roomId: String,
        emoji: String,
        stamp: RoomSyncStamp
    ): RoomResult<SyncedRoomReaction> {
        if (!isAuthenticated) return RoomResult.Error("User not authenticated")
        val react = SyncedRoomReaction(
            id = "react-${System.currentTimeMillis()}",
            roomId = roomId,
            userId = "authenticated-user-id",
            displayName = null,
            emoji = emoji,
            createdAtMs = System.currentTimeMillis(),
            senderContentTimestampMs = stamp.contentTimestampMs,
            senderWallClockMs = stamp.wallClockMs,
            senderLiveOffsetMs = stamp.liveOffsetMs,
            senderConfidence = stamp.confidence,
            senderPlayerType = stamp.playerType
        )
        reactions.add(react)
        return RoomResult.Success(react)
    }

    override fun subscribeRoom(roomId: String): RoomRealtimeStream = RoomRealtimeStream(messageFlow, reactionFlow)

    override fun subscribeMessages(roomId: String): Flow<SyncedRoomMessage> = messageFlow

    override fun subscribeReactions(roomId: String): Flow<SyncedRoomReaction> = reactionFlow
}

class RoomRealtimeTest {

    @Test
    fun `DTO to domain mapping with legacy null sync fields`() {
        val dto = RoomMessageDto(
            id = "1",
            room_id = "r1",
            user_id = "u1",
            display_name = "User",
            body = "Hello",
            created_at = "2026-09-10T01:45:20.233469+00:00",
            sender_content_timestamp_ms = null,
            sender_wall_clock_ms = null,
            sender_live_offset_ms = null,
            sender_confidence = null,
            sender_player_type = null
        )

        val domain = dto.toDomain()
        assertEquals("1", domain.id)
        assertEquals(SyncConfidence.FALLBACK, domain.senderConfidence)
        assertTrue(domain.createdAtMs > 0L)
    }

    @Test
    fun `Unauthenticated send rejected and authenticated user ID inserted automatically`() = runBlocking {
        val repo = MockRoomRepository().apply { isAuthenticated = false }
        val session = RoomRealtimeSession(repo, dispatcher = Dispatchers.Unconfined)

        val resFail = session.sendMessage("r1", "Hello", "User")
        assertTrue(resFail is RoomResult.Error)

        repo.isAuthenticated = true
        val resSuccess = session.sendMessage("r1", "Hello", "User")
        assertTrue(resSuccess is RoomResult.Success)
        assertEquals("authenticated-user-id", (resSuccess as RoomResult.Success).data.userId)
    }

    @Test
    fun `parse ISO timestamptz created_at string for RoomMessageDto and RoomReactionDto`() {
        val msgDto = RoomMessageDto(
            id = "m1",
            room_id = "r1",
            user_id = "u1",
            display_name = "User",
            body = "Test",
            created_at = "2026-09-10T01:45:20.233469+00:00",
            sender_content_timestamp_ms = null,
            sender_wall_clock_ms = null,
            sender_live_offset_ms = null,
            sender_confidence = null,
            sender_player_type = null
        )
        val msgDomain = msgDto.toDomain()
        assertTrue(msgDomain.createdAtMs > 0L)

        val reactDto = RoomReactionDto(
            id = "p1",
            room_id = "r1",
            user_id = "u1",
            emoji = "👍",
            created_at = "2026-09-11T16:08:03.346979+00:00",
            sender_content_timestamp_ms = null,
            sender_wall_clock_ms = null,
            sender_live_offset_ms = null,
            sender_confidence = null,
            sender_player_type = null
        )
        val reactDomain = reactDto.toDomain()
        assertTrue(reactDomain.createdAtMs > 0L)
    }

    @Test
    fun `RoomRealtimeTest join room, history load, and realtime delivery`() = runBlocking {
        val repo = MockRoomRepository()
        repo.messages.add(
            SyncedRoomMessage("h1", "room-1", "u1", "User", "Past message", 500L, 50_000L, 50_000L, null, SyncConfidence.EXACT, PlayerType.EXOPLAYER)
        )

        val session = RoomRealtimeSession(repo, dispatcher = Dispatchers.Unconfined)

        session.joinRoom("room-1")

        assertEquals(1, session.deliveredMessages.value.size)
        assertEquals("h1", session.deliveredMessages.value[0].message.id)

        session.leaveRoom()
        session.release()
        assertTrue(true)
    }

    @Test
    fun `realtime message appears immediately without player timestamp and duplicate ids ignored`() = runBlocking {
        val repo = MockRoomRepository()
        val session = RoomRealtimeSession(repository = repo, dispatcher = Dispatchers.Unconfined)

        session.joinRoom("room-1")
        assertTrue(session.deliveredMessages.value.isEmpty())

        // Emit realtime message without sender content timestamp
        repo.messageFlow.emit(
            SyncedRoomMessage("m1", "room-1", "u1", "User", "Hello realtime", 1000L, null, 1000L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )
        assertEquals(1, session.deliveredMessages.value.size)
        assertEquals("m1", session.deliveredMessages.value[0].message.id)

        // Emit duplicate message ID -> should be ignored
        repo.messageFlow.emit(
            SyncedRoomMessage("m1", "room-1", "u1", "User", "Duplicate", 1001L, null, 1001L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )
        assertEquals(1, session.deliveredMessages.value.size)

        session.leaveRoom()
    }
}
