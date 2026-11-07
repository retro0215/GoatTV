package tv.own.owntv.player

data class SyncedRoomMessage(
    val id: String,
    val roomId: String,
    val userId: String,
    val displayName: String?,
    val body: String,
    val createdAtMs: Long,
    val senderContentTimestampMs: Long?,
    val senderWallClockMs: Long,
    val senderLiveOffsetMs: Long?,
    val senderConfidence: SyncConfidence,
    val senderPlayerType: PlayerType?
)

data class SyncedRoomReaction(
    val id: String,
    val roomId: String,
    val userId: String,
    val emoji: String,
    val createdAtMs: Long,
    val senderContentTimestampMs: Long?,
    val senderWallClockMs: Long,
    val senderLiveOffsetMs: Long?,
    val senderConfidence: SyncConfidence,
    val senderPlayerType: PlayerType?,
    val displayName: String? = null
)

data class DeliveredMessage(
    val message: SyncedRoomMessage,
    val decision: SyncDeliveryDecision
)

data class DeliveredReaction(
    val reaction: SyncedRoomReaction,
    val decision: SyncDeliveryDecision
)

data class RoomSyncStamp(
    val contentTimestampMs: Long?,
    val wallClockMs: Long,
    val liveOffsetMs: Long?,
    val confidence: SyncConfidence,
    val playerType: PlayerType
)

fun buildSyncStamp(
    state: PlaybackSyncState,
    fallbackDelayMs: Long = DEFAULT_FALLBACK_SPOILER_DELAY_MS
): RoomSyncStamp {
    val sampleTime = state.wallClockSampleMs
    val effectiveTime = effectiveContentTimeMs(state, fallbackDelayMs)

    val stampConfidence = if (state.confidence == SyncConfidence.FALLBACK) {
        SyncConfidence.FALLBACK
    } else {
        state.confidence
    }

    return RoomSyncStamp(
        contentTimestampMs = effectiveTime,
        wallClockMs = sampleTime,
        liveOffsetMs = state.liveOffsetMs,
        confidence = stampConfidence,
        playerType = state.playerType
    )
}
