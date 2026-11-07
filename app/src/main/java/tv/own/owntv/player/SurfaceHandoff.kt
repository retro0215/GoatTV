package tv.own.owntv.player

/**
 * Keeping the live picture alive when its video moves to another view (TV Guide preview <-> full screen).
 *
 * Phase 4B measured on the onn (Amlogic): after a few Guide <-> full-screen round trips the decoder stops
 * rendering a moment after a surface switch. Audio and the stream go on; re-attaching the surface, a fresh
 * surface and stream reconnects all leave it stuck (ExoPlayer's prepare() reuses the wedged codec), and
 * ~30 s later the ladder fell back to mpv. Only a new decoder on the same stream fixes it — the same cure
 * [LivePreviewEngine] already uses for a decoder that fails before its first frame.
 *
 * Phase 4C: after a switch the engine watches the frame count for [WATCH_CHECKS] checks of [CHECK_MS].
 * A picture that stops while the stream is playing gets one decoder rebuild on the same stream (one decoder
 * at a time, no re-tune of the channel). A second freeze within [REBUILD_COOLDOWN_MS] goes to the regular
 * ladder (reconnect, then mpv), so recovery can never loop.
 *
 * 4.6.0: the onn's freeze follows ANY move of the decoder to another surface (~1 in 10, Phase 4D), so the
 * Guide's preview no longer moves it: it is the full-screen view, resized (see GuideVideoDock). This watch
 * stays for the moves that remain (a page's preview pane to full screen, a recreated surface). It counts
 * every rendered frame across players, so a rebuilt decoder that is still frozen is seen too.
 */
internal object SurfaceHandoff {

    /** Time between frame checks after a switch. */
    const val CHECK_MS = 1_200L

    /** Checks after a switch (~4.8 s): the onn froze within ~2 s of the switch. */
    const val WATCH_CHECKS = 4

    /** A second decoder rebuild for a frozen picture within this long goes to the regular ladder instead. */
    const val REBUILD_COOLDOWN_MS = 60_000L

    enum class Action { NONE, WATCH, REBUILD_DECODER }

    /**
     * What to do at check [check] (0 = the first) after a switch. Frozen means the stream is playing video
     * and not one frame was rendered since the previous check; paused, buffering and audio-only playback
     * render nothing by design and only keep the watch going.
     */
    fun afterCheck(framesBefore: Long, framesNow: Long, playingVideo: Boolean, check: Int): Action = when {
        playingVideo && framesNow == framesBefore -> Action.REBUILD_DECODER
        check + 1 < WATCH_CHECKS -> Action.WATCH
        else -> Action.NONE
    }

    /** Whether a frozen picture may get a decoder rebuild now ([lastRebuildMs] 0 = never). */
    fun mayRebuild(lastRebuildMs: Long, nowMs: Long): Boolean =
        lastRebuildMs == 0L || nowMs - lastRebuildMs >= REBUILD_COOLDOWN_MS

    /** A switch worth watching: a real, different surface while something is already playing. */
    fun isSwitch(previous: Any?, next: Any?, hasPlayed: Boolean): Boolean =
        next != null && previous != null && next !== previous && hasPlayed
}

/**
 * Phase 4C: which view's surface drives the live decoder.
 *
 * The shell's player (full screen or mini), the TV Guide preview and a page's preview pane each host a
 * SurfaceView for the same [LivePreviewEngine], and each handed over its surface when it was created, so
 * the last view created took the decoder. On the onn every Guide <-> full-screen round trip attached 3–4
 * surfaces, including the Guide preview's own surface again ~0.2 s AFTER returning to full screen. Now the
 * shell names the [Role] that owns the picture; a surface from any other role is remembered but kept off
 * the decoder until its role becomes the owner. 4.6.0: the Guide's ExoPlayer preview is the PLAYER view
 * itself (GuideVideoDock), so GUIDE is no longer named as an owner.
 */
object SurfaceOwnership {

    enum class Role { PLAYER, GUIDE, PAGE }

    /** Whether a surface offered by [role] goes to the decoder. No owner set: the last one offered (as before). */
    fun accepts(owner: Role?, role: Role): Boolean = owner == null || owner == role
}

/**
 * The bookkeeping behind [SurfaceOwnership]: the surface each role last offered and the current owner.
 * Says which surface to attach; [LivePreviewEngine] does the attaching. Main thread only.
 */
class SurfaceOwners<S : Any> {
    private val offered = HashMap<SurfaceOwnership.Role, S>()
    var owner: SurfaceOwnership.Role? = null
        private set

    /** A new owner. Returns its surface when it already has a usable one (attach it now), else null. */
    fun setOwner(role: SurfaceOwnership.Role?, usable: (S) -> Boolean): S? {
        if (owner == role) return null
        owner = role
        return role?.let { offered[it] }?.takeIf(usable)
    }

    /** [role]'s view has [surface]. True when it goes to the decoder now. */
    fun offer(role: SurfaceOwnership.Role, surface: S): Boolean {
        offered[role] = surface
        return SurfaceOwnership.accepts(owner, role)
    }

    /** [role]'s view lost [surface]. */
    fun withdraw(role: SurfaceOwnership.Role, surface: S) {
        if (offered[role] === surface) offered.remove(role)
    }
}
