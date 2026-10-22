package tv.own.owntv.features.multiscreen

import androidx.media3.exoplayer.ExoPlayer
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.player.SurroundMode

/**
 * The tile players behind the Multiscreen grid. On the onn, fullscreen and back with four tiles moved every
 * hidden decoder onto a placeholder surface and back; the second round trip exhausted the hardware codec
 * memory and every tile failed. Hidden tiles are now parked (stopped: no decoder, no stream) and resumed
 * once the grid is back. `stop()` is what releases an ExoPlayer's MediaCodec; `prepare()` restarts it.
 */
class MultiscreenStateTest {

    private fun ch(id: Long) = ChannelEntity(
        id = id, sourceId = 1, categoryId = null, name = "Ch$id", streamUrl = "http://x/$id.ts", remoteId = "$id",
    )

    /** Every player the factory built, by channel, in order. */
    private val built = mutableListOf<Pair<Long, ExoPlayer>>()
    private val state = MultiscreenState { channel, _, _, _ ->
        mockk<ExoPlayer>(relaxed = true).also { built += channel.id to it }
    }

    private fun show(vararg ids: Long): List<ExoPlayer> =
        ids.map { state.getOrCreatePlayer(ch(it), null, SurroundMode.AUTO, true) }

    private fun grid(vararg ids: Long) = state.setActive(MultiscreenDecoders.activeIds(ids.toList(), null))
    private fun fullscreen(id: Long, vararg ids: Long) = state.setActive(MultiscreenDecoders.activeIds(ids.toList(), id))

    // ---- Grid playback ----

    @Test
    fun `two tiles each get one player and both keep playing`() {
        val (a, b) = show(1, 2)
        grid(1, 2)
        assertEquals(2, built.size)
        listOf(a, b).forEach { verify(exactly = 0) { it.stop(); it.prepare(); it.release() } }
    }

    @Test
    fun `four tiles each get one player and all keep playing`() {
        val players = show(1, 2, 3, 4)
        grid(1, 2, 3, 4)
        assertEquals(4, built.size)
        players.forEach { verify(exactly = 0) { it.stop() } }
        assertFalse((1L..4L).any(state::isParked))
    }

    @Test
    fun `asking for the composed tile again reuses its player`() {
        val first = show(1).single()
        val again = show(1).single()
        assertTrue(first === again)
        assertEquals(1, built.size)
    }

    // ---- Fullscreen ----

    @Test
    fun `fullscreen parks every hidden tile and leaves the fullscreen one alone`() {
        val (a, b, c, d) = show(1, 2, 3, 4)
        fullscreen(2, 1, 2, 3, 4)
        listOf(a, c, d).forEach { verify(exactly = 1) { it.stop() } }
        verify(exactly = 0) { b.stop() }
        assertEquals(setOf(1L, 3L, 4L), (1L..4L).filter(state::isParked).toSet())
    }

    @Test
    fun `back to the grid resumes the parked tiles once, without rebuilding any player`() {
        val (a, b, c, d) = show(1, 2, 3, 4)
        fullscreen(2, 1, 2, 3, 4)
        grid(1, 2, 3, 4)
        listOf(a, c, d).forEach { verify(exactly = 1) { it.prepare() } }
        verify(exactly = 0) { b.prepare(); b.stop() }
        assertEquals(4, built.size)
        assertFalse((1L..4L).any(state::isParked))
    }

    @Test
    fun `repeated fullscreen round trips never hold more than one decoder in fullscreen or leak players`() {
        val players = show(1, 2, 3, 4)
        repeat(10) { round ->
            val target = 1L + round % 4
            fullscreen(target, 1, 2, 3, 4)
            assertEquals("round $round: only the fullscreen tile is running", 3, (1L..4L).count(state::isParked))
            grid(1, 2, 3, 4)
            assertEquals("round $round: the grid runs every tile", 0, (1L..4L).count(state::isParked))
        }
        // Each tile was fullscreen in 2 or 3 of the 10 rounds and hidden in the rest: one stop and one
        // prepare per hidden round, never more.
        players.forEachIndexed { i, p ->
            val hiddenRounds = (0 until 10).count { 1L + it % 4 != i + 1L }
            verify(exactly = hiddenRounds) { p.stop() }
            verify(exactly = hiddenRounds) { p.prepare() }
        }
        assertEquals("no player was rebuilt", 4, built.size)
        players.forEach { verify(exactly = 0) { it.release() } }
    }

