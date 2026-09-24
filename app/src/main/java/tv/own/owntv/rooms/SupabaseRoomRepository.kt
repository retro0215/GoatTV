package tv.own.owntv.rooms

import android.util.Log
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import tv.own.owntv.player.RoomSyncStamp
import tv.own.owntv.player.SyncedRoomMessage
import tv.own.owntv.player.SyncedRoomReaction

class SupabaseRoomRepository : RoomRepository {
    private val client = SupabaseClientProvider.client
    private val channelMutex = Mutex()
    private val roomChannels = mutableMapOf<String, RealtimeChannel>()
    private val subscribedChannels = mutableSetOf<String>()
    private val activeRoomJobs = mutableMapOf<String, Job>()
    private var isRealtimeConnected = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonLenient = Json { ignoreUnknownKeys = true }

    private suspend fun ensureRealtimeConnected() {
        if (!isRealtimeConnected) {
            channelMutex.withLock {
                if (!isRealtimeConnected) {
                    runCatching {
                        client.realtime.connect()
                        isRealtimeConnected = true
                    }
                }
            }
        }
    }

    private fun getOrCreateRoomChannel(roomId: String): RealtimeChannel {
        synchronized(roomChannels) {
            return roomChannels.getOrPut(roomId) {
                client.realtime.channel("social-room-$roomId")
            }
        }
    }

    private suspend fun ensureAndSubscribeChannel(roomId: String, channel: RealtimeChannel) {
        if (!subscribedChannels.contains(roomId)) {
            ensureRealtimeConnected()
            channelMutex.withLock {
                if (!subscribedChannels.contains(roomId)) {
                    try {
                        withTimeout(10_000) {
                            channel.subscribe(blockUntilSubscribed = true)
                        }
                        subscribedChannels.add(roomId)
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w("RoomRepo", "Room Realtime subscription timeout for room $roomId")
                    } catch (e: Exception) {
                        Log.e("RoomRepo", "Room Realtime subscription error: ${e.message}", e)
                    }
                }
            }
        }
    }

    override suspend fun fetchHistoryMessages(roomId: String): RoomResult<List<SyncedRoomMessage>> {
        return runCatching {
            val dtos = client.postgrest["messages"]
                .select {
                    filter { eq("room_id", roomId) }
                    order("created_at", Order.ASCENDING)
                    limit(50)
                }
                .decodeList<RoomMessageDto>()
            RoomResult.Success(dtos.map { it.toDomain() })
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to fetch message history: ${e.message}", e)
            RoomResult.Error("Failed to fetch message history: ${e.message}", e)
        }
    }

    override suspend fun fetchHistoryReactions(roomId: String): RoomResult<List<SyncedRoomReaction>> {
        return runCatching {
            val dtos = client.postgrest["reactions"]
                .select {
                    filter { eq("room_id", roomId) }
                    order("created_at", Order.ASCENDING)
                    limit(100)
                }
                .decodeList<RoomReactionDto>()
            RoomResult.Success(dtos.map { it.toDomain() })
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to fetch reaction history: ${e.message}", e)
            RoomResult.Error("Failed to fetch reaction history: ${e.message}", e)
        }
    }

    override suspend fun leaveRoom(roomId: String) {
        channelMutex.withLock {
            subscribedChannels.remove(roomId)
            val job = synchronized(activeRoomJobs) { activeRoomJobs.remove(roomId) }
            job?.cancel()

            val channel = roomChannels.remove(roomId)
            if (channel != null) {
                runCatching {
                    channel.unsubscribe()
                    client.realtime.removeChannel(channel)
                }
            }
            runCatching {
                client.realtime.removeAllChannels()
            }
        }
    }

