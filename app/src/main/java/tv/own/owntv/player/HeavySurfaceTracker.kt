package tv.own.owntv.player

/**
 * Which video surface the last >1080p video was decoded on.
 *
 * A back-to-back 4K-class load on the SAME surface needs that surface recreated first (Realtek panels
 * throw 0x80001000 or stutter on the dirty buffer queue). A surface created after that video ended is
 * already clean. Before 4.6.1 RC6 the player recreated it anyway, purely because the previous video was
 * tall: on the onn, leaving a 4K film and starting the next one rebuilt the brand-new full-screen surface
 * ~40 ms after it appeared, the rebuilt view never reported its surface, and the load sat parked until
 * the 10 s T_OPEN watchdog (RC5 diagnostic).
 *
 * Each attach gets a new number, so nothing here holds a [android.view.Surface] (a destroyed one can't be
 * retained) and a surface the system recreates for the same view still counts as new. Called from the
 * main thread (attach / detach) and mpv's event thread (decoded height), hence the locking.
 */
internal class HeavySurfaceTracker {
    private var nextId = 0L
    /** The attached surface's number; 0 while none is attached. */
    private var currentId = 0L
    /** The surface the last >1080p video decoded on; 0 when none is known. */
    private var heavyId = 0L

    /** A surface was attached: it is a new one, whatever ran before. */
    @Synchronized
    fun onAttach() {
        nextId++
        currentId = nextId
    }

    /** The attached surface went away (view destroyed, or the player dropped it on a reset). */
    @Synchronized
    fun onDetach() {
        currentId = 0L
    }

    /** A video of [heightPx] is decoding on the attached surface. */
    @Synchronized
    fun onDecoded(heightPx: Int) {
        if (heightPx <= 0 || currentId == 0L) return
        if (heightPx > HEAVY_HEIGHT_PX) {
            heavyId = currentId
        } else if (heavyId == currentId) {
            heavyId = 0L // a lighter video has since used this surface: its last session was not 4K-class
        }
    }

    /** Whether the attached surface is the one the last >1080p video decoded on. */
    @Synchronized
    fun heavyOnAttachedSurface(): Boolean = currentId != 0L && currentId == heavyId

    companion object {
        /** Above this height a decoder session leaves the surface needing a rebuild before the next one. */
        const val HEAVY_HEIGHT_PX = 1080
    }
}