    @Test
    fun `parking is idempotent - a recomposition with the same tiles does nothing more`() {
        val (a, b) = show(1, 2)
        repeat(3) { fullscreen(1, 1, 2) }
        verify(exactly = 1) { b.stop() }
        repeat(3) { grid(1, 2) }
        verify(exactly = 1) { b.prepare() }
        verify(exactly = 0) { a.stop(); a.prepare() }
    }

    @Test
    fun `two-tile fullscreen round trip`() {
        val (a, b) = show(1, 2)
        fullscreen(1, 1, 2)
        grid(1, 2)
        verify(exactly = 1) { b.stop(); b.prepare() }
        verify(exactly = 0) { a.stop(); a.prepare() }
    }

    // ---- Replace and remove ----

    @Test
    fun `replacing the fullscreen tile frees its player and keeps the hidden tiles parked`() {
        val (a, b, c) = show(1, 2, 3)
        fullscreen(1, 1, 2, 3)
        state.release(1) // Replace frees the old tile first
        verify(exactly = 1) { a.release() }
        val n = show(9).single()
        fullscreen(9, 9, 2, 3)
        verify(exactly = 0) { n.stop() }
        verify(exactly = 1) { b.stop(); c.stop() } // not stopped again
        grid(9, 2, 3)
        verify(exactly = 1) { b.prepare(); c.prepare() }
        verify(exactly = 0) { n.prepare() } // built prepared; never re-prepared
        assertEquals(setOf(9L, 2L, 3L), state.players.keys.toSet())
    }

    @Test
    fun `replacing a grid tile frees the old player before the new one is built`() {
        val (a, _) = show(1, 2)
        state.release(1)
        verify(exactly = 1) { a.release() }
        assertEquals(listOf(1L, 2L), built.map { it.first })
        show(5)
        assertEquals(listOf(1L, 2L, 5L), built.map { it.first })
        assertEquals(setOf(2L, 5L), state.players.keys.toSet())
    }

    @Test
    fun `removing a parked tile releases it and the grid does not resume it`() {
        val (_, b, c) = show(1, 2, 3)
        fullscreen(1, 1, 2, 3)
        state.releaseUnused(setOf(1L, 3L)) // tile 2 removed from the fullscreen tile's menu
        verify(exactly = 1) { b.release() }
        assertFalse(state.isParked(2))
        grid(1, 3)
        verify(exactly = 0) { b.prepare() }
        verify(exactly = 1) { c.prepare() }
    }

    // ---- Back and exit ----

    @Test
    fun `leaving Multiscreen releases every player, parked ones included`() {
        val players = show(1, 2, 3, 4)
        fullscreen(4, 1, 2, 3, 4)
        state.releaseAll()
        players.forEach { verify(exactly = 1) { it.release() } }
        assertTrue(state.players.isEmpty())
        assertFalse((1L..4L).any(state::isParked))
    }

    @Test
    fun `back from fullscreen to an emptied grid leaves nothing parked or running`() {
        show(1, 2)
        fullscreen(1, 1, 2)
        state.releaseUnused(emptySet())
        grid()
        assertTrue(state.players.isEmpty())
        assertFalse(state.isParked(1) || state.isParked(2))
    }

    // ---- Failure recovery ----

    @Test
    fun `a failed tile is not retried by recompositions - only when it comes back from parking`() {
        val (a, b) = show(1, 2)
        repeat(5) { grid(1, 2) } // a visible tile in error waits for its Retry button
        verify(exactly = 0) { a.prepare(); b.prepare() }
        fullscreen(1, 1, 2)
        grid(1, 2)
        verify(exactly = 1) { b.prepare() } // a hidden failed tile restarts fresh on the way back
    }

    // ---- Audio ----

    @Test
    fun `audio follows the focused tile and every other tile is muted`() {
        val (a, b, c) = show(1, 2, 3)
        state.applyAudioFocus(2)
        verify { b.volume = 1f }
        verify { a.volume = 0f; c.volume = 0f }
        state.applyAudioFocus(3)
        verify { c.volume = 1f }
        verify { b.volume = 0f }
    }

    @Test
    fun `fullscreen tile keeps audio while the parked tiles stay muted`() {
        val (a, b) = show(1, 2)
        fullscreen(1, 1, 2)
        state.applyAudioFocus(1)
        verify { a.volume = 1f }
        verify { b.volume = 0f }
        verify(exactly = 0) { b.volume = 1f }
    }
}
