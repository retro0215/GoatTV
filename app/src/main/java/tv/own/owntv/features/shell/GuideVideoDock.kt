package tv.own.owntv.features.shell

import tv.own.owntv.player.SurfaceOwnership

/**
 * 4.6.0: the TV Guide's live ExoPlayer preview is the full-screen player's own video view, moved and
 * resized into the preview box. The picture never changes surface.
 *
 * Measured on the onn (Phase 4D, 30 Guide <-> full-screen transitions): every transition moved the decoder
 * to another view's surface (MediaCodec setOutputSurface, buffers migrated to the new window), and 3 of
 * the 30 times the Amlogic decoder stopped producing pictures within a second of the move. Audio, the
 * clock and the buffer went on, no frame was dropped; only a new decoder brought the picture back (2–9 s).
 * Moving the decoder once instead of twice (4.6.0's first handoff) did not change the rate: any move can
 * do it. Resizing a SurfaceView keeps its surface, so here nothing moves — the same path the mini player
 * has always used.
 *
 * mpv keeps its own Guide view, as before. Pure rules, so the shell's wiring can be tested without Compose.
 */
internal object GuideVideoDock {

    /** Where the shell's video view is drawn. */
    enum class Placement { FULL_SCREEN, MINI, GUIDE_PREVIEW }

    /** Whether the Guide's live preview is the shell's ExoPlayer view: the Guide is on screen with its
     *  ExoPlayer preview (not mpv, not the idle placeholder). */
    fun docked(guideOnScreen: Boolean, previewSurface: GuidePlayback.Surface): Boolean =
        guideOnScreen && previewSurface == GuidePlayback.Surface.EXO

    /** Whether the shell's video layer is composed. One slot for all three placements, so its video view is
     *  never rebuilt when the picture moves between them. */
    fun layerShown(fullscreen: Boolean, mini: Boolean, docked: Boolean): Boolean = fullscreen || mini || docked

    /**
     * The placement. Docked before the Guide has laid out its preview box (its first build takes ~1 s on the
     * onn), the picture stays full screen for that moment, without controls, rather than vanishing.
     */
    fun placement(fullscreen: Boolean, mini: Boolean, docked: Boolean, previewKnown: Boolean): Placement = when {
        fullscreen -> Placement.FULL_SCREEN
        mini -> Placement.MINI
        docked && previewKnown -> Placement.GUIDE_PREVIEW
        else -> Placement.FULL_SCREEN
    }

    /** The player controls (and prompts) show only in real full screen. */
    fun controlsShown(fullscreen: Boolean): Boolean = fullscreen

    /** The view that owns the live picture: the shell's player view in full screen and in the Guide; elsewhere
     *  (pages, the mini player) none is named and the last surface offered wins, as before. */
    fun owner(fullscreen: Boolean, docked: Boolean): SurfaceOwnership.Role? =
        if (fullscreen || docked) SurfaceOwnership.Role.PLAYER else null
}
