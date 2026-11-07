package tv.own.owntv.features.live

/**
 * When a channel's Now / Next stops being true. [LiveViewModel.nowNext] used to be read only when the
 * focused channel changed, so the preview and the player's guide card kept showing a programme long
 * after it ended. Instead of polling, it now sleeps until the earliest of these boundaries and reads
 * once more; [LiveEpgReader]'s cache also refuses an entry a boundary has made stale.
 */
internal object NowNextClock {
    /** Read just after the boundary, so the programme starting at it is already the current one. */
    const val SLACK_MS = 1_000L

    /** The first start/stop after [now] at which [data] changes, or null (no guide: nothing to wait for). */
    fun nextChangeAt(data: EpgNowNext?, now: Long): Long? {
        if (data == null) return null
        return listOfNotNull(data.now?.stopMs, data.next?.startMs)
            .filter { it > now }
            .minOrNull()
    }

    /** How long to sleep before re-reading for [changeAt]. */
    fun delayUntil(changeAt: Long, now: Long): Long = (changeAt - now).coerceAtLeast(0L) + SLACK_MS

    /** True once [now] has passed a boundary of [data]: its "Now" ended or its "Next" began. */
    fun isStale(data: EpgNowNext, now: Long): Boolean =
        (data.now != null && data.now.stopMs <= now) || (data.next != null && data.next.startMs <= now)
}
