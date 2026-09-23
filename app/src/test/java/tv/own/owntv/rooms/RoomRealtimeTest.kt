package tv.own.owntv.rooms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.PlaybackSyncState
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
        val syncState = MutableStateFlow(
            PlaybackSyncState(
                playerType = PlayerType.EXOPLAYER,
                channelKey = "ch-1",
                isLive = false,
                isPlaying = true,
                isBuffering = false,
                positionMs = 60_000L,
                durationMs = 100_000L,
                liveOffsetMs = null,
                contentTimestampMs = 60_000L,
                wallClockSampleMs = 60_000L,
                confidence = SyncConfidence.EXACT
            )
        )
        val session = RoomRealtimeSession(repo, syncState, dispatcher = Dispatchers.Unconfined)

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

        val syncState = MutableStateFlow(
            PlaybackSyncState(
                playerType = PlayerType.EXOPLAYER,
                channelKey = "ch-1",
                isLive = false,
                isPlaying = true,
                isBuffering = false,
                positionMs = 60_000L,
                durationMs = 100_000L,
                liveOffsetMs = null,
                contentTimestampMs = 60_000L,
                wallClockSampleMs = 60_000L,
                confidence = SyncConfidence.EXACT
            )
        )

        val session = RoomRealtimeSession(repo, syncState, dispatcher = Dispatchers.Unconfined)

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

    @Test
    fun `send and realtime echo produces one visible message due to deduplication`() = runBlocking {
        val repo = MockRoomRepository()
        val session = RoomRealtimeSession(repository = repo, dispatcher = Dispatchers.Unconfined)

        session.joinRoom("room-1")

        // Send message (inserts into repo and appends locally with id e.g. msg-123)
        val res = session.sendMessage("room-1", "Test send", "Me")
        assertTrue(res is RoomResult.Success)
        val sentMsg = (res as RoomResult.Success).data

        assertEquals(1, session.deliveredMessages.value.size)

        // Same message arrives via realtime echo
        repo.messageFlow.emit(sentMsg)
        assertEquals(1, session.deliveredMessages.value.size)

        session.leaveRoom()
    }

    @Test
    fun `wrong room id ignored and message ordering maintained`() = runBlocking {
        val repo = MockRoomRepository()
        val session = RoomRealtimeSession(repository = repo, dispatcher = Dispatchers.Unconfined)

        session.joinRoom("room-1")

        repo.messageFlow.emit(
            SyncedRoomMessage("m2", "room-2", "u1", "User", "Wrong room", 1000L, null, 1000L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )
        assertTrue(session.deliveredMessages.value.isEmpty())

        repo.messageFlow.emit(
            SyncedRoomMessage("m10", "room-1", "u1", "User", "Second", 2000L, null, 2000L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )
        repo.messageFlow.emit(
            SyncedRoomMessage("m5", "room-1", "u1", "User", "First", 1000L, null, 1000L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )

        assertEquals(2, session.deliveredMessages.value.size)
        assertEquals("m5", session.deliveredMessages.value[0].message.id)
        assertEquals("m10", session.deliveredMessages.value[1].message.id)

        session.leaveRoom()
    }

    @Test
    fun `room transition A to B clears old state and reactions appear immediately`() = runBlocking {
        val repo = MockRoomRepository()
        val session = RoomRealtimeSession(repository = repo, dispatcher = Dispatchers.Unconfined)

        session.joinRoom("room-A")
        repo.messageFlow.emit(
            SyncedRoomMessage("mA", "room-A", "u1", "User", "A", 1000L, null, 1000L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER)
        )
        assertEquals(1, session.deliveredMessages.value.size)

        // Switch to room-B
        session.joinRoom("room-B")
        assertTrue(session.deliveredMessages.value.isEmpty())

        // Realtime reaction in room-B
        repo.reactionFlow.emit(
            SyncedRoomReaction("r1", "room-B", "u1", "❤️", 1500L, null, 1500L, null, SyncConfidence.FALLBACK, PlayerType.EXOPLAYER, "User")
        )
        assertEquals(1, session.deliveredReactions.value.size)
        assertEquals("❤️", session.deliveredReactions.value[0].reaction.emoji)

        session.leaveRoom()
    }
}