    @Serializable
    data class DiscoveredRoomDto(
        val id: String,
        val name: String,
        val status: String? = null,
        val starts_at: String? = null,
        val ends_at: String? = null,
        val channel_name: String? = null,
        val epg_channel_id: String? = null,
        val remote_id: String? = null,
        val logo_url: String? = null
    ) {
        fun toDomain(): SocialRoom {
            val parsedStarts = parseTimestamp(starts_at)
            val parsedEnds = parseTimestamp(ends_at)
            val nowMs = System.currentTimeMillis()
            val computedState = RoomAccessPolicy.evaluate(status, parsedStarts, parsedEnds, nowMs)
            Log.d("RoomRepo", "ROOM_TIME_RAW: status=$status, starts_at=$starts_at, ends_at=$ends_at")
            Log.d("RoomRepo", "ROOM_TIME_PARSED: startsAtEpochMs=$parsedStarts, endsAtEpochMs=$parsedEnds, nowEpochMs=$nowMs")
            Log.d("RoomRepo", "ROOM_TIME_STATE: computed RoomAccessState=$computedState")

            val channelRef = if (!channel_name.isNullOrBlank() || !remote_id.isNullOrBlank() || !epg_channel_id.isNullOrBlank()) {
                RoomChannelReference(
                    channelName = channel_name ?: "Live Channel",
                    streamUrl = null,
                    epgChannelId = epg_channel_id,
                    remoteId = remote_id,
                    logoUrl = logo_url
                )
            } else null
            return SocialRoom(
                id = id,
                name = name,
                status = status,
                startsAt = parsedStarts,
                endsAt = parsedEnds,
                channelReference = channelRef
            )
        }
    }

    override suspend fun fetchRoomsForBrand(brandId: String): RoomResult<List<SocialRoom>> {
        return runCatching {
            val dtos = client.postgrest.rpc(
                "discover_rooms_for_brand",
                buildJsonObject {
                    put("p_brand_id", brandId)
                }
            ).decodeList<DiscoveredRoomDto>()

            RoomResult.Success(dtos.map { it.toDomain() })
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to fetch rooms for brand $brandId: ${e.message}", e)
            RoomResult.Error("Failed to fetch rooms for brand $brandId: ${e.message}", e)
        }
    }

    override suspend fun fetchDisplayName(userId: String): String? {
        return runCatching {
            val dto = client.postgrest["profiles"]
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<SocialProfileDto>()
            dto?.display_name?.takeIf { !it.isBlank() }
        }.getOrNull()
    }

    override suspend fun insertMessage(
        roomId: String,
        body: String,
        displayName: String?,
        stamp: RoomSyncStamp
    ): RoomResult<SyncedRoomMessage> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return RoomResult.Error("User not authenticated")

        val resolvedName = displayName?.takeIf { !it.isBlank() } ?: fetchDisplayName(userId)

