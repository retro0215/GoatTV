package tv.own.owntv.rooms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tv.own.owntv.player.PlayerType
import tv.own.owntv.player.SyncConfidence

const val MAX_TV_SYNC_SAMPLE_AGE_MS = 5000L
const val PAIRING_SESSION_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes

enum class PairingStatus {
    ACTIVE,
    CLAIMED,
    EXPIRED,
    CANCELLED
}

@Serializable
data class PairingSessionDto(
    val id: String,
    val pairing_code: String,
    val tv_device_id: String,
    val claiming_user_id: String?,
    val created_at: Long,
    val expires_at: Long,
    val claimed_at: Long?,
    val status: String
)

@Serializable
data class CreatePairingResponseDto(
    val id: String,
    val pairing_code: String,
    val qr_token: String,
    val expires_at: String,
    val status: String
)

@Serializable
data class PairingSessionRowDto(
    val id: String,
    val room_id: String? = null,
    val brand_id: String,
    val pairing_code: String,
    val tv_session_id: String,
    val claimed_by: String? = null,
    val expires_at: String,
    val claimed_at: String? = null,
    val created_at: String,
    val tv_auth_user_id: String? = null,
    val status: String,
    val qr_token: String? = null,
    @SerialName("paired_until")
    val paired_until: String? = null
) {
    fun toDomain(): TvPairingInfo {
        val st = when {
            status.lowercase() == "claimed" -> PairingStatus.CLAIMED
            status.lowercase() == "cancelled" -> PairingStatus.CANCELLED
            status.lowercase() == "expired" -> PairingStatus.EXPIRED
            parseTimestampToMillis(expires_at) <= System.currentTimeMillis() -> PairingStatus.EXPIRED
            else -> PairingStatus.ACTIVE
        }
        return TvPairingInfo(
            id = id,
            pairingCode = pairing_code,
            qrToken = qr_token ?: "",
            expiresAtMs = parseTimestampToMillis(expires_at),
            status = st,
            claimedBy = claimed_by
        )
    }
}

data class PairingSession(
    val id: String,
    val pairingCode: String,
    val tvDeviceId: String,
    val claimingUserId: String?,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val claimedAtMs: Long?,
    val status: PairingStatus
)

const val PRODUCTION_PHONE_COMPANION_BASE_URL = "https://connect.goattv.net"

data class TvPairingInfo(
    val id: String,
    val pairingCode: String,
    val qrToken: String,
    val expiresAtMs: Long,
    val status: PairingStatus,
    val claimedBy: String? = null,
    val roomId: String? = null
) {
    fun qrUrl(baseUrl: String = "$PRODUCTION_PHONE_COMPANION_BASE_URL/pair?token="): String {
        return "$baseUrl$qrToken"
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        return nowMs >= expiresAtMs
    }
}

data class TvPlaybackSyncEnvelope(
    val pairingSessionId: String,
    val roomId: String?,
    val channelKey: String?,
    val contentTimestampMs: Long?,
    val wallClockSampleMs: Long,
    val liveOffsetMs: Long?,
    val confidence: SyncConfidence,
    val playerType: PlayerType,
    val isPlaying: Boolean,
    val isBuffering: Boolean
) {
    fun validateAndNormalize(nowMs: Long = System.currentTimeMillis()): TvPlaybackSyncEnvelope {
        val sampleAge = nowMs - wallClockSampleMs
        if (sampleAge > MAX_TV_SYNC_SAMPLE_AGE_MS) {
            return copy(confidence = SyncConfidence.FALLBACK)
        }
        return this
    }
}

fun parseTimestampToMillis(timestampStr: String?): Long {
    if (timestampStr.isNullOrBlank()) return System.currentTimeMillis() + PAIRING_SESSION_EXPIRY_MS
    return try {
        java.time.Instant.parse(timestampStr).toEpochMilli()
    } catch (_: Exception) {
        System.currentTimeMillis() + PAIRING_SESSION_EXPIRY_MS
    }
}

fun parsePairingStatus(statusStr: String?): PairingStatus {
    return when (statusStr?.uppercase()) {
        "CLAIMED" -> PairingStatus.CLAIMED
        "EXPIRED" -> PairingStatus.EXPIRED
        "CANCELLED" -> PairingStatus.CANCELLED
        else -> PairingStatus.ACTIVE
    }
}

fun pairingStatusToString(status: PairingStatus): String {
    return status.name.lowercase()
}

fun PairingSessionDto.toDomain(): PairingSession {
    return PairingSession(
        id = id,
        pairingCode = pairing_code,
        tvDeviceId = tv_device_id,
        claimingUserId = claiming_user_id,
        createdAtMs = created_at,
        expiresAtMs = expires_at,
        claimedAtMs = claimed_at,
        status = parsePairingStatus(status)
    )
}
