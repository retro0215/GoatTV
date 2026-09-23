package tv.own.owntv.rooms

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import tv.own.owntv.player.DeliveredMessage
import tv.own.owntv.player.DeliveredReaction
import tv.own.owntv.player.PlayerType
import tv.own.owntv.player.RoomSyncStamp
import tv.own.owntv.player.SyncConfidence
import tv.own.owntv.player.SyncDeliveryDecision
import tv.own.owntv.player.SyncedRoomMessage
import tv.own.owntv.player.SyncedRoomReaction
import kotlin.coroutines.CoroutineContext

class RoomRealtimeSession(
    private val repository: RoomRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Main.immediate
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var activeRoomId: String? = null
    private var messageSubJob: Job? = null
    private var reactionSubJob: Job? = null

    private val _deliveredMessages = MutableStateFlow<List<DeliveredMessage>>(emptyList())
    val deliveredMessages: StateFlow<List<DeliveredMessage>> = _deliveredMessages.asStateFlow()

    private val _deliveredReactions = MutableStateFlow<List<DeliveredReaction>>(emptyList())
    val deliveredReactions: StateFlow<List<DeliveredReaction>> = _deliveredReactions.asStateFlow()

    fun joinRoom(roomId: String) {
        scope.launch {
            val oldRoom = activeRoomId
            activeRoomId = null
            messageSubJob?.cancel()
            reactionSubJob?.cancel()
            messageSubJob = null
            reactionSubJob = null

            if (oldRoom != null) {
                repository.leaveRoom(oldRoom)
            }
            clearRoomInternal()

            activeRoomId = roomId

            // 1. Fetch history
            val msgResult = repository.fetchHistoryMessages(roomId)
            if (msgResult is RoomResult.Success) {
                val delivered = msgResult.data
                    .sortedBy { it.createdAtMs }
                    .takeLast(100)
                    .map { DeliveredMessage(it, SyncDeliveryDecision.SHOW_NOW) }
                _deliveredMessages.value = delivered
            }

            val reactResult = repository.fetchHistoryReactions(roomId)
            if (reactResult is RoomResult.Success) {
                val delivered = reactResult.data
                    .sortedBy { it.createdAtMs }
                    .takeLast(200)
                    .map { DeliveredReaction(it, SyncDeliveryDecision.SHOW_NOW) }
                _deliveredReactions.value = delivered
            }

            // 2. Subscribe to Realtime stream directly without clock gating
            val stream = repository.subscribeRoom(roomId)
            
            messageSubJob = stream.messages
                .onEach { msg ->
                    if (activeRoomId == roomId) {
                        try {
                            if (msg.roomId == roomId) {
                                appendMessage(msg)
                            }
                        } catch (e: Exception) {
                            Log.e("RoomRealtimeSession", "Error processing realtime message: ${e.message}", e)
                        }
                    }
                }
                .launchIn(scope)

            reactionSubJob = stream.reactions
                .onEach { react ->
                    if (activeRoomId == roomId) {
                        try {
                            if (react.roomId == roomId) {
                                appendReaction(react)
                            }
                        } catch (e: Exception) {
                            Log.e("RoomRealtimeSession", "Error processing realtime reaction: ${e.message}", e)
                        }
                    }
                }
                .launchIn(scope)
        }
    }

    private fun appendMessage(message: SyncedRoomMessage) {
        val current = _deliveredMessages.value
        if (current.any { it.message.id == message.id }) return
        val updated = (current + DeliveredMessage(message, SyncDeliveryDecision.SHOW_NOW))
            .sortedBy { it.message.createdAtMs }
            .takeLast(100)
        _deliveredMessages.value = updated
    }

    private fun appendReaction(reaction: SyncedRoomReaction) {
        val current = _deliveredReactions.value
        if (current.any { it.reaction.id == reaction.id }) return
        val updated = (current + DeliveredReaction(reaction, SyncDeliveryDecision.SHOW_NOW))
            .sortedBy { it.reaction.createdAtMs }
            .takeLast(200)
        _deliveredReactions.value = updated
    }

    private fun clearRoomInternal() {
        _deliveredMessages.value = emptyList()
        _deliveredReactions.value = emptyList()
    }

    fun leaveRoom() {
        val oldRoom = activeRoomId
        activeRoomId = null
        messageSubJob?.cancel()
        reactionSubJob?.cancel()
        messageSubJob = null
        reactionSubJob = null
        clearRoomInternal()

        if (oldRoom != null) {
            scope.launch {
                repository.leaveRoom(oldRoom)
            }
        }
    }

    suspend fun sendMessage(roomId: String, body: String, displayName: String?): RoomResult<SyncedRoomMessage> {
        if (body.isBlank()) {
            return RoomResult.Error("Message body cannot be blank")
        }
        val stamp = RoomSyncStamp(
            contentTimestampMs = null,
            wallClockMs = System.currentTimeMillis(),
            liveOffsetMs = null,
            confidence = SyncConfidence.FALLBACK,
            playerType = PlayerType.EXOPLAYER
        )
        val result = repository.insertMessage(roomId, body, displayName, stamp)
        if (result is RoomResult.Success) {
            if (activeRoomId == roomId) {
                appendMessage(result.data)
            }
        }
        return result
    }

    suspend fun sendReaction(roomId: String, emoji: String): RoomResult<SyncedRoomReaction> {
        if (emoji.isBlank()) {
            return RoomResult.Error("Reaction emoji cannot be blank")
        }
        val stamp = RoomSyncStamp(
            contentTimestampMs = null,
            wallClockMs = System.currentTimeMillis(),
            liveOffsetMs = null,
            confidence = SyncConfidence.FALLBACK,
            playerType = PlayerType.EXOPLAYER
        )
        val result = repository.insertReaction(roomId, emoji, stamp)
        if (result is RoomResult.Success) {
            if (activeRoomId == roomId) {
                appendReaction(result.data)
            }
        }
        return result
    }

    fun release() {
        leaveRoom()
        scope.cancel()
    }
}
