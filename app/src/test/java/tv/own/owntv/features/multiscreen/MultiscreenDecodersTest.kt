package tv.own.owntv.features.multiscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiscreenDecodersTest {

    private val tiles = listOf(10L, 20L, 30L, 40L)

    @Test
    fun `in the grid every tile keeps its decoder`() {
        assertEquals(setOf(10L, 20L), MultiscreenDecoders.activeIds(listOf(10L, 20L), null))
        assertEquals(tiles.toSet(), MultiscreenDecoders.activeIds(tiles, null))
    }

    @Test
    fun `fullscreen keeps only the fullscreen tile's decoder`() {
        assertEquals(setOf(30L), MultiscreenDecoders.activeIds(tiles, 30L))
    }

    @Test
    fun `a fullscreen tile that left the grid shows the grid again`() {
        assertEquals(tiles.toSet(), MultiscreenDecoders.activeIds(tiles, 99L))
        assertNull(MultiscreenDecoders.shownFullscreen(tiles, 99L))
        assertEquals(20L, MultiscreenDecoders.shownFullscreen(tiles, 20L))
        assertNull(MultiscreenDecoders.shownFullscreen(tiles, null))
    }

    @Test
    fun `no tiles, no decoders`() {
        assertEquals(emptySet<Long>(), MultiscreenDecoders.activeIds(emptyList(), null))
        assertEquals(emptySet<Long>(), MultiscreenDecoders.activeIds(emptyList(), 10L))
    }
}
