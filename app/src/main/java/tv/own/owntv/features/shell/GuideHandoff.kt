package tv.own.owntv.features.shell

import tv.own.owntv.player.SurfaceOwnership

/**
 * 4.6.0: moving the live ExoPlayer picture between full screen and the TV Guide's preview in ONE step.
 *
 * Measured on the onn (Phase 4C): the outgoing video view was destroyed before the incoming one existed —
 * about 1 s on the way into the Guide (its first frame takes that long) and 0.2 s on the way back. With no
 * surface, ExoPlayer parks the decoder on an off-screen stand-in surface, so every transition moved the
 * decoder twice, and the onn's decoder froze after some of these moves. Showing the player controls never
 * touched the surface: the extra move happened when the Guide was opened, not when the controls appeared.
 *
 * Now the outgoing view stays until the incoming view's surface is on the decoder:
 *  - [Phase.TO_GUIDE]: the full-screen video (no controls) stays over the Guide while the Guide builds;
 *  - [Phase.TO_PLAYER]: the full-screen video is shown over the Guide first, and the Guide leaves once it
 *    is attached. Keys wait for that moment so none reaches the Guide underneath.
 * Either way the decoder moves once, from one real surface to the other. Same player and decoder, no
 * re-tune; mpv keeps its own path. A timeout falls back to the old order, so a handoff can never hang.
 */
object GuideHandoff {

    enum class Phase { NONE, TO_GUIDE, TO_PLAYER }

    /** Longest wait for the Guide's preview: its first build took ~1 s on the onn, more right after launch. */
    const val TO_GUIDE_TIMEOUT_MS = 4_000L

    /** Longest wait for the full-screen view (~0.2 s on the onn); keys are held meanwhile. */
    const val TO_PLAYER_TIMEOUT_MS = 1_500L

    /** The handoff to start: only on ExoPlayer (the shared live engine) and never on top of another one. */
    fun start(onExo: Boolean, current: Phase, to: Phase): Phase = when {
        !onExo -> Phase.NONE
        current != Phase.NONE -> current
        else -> to
    }

    /** The view whose surface ends the handoff once it is attached. */
    fun target(phase: Phase): SurfaceOwnership.Role? = when (phase) {
        Phase.NONE -> null
        Phase.TO_GUIDE -> SurfaceOwnership.Role.GUIDE
        Phase.TO_PLAYER -> SurfaceOwnership.Role.PLAYER
    }

    fun timeoutMs(phase: Phase): Long = if (phase == Phase.TO_PLAYER) TO_PLAYER_TIMEOUT_MS else TO_GUIDE_TIMEOUT_MS

    /** Whether the shell's video layer (full screen / mini) is composed. The same slot throughout a handoff,
     *  so its video view is never rebuilt when the handoff ends. */
    fun videoLayerShown(playerFullscreenOrMini: Boolean, phase: Phase): Boolean = playerFullscreenOrMini || phase != Phase.NONE

    /** The video layer fills the screen (rather than the mini player's corner). */
    fun videoLayerFullScreen(playerFullscreen: Boolean, phase: Phase): Boolean = playerFullscreen || phase != Phase.NONE

    /** The player controls (and prompts) show only in real full screen, never during a handoff. */
    fun controlsShown(playerFullscreen: Boolean, phase: Phase): Boolean = playerFullscreen && phase == Phase.NONE

    /** Whether the full-screen player owns the picture: in full screen, and already while returning to it. */
    fun playerOwnsPicture(playerFullscreen: Boolean, phase: Phase): Boolean = playerFullscreen || phase == Phase.TO_PLAYER

    /** Keys are held while returning to full screen: the Guide underneath must not act on them. */
    fun keysHeld(phase: Phase): Boolean = phase == Phase.TO_PLAYER
}
