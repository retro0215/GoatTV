package tv.own.owntv.player

const val LOCAL_SYNC_SAMPLE_MS = 500L
const val DEFAULT_FALLBACK_SPOILER_DELAY_MS = 15_000L
const val DEFAULT_REACTION_FRESHNESS_WINDOW_MS = 12_000L

enum class SyncDeliveryDecision {
    SHOW_NOW,
    HOLD,
    SUPPRESS_ANIMATION
}

/**
 * Determines the viewer's effective content clock timestamp in epoch milliseconds.
 * If buffering, the effective time holds/freezes at the last known position.
 */
fun effectiveContentTimeMs(
    state: PlaybackSyncState?,
    fallbackDelayMs: Long = DEFAULT_FALLBACK_SPOILER_DELAY_MS
): Long? {
    if (state == null) return null
    val sampleTime = state.wallClockSampleMs

    val baseTime = when (state.confidence) {
        SyncConfidence.EXACT, SyncConfidence.ESTIMATED -> state.contentTimestampMs ?: (sampleTime - fallbackDelayMs)
        SyncConfidence.FALLBACK -> sampleTime - fallbackDelayMs
    }
    return baseTime
}

/**
 * Pure helper for message and reaction delivery decisions.
 * Normal synchronized chat is gated strictly by receiver content position.
 */
fun decideDelivery(
    receiverContentTimeMs: Long?,
    senderContentTimeMs: Long?,
    isReaction: Boolean = false,
    freshnessWindowMs: Long = DEFAULT_REACTION_FRESHNESS_WINDOW_MS,
    isFallbackConfidence: Boolean = false
): SyncDeliveryDecision {
    if (senderContentTimeMs == null || receiverContentTimeMs == null || isFallbackConfidence) {
        return SyncDeliveryDecision.SHOW_NOW
    }

    val diff = receiverContentTimeMs - senderContentTimeMs

    return if (!isReaction) {
        // Text messages: hold if viewer is behind sender content time
        if (diff >= 0) {
            SyncDeliveryDecision.SHOW_NOW
        } else {
            SyncDeliveryDecision.HOLD
        }
    } else {
        // Floating reactions: hold if viewer is behind sender content time
        when {
            diff < 0 -> SyncDeliveryDecision.HOLD
            diff <= freshnessWindowMs -> SyncDeliveryDecision.SHOW_NOW
            else -> SyncDeliveryDecision.SUPPRESS_ANIMATION
        }
    }
}
