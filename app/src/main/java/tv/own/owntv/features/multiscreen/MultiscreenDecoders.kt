package tv.own.owntv.features.multiscreen

/**
 * Which Multiscreen tiles hold a video decoder. Every visible tile does; a tile hidden behind a fullscreen
 * one does not. A hidden tile that kept its decoder was moved onto a placeholder surface and back on every
 * fullscreen round trip, and on the onn a few round trips with four tiles exhausted the hardware codec memory.
 *
 * None does while the app is in the background (Home, the screensaver, TV off): 4.6.0 kept every tile
 * decoding, and the focused one playing sound, behind the Android TV home screen.
 */
internal object MultiscreenDecoders {

    /**
     * The fullscreen tile alone while one is shown (and still in the grid), otherwise every tile — and no
     * tile at all while the app is not [visible]. Coming back, the same rule picks the tiles to resume, so a
     * layout left in fullscreen resumes only its fullscreen tile.
     */
    fun activeIds(tileIds: List<Long>, fullscreenId: Long?, visible: Boolean = true): Set<Long> = when {
        !visible -> emptySet()
        fullscreenId != null && fullscreenId in tileIds -> setOf(fullscreenId)
        else -> tileIds.toSet()
    }

    /** The fullscreen tile, or null once it has left the grid (removed) so the grid is shown again. */
    fun shownFullscreen(tileIds: List<Long>, fullscreenId: Long?): Long? = fullscreenId?.takeIf { it in tileIds }
}
