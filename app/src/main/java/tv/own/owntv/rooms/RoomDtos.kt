package tv.own.owntv.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoomMessageDto(
    val id: String,
    @SerialName("room_id")
    val room_id: String,
    @SerialName("user_id")
    val user_id: String,
    @SerialName("sender_id")
    val sender_id: String? = null,
    @SerialName("display_name")
    val display_name: String? = null,
    val body: String,
    @SerialName("created_at")
    val created_at: String,
    @SerialName("updated_at")
    val updated_at: String? = null,
    @SerialName("deleted_at")
    val deleted_at: String? = null,
    @SerialName("sender_content_timestamp_ms")
    val sender_content_timestamp_ms: Long?,
    @SerialName("sender_wall_clock_ms")
    val sender_wall_clock_ms: Long?,
    @SerialName("sender_live_offset_ms")
    val sender_live_offset_ms: Long?,
    @SerialName("sender_confidence")
    val sender_confidence: String?,
    @SerialName("sender_player_type")
    val sender_player_type: String?
)

@Serializable
data class RoomReactionDto(
    val id: String,
    @SerialName("room_id")
    val room_id: String,
    @SerialName("user_id")
    val user_id: String,
    @SerialName("sender_id")
    val sender_id: String? = null,
    @SerialName("display_name")
    val display_name: String? = null,
    val emoji: String,
    @SerialName("created_at")
    val created_at: String,
    @SerialName("updated_at")
    val updated_at: String? = null,
    @SerialName("deleted_at")
    val deleted_at: String? = null,
    @SerialName("sender_content_timestamp_ms")
    val sender_content_timestamp_ms: Long?,
    @SerialName("sender_wall_clock_ms")
    val sender_wall_clock_ms: Long?,
    @SerialName("sender_live_offset_ms")
    val sender_live_offset_ms: Long?,
    @SerialName("sender_confidence")
    val sender_confidence: String?,
    @SerialName("sender_player_type")
    val sender_player_type: String?
)
