package tv.own.owntv.core.customize

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Category moves in Customize while one playlist is selected must keep every other playlist's order
 * (upstream OwnTV_Core 1de78b1e). Keys are `<sourceId>:<remoteId>`, custom categories `custom:<id>`.
 */
class MergeCategoryOrderTest {

    private val playlistA = listOf("1:news", "1:sport", "1:kids")
    private val playlistB = listOf("2:films", "2:music", "2:docs")

    @Test
    fun `moving one playlist's categories keeps the other playlist's order`() {
        val stored = playlistA + playlistB
        // Playlist B selected: Kids is not on screen. Move Docs to the top of B.
        val merged = mergeCategoryOrder(stored, listOf("2:docs", "2:films", "2:music"))
        assertEquals(listOf("1:news", "1:sport", "1:kids", "2:docs", "2:films", "2:music"), merged)
    }

    @Test
    fun `the other playlist is not erased (the bug)`() {
        val stored = playlistA + playlistB
        val merged = mergeCategoryOrder(stored, listOf("1:kids", "1:news", "1:sport"))
        // Before 4.6.1 the stored order became just A's three keys and B's order was lost.
        assertEquals(listOf("1:kids", "1:news", "1:sport", "2:films", "2:music", "2:docs"), merged)
    }

    @Test
    fun `the moved block goes where its first key already was`() {
        val stored = listOf("2:films", "1:news", "2:music", "1:sport")
        val merged = mergeCategoryOrder(stored, listOf("1:sport", "1:news"))
        assertEquals(listOf("2:films", "1:sport", "1:news", "2:music"), merged)
    }

    @Test
    fun `a custom category shared by all playlists is not pulled ahead of another playlist's folders`() {
        val stored = listOf("2:films", "custom:fav", "1:news", "1:sport")
        // Playlist A selected: its rail shows the custom category too. Move Sport to the top of A's rail.
        val merged = mergeCategoryOrder(stored, listOf("1:sport", "custom:fav", "1:news"))
        assertEquals(listOf("2:films", "1:sport", "custom:fav", "1:news"), merged)
    }

    @Test
    fun `nothing stored yet keeps the order on screen`() {
        assertEquals(playlistA, mergeCategoryOrder(emptyList(), playlistA))
    }

    @Test
    fun `keys on screen that were never stored go after the stored ones`() {
        val merged = mergeCategoryOrder(playlistB, listOf("1:sport", "1:news"))
        assertEquals(playlistB + listOf("1:sport", "1:news"), merged)
    }

    @Test
    fun `all playlists on screen gives exactly the new order`() {
        val all = (playlistA + playlistB).reversed()
        assertEquals(all, mergeCategoryOrder(playlistA + playlistB, all))
    }
}
