package tv.own.owntv.rooms

import kotlinx.serialization.Serializable

@Serializable
data class SocialProfileDto(
    val id: String,
    val display_name: String?,
    val avatar_url: String?,
    val origin_brand_id: String?,
    val is_admin: Boolean?,
    val is_moderator: Boolean?,
    val is_banned: Boolean?,
    val created_at: Long?,
    val updated_at: Long?
)

data class SocialProfile(
    val id: String,
    val displayName: String?,
    val avatarUrl: String?,
    val originBrandId: String,
    val isAdmin: Boolean,
    val isModerator: Boolean,
    val isBanned: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

fun SocialProfileDto.toDomain(): SocialProfile {
    return SocialProfile(
        id = id,
        displayName = display_name,
        avatarUrl = avatar_url,
        originBrandId = origin_brand_id ?: "goat",
        isAdmin = is_admin ?: false,
        isModerator = is_moderator ?: false,
        isBanned = is_banned ?: false,
        createdAtMs = created_at ?: 0L,
        updatedAtMs = updated_at ?: 0L
    )
}

fun SocialProfile.toDto(): SocialProfileDto {
    return SocialProfileDto(
        id = id,
        display_name = displayName,
        avatar_url = avatarUrl,
        origin_brand_id = originBrandId,
        is_admin = isAdmin,
        is_moderator = isModerator,
        is_banned = isBanned,
        created_at = createdAtMs,
        updated_at = updatedAtMs
    )
}
