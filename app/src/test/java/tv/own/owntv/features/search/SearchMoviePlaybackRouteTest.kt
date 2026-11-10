package tv.own.owntv.features.search

import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.movies.MovieViewModel
import tv.own.owntv.features.search.SearchViewModel

/**
 * A film found in Search plays through MovieViewModel (saved position, progress saving, history,
 * reconnect), not a second Search-only copy that skipped them.
 */
class SearchMoviePlaybackRouteTest {

    @Test fun `Search has no movie playback of its own`() {
        val names = SearchViewModel::class.java.declaredMethods.map { it.name }
        assertTrue(names.none { it == "playMovie" })
    }

    @Test fun `the movie path exposes the entry Search uses`() {
        val names = MovieViewModel::class.java.declaredMethods.map { it.name }
        assertTrue("playWithSavedPosition" in names)
        assertTrue("chooseVersion" in names)
    }
}
