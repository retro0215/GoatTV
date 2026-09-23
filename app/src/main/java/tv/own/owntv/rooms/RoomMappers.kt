package tv.own.owntv.rooms

import android.util.Log
import tv.own.owntv.player.PlayerType
import tv.own.owntv.player.RoomSyncStamp
import tv.own.owntv.player.SyncConfidence
import tv.own.owntv.player.SyncedRoomMessage
import tv.own.owntv.player.SyncedRoomReaction

fun parseConfidence(confidenceStr: String?): SyncConfidence {
    return when (confidenceStr?.lowercase()) {
        "exact" -> SyncConfidence.EXACT
        "estimated" -> SyncConfidence.ESTIMATED
        else -> SyncConfidence.FALLBACK
    }
}

fun parsePlayerType(playerTypeStr: String?): PlayerType? {
    return when (playerTypeStr?.lowercase()) {
        "exoplayer" -> PlayerType.EXOPLAYER
        "mpv" -> PlayerType.MPV
        else -> null
    }
}

fun confidenceToString(confidence: SyncConfidence): String {
    return when (confidence) {
        SyncConfidence.EXACT -> "exact"
        SyncConfidence.ESTIMATED -> "estimated"
        SyncConfidence.FALLBACK -> "fallback"
    }
}

fun playerTypeToString(playerType: PlayerType): String {
    return when (playerType) {
        PlayerType.EXOPLAYER -> "exoplayer"
        PlayerType.MPV -> "mpv"
    }
}

@android.annotation.SuppressLint("NewApi")
fun parseCreatedAt(createdAtStr: String?): Long {
    if (createdAtStr.isNullOrBlank()) return System.currentTimeMillis()
    return try {
        java.time.OffsetDateTime.parse(createdAtStr).toInstant().toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}

fun formatCreatedAt(createdAtMs: Long): String {
    return runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        fmt.format(java.util.Date(createdAtMs))
    }.getOrElse { "2026-09-10T01:45:20.233Z" }
}

fun RoomMessageDto.toDomain(): SyncedRoomMessage {
    val createdMs = parseCreatedAt(created_at)
    val safeId = if (!id.isBlank()) {
        id
    } else {
        Log.w("RoomMappers", "Missing message ID in DTO; generated fallback UUID")
        java.util.UUID.randomUUID().toString()
    }
    return SyncedRoomMessage(
        id = safeId,
        roomId = room_id,
        userId = user_id,
        body = body,
        createdAtMs = createdMs,
        senderContentTimestampMs = sender_content_timestamp_ms,
        senderWallClockMs = sender_wall_clock_ms ?: createdMs,
        senderLiveOffsetMs = sender_live_offset_ms,
        senderConfidence = parseConfidence(sender_confidence),
        senderPlayerType = parsePlayerType(sender_player_type),
        displayName = display_name?.takeIf { !it.isBlank() }
    )
}

fun RoomReactionDto.toDomain(): SyncedRoomReaction {
    val createdMs = parseCreatedAt(created_at)
    val safeId = if (!id.isBlank()) {
        id
    } else {
        Log.w("RoomMappers", "Missing reaction ID in DTO; generated fallback UUID")
        java.util.UUID.randomUUID().toString()
    }
    return SyncedRoomReaction(
        id = safeId,
        roomId = room_id,
        userId = user_id,
        emoji = emoji,
        createdAtMs = createdMs,
        senderContentTimestampMs = sender_content_timestamp_ms,
        senderWallClockMs = sender_wall_clock_ms ?: createdMs,
        senderLiveOffsetMs = sender_live_offset_ms,
        senderConfidence = parseConfidence(sender_confidence),
        senderPlayerType = parsePlayerType(sender_player_type),
        displayName = display_name?.takeIf { !it.isBlank() }
    )
}

fun SyncedRoomMessage.toDto(stamp: RoomSyncStamp): RoomMessageDto {
    return RoomMessageDto(
        id = id,
        room_id = roomId,
        user_id = userId,
        display_name = displayName,
        body = body,
        created_at = formatCreatedAt(createdAtMs),
        sender_content_timestamp_ms = stamp.contentTimestampMs,
        sender_wall_clock_ms = stamp.wallClockMs,
        sender_live_offset_ms = stamp.liveOffsetMs,
        sender_confidence = confidenceToString(stamp.confidence),
        sender_player_type = playerTypeToString(stamp.playerType)
    )
}

fun SyncedRoomReaction.toDto(stamp: RoomSyncStamp): RoomReactionDto {
    return RoomReactionDto(
        id = id,
        room_id = roomId,
        user_id = userId,
        display_name = displayName,
        emoji = emoji,
        created_at = formatCreatedAt(createdAtMs),
        sender_content_timestamp_ms = stamp.contentTimestampMs,
        sender_wall_clock_ms = stamp.wallClockMs,
        sender_live_offset_ms = stamp.liveOffsetMs,
        sender_confidence = confidenceToString(stamp.confidence),
        sender_player_type = playerTypeToString(stamp.playerType)
    )
}

@android.annotation.SuppressLint("NewApi")
fun parseTimestamp(ts: String?): Long? {
    if (ts.isNullOrBlank()) return null
    return try {
        java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli()
    } catch (e: Exception) {
        Log.w("RoomMappers", "Failed to parse room timestamp: $ts", e)
        null
    }
}
