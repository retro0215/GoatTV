package tv.own.owntv.player

/**
 * Phase 4B: keeping the picture alive when the live player's video moves to another view (the TV Guide
 * preview ⇄ full screen).
 *
 * On the onn, the Amlogic decoder sometimes stopped rendering after such a switch: audio went on, the
 * frame count froze, the engine's freeze watchdog reconnected the stream (which renders into the same
 * dead window) and ~30 s later the ladder fell back to mpv. The engine's own notes already name the cure
 * for a decoder that lost its window: a fresh Surface, not a fresh stream. So, after a switch, the
 * engine checks that frames resume and, if they do not, first re-attaches the same surface and then
 * asks for a brand-new one — the same decoder and stream throughout (no re-tune, no second decoder).
 */
internal object SurfaceHandoff {

    /** How long after a switch frames must have moved on. */
    const val CHECK_MS = 1_200L

    enum class Action { NONE, REATTACH, NEW_SURFACE }

    /**
     * What to do [attempt] checks after a switch (0 = the first). Only while the stream is playing with
     * video and has rendered before; a paused, audio-only or not-yet-started stream renders nothing by design.
     */
    fun afterSwitch(framesAtSwitch: Int, framesNow: Int, playingVideo: Boolean, attempt: Int): Action = when {
        !playingVideo || framesNow != framesAtSwitch -> Action.NONE
        attempt == 0 -> Action.REATTACH
        attempt == 1 -> Action.NEW_SURFACE
        else -> Action.NONE // the regular freeze watchdog and the engine ladder take it from here
    }

    /** At most one fresh surface per freeze (reset once frames move again), so recovery can never loop. */
    fun mayRecreate(freshSurfacesSinceFrames: Int): Boolean = freshSurfacesSinceFrames < 1

    /** A switch worth checking: a real, different surface while something is already playing. */
    fun isSwitch(previous: Any?, next: Any?, hasPlayed: Boolean): Boolean =
        next != null && previous != null && next !== previous && hasPlayed
}