        return runCatching {
            val dto = RoomMessageDto(
                id = "",
                room_id = roomId,
                user_id = userId,
                display_name = resolvedName,
                body = body,
                created_at = formatCreatedAt(System.currentTimeMillis()),
                sender_content_timestamp_ms = stamp.contentTimestampMs,
                sender_wall_clock_ms = stamp.wallClockMs,
                sender_live_offset_ms = stamp.liveOffsetMs,
                sender_confidence = confidenceToString(stamp.confidence),
                sender_player_type = playerTypeToString(stamp.playerType)
            )

            val inserted = client.postgrest["messages"]
                .insert(dto) {
                    select()
                }
                .decodeSingle<RoomMessageDto>()

            RoomResult.Success(inserted.toDomain())
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to send message: ${e.message}", e)
            RoomResult.Error("Failed to send message: ${e.message}", e)
        }
    }

    override suspend fun insertReaction(
        roomId: String,
        emoji: String,
        stamp: RoomSyncStamp
    ): RoomResult<SyncedRoomReaction> {
        val userId = client.auth.currentUserOrNull()?.id
            ?: return RoomResult.Error("User not authenticated")

        val resolvedName = fetchDisplayName(userId)

        return runCatching {
            val dto = RoomReactionDto(
                id = "",
                room_id = roomId,
                user_id = userId,
                display_name = resolvedName,
                emoji = emoji,
                created_at = formatCreatedAt(System.currentTimeMillis()),
                sender_content_timestamp_ms = stamp.contentTimestampMs,
                sender_wall_clock_ms = stamp.wallClockMs,
                sender_live_offset_ms = stamp.liveOffsetMs,
                sender_confidence = confidenceToString(stamp.confidence),
                sender_player_type = playerTypeToString(stamp.playerType)
            )

            val inserted = client.postgrest["reactions"]
                .insert(dto) {
                    select()
                }
                .decodeSingle<RoomReactionDto>()

            RoomResult.Success(inserted.toDomain())
        }.getOrElse { e ->
            Log.e("RoomRepo", "Failed to send reaction: ${e.message}", e)
            RoomResult.Error("Failed to send reaction: ${e.message}", e)
        }
    }

    private val roomPresenceFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()

    override fun observeRoomPhonePresence(roomId: String): Flow<Boolean> {
        synchronized(roomPresenceFlows) {
            return roomPresenceFlows.getOrPut(roomId) { MutableStateFlow(false) }
        }
    }

    override fun subscribeRoom(roomId: String): RoomRealtimeStream {
        val channel = getOrCreateRoomChannel(roomId)

        val messageFlow: Flow<PostgresAction.Insert> = channel.postgresChangeFlow(schema = "public") {
            table = "messages"
            filter = "room_id=eq.$roomId"
        }

        val reactionFlow: Flow<PostgresAction.Insert> = channel.postgresChangeFlow(schema = "public") {
            table = "reactions"
            filter = "room_id=eq.$roomId"
        }

        val presenceStateFlow = synchronized(roomPresenceFlows) {
            roomPresenceFlows.getOrPut(roomId) { MutableStateFlow(false) }
        }

        val messageSharedFlow = MutableSharedFlow<SyncedRoomMessage>(replay = 0, extraBufferCapacity = 64)
        val reactionSharedFlow = MutableSharedFlow<SyncedRoomReaction>(replay = 0, extraBufferCapacity = 64)

        val job = serviceScope.launch {
            val activePhones = mutableSetOf<String>()

            fun updatePresence(present: Boolean) {
                presenceStateFlow.value = present
            }

            // 1. Register presence listener BEFORE channel subscribe/join
            launch {
                try {
                    channel.presenceChangeFlow().collect { action ->
                        for ((ref, presence) in action.joins) {
                            val payload = runCatching {
                                jsonLenient.decodeFromJsonElement<DedicatedPhonePresencePayload>(presence.state)
                            }.getOrNull()
                            if (payload != null && payload.isMatchingPhone(roomId)) {
                                activePhones.add(ref)
                            }
                        }
                        for ((ref, _) in action.leaves) {
                            activePhones.remove(ref)
                        }
                        updatePresence(activePhones.isNotEmpty())
                    }
                } catch (e: Exception) {
                    Log.e("RoomRepo", "Presence flow error: ${e.message}", e)
                }
            }

            // 2. Register postgres change collectors BEFORE channel subscribe/join
            launch {
                try {
                    messageFlow.collect { action ->
                        runCatching {
                            val dto = jsonLenient.decodeFromJsonElement<RoomMessageDto>(action.record)
                            if (dto.room_id == roomId) {
                                messageSharedFlow.tryEmit(dto.toDomain())
                            }
                        }.onFailure { e ->
                            Log.e("RoomRepo", "Error processing chat event: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RoomRepo", "Message collector fatal error: ${e.message}", e)
                }
            }

            launch {
                try {
                    reactionFlow.collect { action ->
                        runCatching {
                            val dto = jsonLenient.decodeFromJsonElement<RoomReactionDto>(action.record)
                            if (dto.room_id == roomId) {
                                reactionSharedFlow.tryEmit(dto.toDomain())
                            }
                        }.onFailure { e ->
                            Log.e("RoomRepo", "Error processing reaction event: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RoomRepo", "Reaction collector fatal error: ${e.message}", e)
                }
            }

            // 3. ONE canonical subscribe/join call
            ensureAndSubscribeChannel(roomId, channel)
            Log.d("RoomRepo", "TV_SOCIAL_REALTIME: roomId=$roomId, topic=social-room-$roomId, subscribed=true")
        }

        synchronized(activeRoomJobs) {
            activeRoomJobs[roomId]?.cancel()
            activeRoomJobs[roomId] = job
        }

        return RoomRealtimeStream(messageSharedFlow, reactionSharedFlow, presenceStateFlow)
    }

    override fun subscribeMessages(roomId: String): Flow<SyncedRoomMessage> {
        return subscribeRoom(roomId).messages
    }

    override fun subscribeReactions(roomId: String): Flow<SyncedRoomReaction> {
        return subscribeRoom(roomId).reactions
    }
}
