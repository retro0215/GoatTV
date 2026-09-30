package tv.own.owntv.features.multiscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.features.multiscreen.MultiscreenDirection.DOWN
import tv.own.owntv.features.multiscreen.MultiscreenDirection.LEFT
import tv.own.owntv.features.multiscreen.MultiscreenDirection.RIGHT
import tv.own.owntv.features.multiscreen.MultiscreenDirection.UP

/**
 * Multiscreen's grid shapes (Mobile Multiview's Auto layouts for 1–4 real tiles), the D-pad
 * neighbours drawn from them, and the store's Move (swap) and Replace rules.
 */
class MultiscreenLayoutTest {

    private val w = 1920f
    private val h = 1080f

    private fun assertRect(expected: TileRect, actual: TileRect) {
        assertEquals(expected.left, actual.left, 0.01f)
        assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.width, actual.width, 0.01f)
        assertEquals(expected.height, actual.height, 0.01f)
    }

    // --- Geometry -----------------------------------------------------------------------------------

    @Test fun `no tiles means no rects`() {
        assertTrue(MultiscreenLayout.rects(0, w, h).isEmpty())
        assertTrue(MultiscreenLayout.rects(2, 0f, h).isEmpty())
    }

    @Test fun `one tile fills the whole area`() {
        val r = MultiscreenLayout.rects(1, w, h)
        assertEquals(1, r.size)
        assertRect(TileRect(0f, 0f, w, h), r[0])
    }

    @Test fun `two tiles are a true 50-50 split at full height`() {
        val r = MultiscreenLayout.rects(2, w, h)
        assertEquals(2, r.size)
        assertRect(TileRect(0f, 0f, w / 2, h), r[0])
        assertRect(TileRect(w / 2, 0f, w / 2, h), r[1])
    }

    @Test fun `three tiles are a two-thirds hero left and two stacked right`() {
        val r = MultiscreenLayout.rects(3, w, h)
        assertEquals(3, r.size)
        assertRect(TileRect(0f, 0f, w * 2 / 3, h), r[0])
        assertRect(TileRect(w * 2 / 3, 0f, w / 3, h / 2), r[1])
        assertRect(TileRect(w * 2 / 3, h / 2, w / 3, h / 2), r[2])
    }

    @Test fun `four tiles are an equal two by two`() {
        val r = MultiscreenLayout.rects(4, w, h)
        assertEquals(4, r.size)
        assertRect(TileRect(0f, 0f, w / 2, h / 2), r[0])
        assertRect(TileRect(w / 2, 0f, w / 2, h / 2), r[1])
        assertRect(TileRect(0f, h / 2, w / 2, h / 2), r[2])
        assertRect(TileRect(w / 2, h / 2, w / 2, h / 2), r[3])
    }

    @Test fun `every layout covers the area exactly without overlap`() {
        for (n in 1..4) {
            val r = MultiscreenLayout.rects(n, w, h)
            assertEquals("area for $n", w * h, r.sumOf { (it.width * it.height).toDouble() }.toFloat(), 1f)
            for (i in r.indices) for (j in r.indices) if (i < j) {
                val a = r[i]; val b = r[j]
                val overlapW = minOf(a.right, b.right) - maxOf(a.left, b.left)
                val overlapH = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
                assertFalse("tiles $i and $j overlap in the $n layout", overlapW > 0.01f && overlapH > 0.01f)
            }
        }
    }

    @Test fun `counts past four never grow a fifth cell`() {
        assertEquals(4, MultiscreenLayout.rects(5, w, h).size)
    }

    // --- D-pad neighbours ---------------------------------------------------------------------------

    private fun n(count: Int, from: Int, dir: MultiscreenDirection) =
        MultiscreenLayout.neighbour(MultiscreenLayout.rects(count, w, h), from, dir)

    @Test fun `a single tile has no neighbours`() {
        MultiscreenDirection.entries.forEach { assertNull(n(1, 0, it)) }
        assertTrue(MultiscreenLayout.isTopRow(MultiscreenLayout.rects(1, w, h), 0))
    }

    @Test fun `two tiles move left and right only`() {
        assertEquals(1, n(2, 0, RIGHT))
        assertEquals(0, n(2, 1, LEFT))
        assertNull(n(2, 0, LEFT))
        assertNull(n(2, 1, RIGHT))
        assertNull(n(2, 0, UP)); assertNull(n(2, 0, DOWN))
        assertNull(n(2, 1, UP)); assertNull(n(2, 1, DOWN))
    }

    @Test fun `three tiles - right from the hero reaches the top right tile`() {
        assertEquals(1, n(3, 0, RIGHT))
        assertNull(n(3, 0, LEFT)); assertNull(n(3, 0, UP)); assertNull(n(3, 0, DOWN))
    }

    @Test fun `three tiles - left from either right tile returns to the hero`() {
        assertEquals(0, n(3, 1, LEFT))
        assertEquals(0, n(3, 2, LEFT))
    }

    @Test fun `three tiles - up and down move between the right tiles`() {
        assertEquals(2, n(3, 1, DOWN))
        assertEquals(1, n(3, 2, UP))
        assertNull(n(3, 1, UP))
        assertNull(n(3, 2, DOWN))
        assertNull(n(3, 1, RIGHT)); assertNull(n(3, 2, RIGHT))
    }

    @Test fun `four tiles navigate as a two by two`() {
        assertEquals(1, n(4, 0, RIGHT)); assertEquals(2, n(4, 0, DOWN))
        assertEquals(0, n(4, 1, LEFT)); assertEquals(3, n(4, 1, DOWN))
        assertEquals(0, n(4, 2, UP)); assertEquals(3, n(4, 2, RIGHT))
        assertEquals(1, n(4, 3, UP)); assertEquals(2, n(4, 3, LEFT))
        assertNull(n(4, 0, UP)); assertNull(n(4, 0, LEFT))
        assertNull(n(4, 3, DOWN)); assertNull(n(4, 3, RIGHT))
    }

    @Test fun `the top row is what UP leaves for the control strip`() {
        val three = MultiscreenLayout.rects(3, w, h)
        assertTrue(MultiscreenLayout.isTopRow(three, 0))
        assertTrue(MultiscreenLayout.isTopRow(three, 1))
        assertFalse(MultiscreenLayout.isTopRow(three, 2))
        val four = MultiscreenLayout.rects(4, w, h)
        assertEquals(listOf(true, true, false, false), (0..3).map { MultiscreenLayout.isTopRow(four, it) })
    }

    @Test fun `neighbours depend on the shape not the pixel size`() {
        for (count in 1..4) for (from in 0 until count) for (dir in MultiscreenDirection.entries) {
            assertEquals(
                MultiscreenLayout.neighbour(MultiscreenLayout.rects(count, 1600f, 900f), from, dir),
                MultiscreenLayout.neighbour(MultiscreenLayout.rects(count, 1280f, 700f), from, dir),
            )
        }
    }

    // --- Store: Move (swap) and Replace ------------------------------------------------------------

    private fun ch(id: Long) = ChannelEntity(
        id = id, sourceId = 1, categoryId = null, name = "Ch$id", streamUrl = "http://x/$id.ts", remoteId = "$id",
    )

    private fun storeOf(vararg ids: Long) = MultiscreenStore().apply { ids.forEach { addChannel(ch(it)) } }

    private fun MultiscreenStore.ids() = channels.value.map { it.id }

    @Test fun `move swaps two tiles and leaves the rest in place`() {
        // A B / C D, move A onto D -> D B / C A
        val store = storeOf(1, 2, 3, 4)
        store.swapChannels(0, 3)
        assertEquals(listOf(4L, 2L, 3L, 1L), store.ids())
    }

    @Test fun `swap keeps the sound with the same channel`() {
        val store = storeOf(1, 2, 3, 4)
        store.setAudioFocus(0)
        store.swapChannels(0, 3)
        assertEquals(3, store.audioFocusIndex.value)
        store.setAudioFocus(1)
        store.swapChannels(0, 3)
        assertEquals(1, store.audioFocusIndex.value)
    }

    @Test fun `swap with itself or out of range changes nothing`() {
        val store = storeOf(1, 2, 3)
        store.swapChannels(1, 1)
        store.swapChannels(0, 7)
        assertEquals(listOf(1L, 2L, 3L), store.ids())
    }

    @Test fun `replace keeps the tile position and changes only that tile`() {
        val store = storeOf(1, 2, 3)
        store.setAudioFocus(1)
        assertTrue(store.replaceChannel(1, ch(9)))
        assertEquals(listOf(1L, 9L, 3L), store.ids())
        assertEquals(1, store.audioFocusIndex.value)
    }

    @Test fun `replace refuses a channel another tile already shows`() {
        val store = storeOf(1, 2, 3)
        assertFalse(store.replaceChannel(0, ch(3)))
        assertEquals(listOf(1L, 2L, 3L), store.ids())
    }

    @Test fun `replace with the same channel is a no-op success`() {
        val store = storeOf(1, 2)
        assertTrue(store.replaceChannel(0, ch(1)))
        assertEquals(listOf(1L, 2L), store.ids())
    }

    @Test fun `replace at a bad index is refused`() {
        val store = storeOf(1)
        assertFalse(store.replaceChannel(3, ch(9)))
        assertEquals(listOf(1L), store.ids())
    }

    @Test fun `every device allows four tiles from one shared maximum`() {
        val store = MultiscreenStore()
        assertEquals(4, MultiscreenLayout.MAX_TILES)
        assertEquals(MultiscreenLayout.MAX_TILES, store.maxTiles)
        for (id in 1L..4L) {
            assertTrue("add $id", store.addChannel(ch(id)))
            assertEquals(id.toInt(), store.channels.value.size)
        }
    }

    @Test fun `a fifth channel is refused and the four stay`() {
        val store = storeOf(1, 2, 3, 4)
        assertFalse(store.addChannel(ch(5)))
        assertEquals(listOf(1L, 2L, 3L, 4L), store.ids())
    }
}
