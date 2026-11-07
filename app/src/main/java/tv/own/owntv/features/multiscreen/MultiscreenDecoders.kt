package tv.own.owntv.features.multiscreen

/**
 * Which Multiscreen tiles hold a video decoder. Every visible tile does; a tile hidden behind a fullscreen
 * one does not. A hidden tile that kept its decoder was moved onto a placeholder surface and back on every
 * fullscreen round trip, and on the onn a few round trips with four tiles exhausted the hardware codec memory.
 */
internal object MultiscreenDecoders {

    /** The fullscreen tile alone while one is shown (and still in the grid), otherwise every tile. */
    fun activeIds(tileIds: List<Long>, fullscreenId: Long?): Set<Long> =
        if (fullscreenId != null && fullscreenId in tileIds) setOf(fullscreenId) else tileIds.toSet()

    /** The fullscreen tile, or null once it has left the grid (removed) so the grid is shown again. */
    fun shownFullscreen(tileIds: List<Long>, fullscreenId: Long?): Long? = fullscreenId?.takeIf { it in tileIds }
}
