package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.stalker.ReconnectUrlProvider

/**
 * mpv's background restore (Home, screensaver, TV off -> back). A live channel on mpv used to be dropped on
 * the way out and came back to a black screen. Synthetic streams only.
 */
class PlayerBackgroundRestoreTest {

    private val liveUrl = "http://panel.invalid/live/synthUser/synthPass/1234.ts"
    private val filmUrl = "http://panel.invalid/movie/synthUser/synthPass/55.mkv"
    private val meta = MediaMeta(title = "Synthetic Channel", subtitle = "101", logoUrl = "http://img.invalid/1.png", contentKey = "1:LIVE:1234")
    private val provider = ReconnectUrlProvider { "http://panel.invalid/fresh" }

    private fun capture(
        url: String? = liveUrl,
        isLive: Boolean = true,
        position: () -> Long = { error("a live channel has no position to read") },
        isPlaying: Boolean = true,
        muted: Boolean = false,
    ) = PlayerBackgroundRestore.capture(
        url = url, isLive = isLive, meta = meta, positionMs = position, isPlaying = isPlaying, muted = muted,
        prerollSecs = 3, userAgent = "SyntheticUA/1.0", httpHeaders = "Referer: http://panel.invalid",
        reconnectProvider = provider,
    )

    @Test
    fun `a live channel on mpv is kept, with everything needed to re-tune it`() {
        val r = capture(muted = true) as PlayerBackgroundRestore.Live
        assertEquals(liveUrl, r.url)
        assertEquals(meta, r.meta)
        assertTrue("mute state comes back as it was", r.muted)
        assertEquals(3, r.prerollSecs)
        assertEquals("SyntheticUA/1.0", r.userAgent)
        assertEquals("Referer: http://panel.invalid", r.httpHeaders)
        assertSame("a Stalker channel can mint a fresh link", provider, r.reconnectProvider)
    }

    @Test
    fun `a film is still kept paused or playing at its position`() {
        val playing = capture(url = filmUrl, isLive = false, position = { 61_000L }, isPlaying = true) as PlayerBackgroundRestore.Vod
        assertEquals(61_000L, playing.positionMs)
        assertTrue(playing.wasPlaying)
        val paused = capture(url = filmUrl, isLive = false, position = { 5_000L }, isPlaying = false) as PlayerBackgroundRestore.Vod
        assertFalse(paused.wasPlaying)
        assertSame(provider, paused.reconnectProvider)
    }

    @Test
    fun `nothing is kept when the customer had stopped or left the channel`() {
        assertNull(capture(url = null))
        assertNull(capture(url = null, isLive = false, position = { 1L }))
    }

    @Test
    fun `the restore is handed out once`() {
        val slot = BackgroundRestoreSlot()
        slot.remember(capture()!!)
        assertTrue(slot.isPending)
        assertTrue(slot.take(somethingPlaying = false) is PlayerBackgroundRestore.Live)
        assertNull("a second start (or a quick toggle) does not tune again", slot.take(somethingPlaying = false))
        assertFalse(slot.isPending)
    }

    @Test
    fun `something already playing on return wins, and no second stream is opened`() {
        // A launcher deep link, the Guide or a reminder tune got there first.
        val slot = BackgroundRestoreSlot()
        slot.remember(capture()!!)
        assertNull(slot.take(somethingPlaying = true))
        assertFalse("dropped, not kept for later", slot.isPending)
        assertNull(slot.take(somethingPlaying = false))
    }

    @Test
    fun `a profile switch or sign-out never brings back the previous user's channel`() {
        val slot = BackgroundRestoreSlot()
        slot.remember(capture()!!)
        slot.discard()
        assertNull(slot.take(somethingPlaying = false))
    }

    @Test
    fun `the newest background wins`() {
        val slot = BackgroundRestoreSlot()
        slot.remember(capture(url = filmUrl, isLive = false, position = { 1L })!!)
        slot.remember(capture()!!)
        assertTrue(slot.take(somethingPlaying = false) is PlayerBackgroundRestore.Live)
    }

    @Test
    fun `screensaver and TV off follow the same path as Home`() {
        // All three stop the activity: onStop -> onAppBackgrounded, onStart -> onAppForegrounded. The same
        // capture and the same single take apply, whatever hid the app.
        for (cause in listOf("home", "screensaver", "tv-off")) {
            val slot = BackgroundRestoreSlot()
            capture()?.let(slot::remember)
            val back = slot.take(somethingPlaying = false)
            assertTrue("$cause: live channel re-tuned", back is PlayerBackgroundRestore.Live)
        }
    }
}
