package tv.own.owntv.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoomMessageDto(
    val id: String,
    val room_id: String,
    val user_id: String,
    val sender_id: String? = null,
    val display_name: String? = null,
    val body: String,
    val created_at: String,
    val updated_at: String? = null,
    @SerialName("deleted_at")
    val deleted_at: String? = null,
    val sender_content_timestamp_ms: Long?,
    val sender_wall_clock_ms: Long?,
    val sender_live_offset_ms: Long?,
    val sender_confidence: String?,
    val sender_player_type: String?
)

@Serializable
data class RoomReactionDto(
    val id: String,
    val room_id: String,
    val user_id: String,
    val sender_id: String? = null,
    val display_name: String? = null,
    val emoji: String,
    val created_at: String,
    val updated_at: String? = null,
    @SerialName("deleted_at")
    val deleted_at: String? = null,
    val sender_content_timestamp_ms: Long?,
    val sender_wall_clock_ms: Long?,
    val sender_live_offset_ms: Long?,
    val sender_confidence: String?,
    val sender_player_type: String?
)
