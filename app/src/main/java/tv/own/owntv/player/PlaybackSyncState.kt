package tv.own.owntv.player

enum class PlayerType {
    EXOPLAYER,
    MPV
}

enum class SyncConfidence {
    EXACT,
    ESTIMATED,
    FALLBACK
}

data class PlaybackSyncState(
    val playerType: PlayerType,
    val channelKey: String?,
    val isLive: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val liveOffsetMs: Long?,
    val contentTimestampMs: Long?,
    val wallClockSampleMs: Long,
    val confidence: SyncConfidence
)
