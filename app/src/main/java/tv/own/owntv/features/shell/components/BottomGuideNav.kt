package tv.own.owntv.features.shell.components

import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Selection and clock rules for the Live TV bottom guide strip, kept out of [BottomTvGuideOverlay] so
 * they can be unit-tested. The strip holds a day of past schedule plus five days ahead; every rule here
 * is about landing on, and staying with, what is airing now.
 */
internal object BottomGuideNav {
    /** Never tick slower than this: the progress bar and "LIVE" badge move with it. */
    const val MAX_TICK_MS = 20_000L

    /** Wake just after a boundary, so the programme that starts at it already counts as live. */
    private const val BOUNDARY_SLACK_MS = 500L

    /**
     * Strip order: by start time, one entry per start. Two guide feeds for the same channel return the
     * same slot twice, which made Right land on a copy of the card already selected.
     */
    fun normalize(programmes: List<EpgProgrammeEntity>): List<EpgProgrammeEntity> =
        programmes.sortedBy { it.startMs }.distinctBy { it.startMs }

    /** The programme airing at [now], or -1. */
    fun liveIndex(programmes: List<EpgProgrammeEntity>, now: Long): Int =
        programmes.indexOfFirst { it.startMs <= now && it.stopMs > now }

    /**
     * Where the strip opens: the live programme, else the next one to start (a gap in the guide), else
     * the most recent one (the guide ran out). Never the oldest entry, which is a day in the past.
     */
    fun openingIndex(programmes: List<EpgProgrammeEntity>, now: Long): Int {
        if (programmes.isEmpty()) return -1
        val live = liveIndex(programmes, now)
        if (live >= 0) return live
        val upcoming = programmes.indexOfFirst { it.startMs > now }
        return if (upcoming >= 0) upcoming else programmes.lastIndex
    }

    /** Left/Right: one programme at a time, stopping at either end. */
    fun step(index: Int, delta: Int, size: Int): Int =
        if (size <= 0) -1 else (index + delta).coerceIn(0, size - 1)

    /**
     * After the clock moves from [previousNow] to [now]: a selection that was on the live programme
     * follows the live programme across the boundary; one the viewer moved elsewhere stays put.
     */
    fun followLive(programmes: List<EpgProgrammeEntity>, selectedIndex: Int, previousNow: Long, now: Long): Int {
        if (selectedIndex !in programmes.indices) return openingIndex(programmes, now)
        val wasOnLive = selectedIndex == openingIndex(programmes, previousNow)
        return if (wasOnLive) openingIndex(programmes, now) else selectedIndex
    }

    /** Delay to the next clock tick: the next start/stop boundary, but never longer than [MAX_TICK_MS]. */
    fun nextTickDelay(programmes: List<EpgProgrammeEntity>, now: Long): Long {
        var boundary = Long.MAX_VALUE
        for (p in programmes) {
            if (p.startMs > now && p.startMs < boundary) boundary = p.startMs
            if (p.stopMs > now && p.stopMs < boundary) boundary = p.stopMs
        }
        if (boundary == Long.MAX_VALUE) return MAX_TICK_MS
        return (boundary - now + BOUNDARY_SLACK_MS).coerceIn(BOUNDARY_SLACK_MS, MAX_TICK_MS)
    }
}

/**
 * Back on the bottom guide. One press = one step: the reminder dialog closes first, then the guide.
 * The press is consumed and the release acts, like the Multiscreen dialogs. Acting on the press let the
 * release reach the activity's back dispatcher, which then closed the guide too; with the guide gone,
 * the next Back left Live TV.
 */
internal class GuideBackKeys {
    enum class Step { NONE, CLOSE_REMINDER_DIALOG, CLOSE_GUIDE }

    private var pressed = false

    fun onPress() {
        pressed = true
    }

    /** The step for a release. A release whose press went elsewhere (before the guide opened) does nothing. */
    fun onRelease(reminderDialogOpen: Boolean): Step {
        if (!pressed) return Step.NONE
        pressed = false
        return stepFor(reminderDialogOpen)
    }

    companion object {
        fun stepFor(reminderDialogOpen: Boolean): Step =
            if (reminderDialogOpen) Step.CLOSE_REMINDER_DIALOG else Step.CLOSE_GUIDE
    }
}
