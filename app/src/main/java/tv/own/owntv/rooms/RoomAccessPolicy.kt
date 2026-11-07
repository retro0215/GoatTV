package tv.own.owntv.rooms

enum class RoomAccessState {
    UPCOMING,
    OPEN,
    CLOSED
}

object RoomAccessPolicy {
    fun evaluate(
        status: String?,
        startsAt: Long?,
        endsAt: Long?,
        nowMs: Long = System.currentTimeMillis()
    ): RoomAccessState {
        val lowerStatus = status?.lowercase()

        if (lowerStatus == "ended" || lowerStatus == "disabled") {
            return RoomAccessState.CLOSED
        }

        if (endsAt != null && nowMs >= endsAt) {
            return RoomAccessState.CLOSED
        }

        if (lowerStatus == "scheduled") {
            if (startsAt == null) {
                return RoomAccessState.UPCOMING
            }
            return if (nowMs < startsAt) {
                RoomAccessState.UPCOMING
            } else {
                RoomAccessState.OPEN
            }
        }

        if (lowerStatus == "live") {
            return RoomAccessState.OPEN
        }

        if (startsAt != null && nowMs < startsAt) {
            return RoomAccessState.UPCOMING
        }

        return RoomAccessState.OPEN
    }
}
