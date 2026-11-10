package tv.own.owntv.features.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity

/** Next and auto-advance stay inside the version that is playing. */
class EpisodeQueuesTest {

    private fun ep(id: Long, seriesId: Long, season: Int, episode: Int) =
        EpisodeEntity(id = id, seriesId = seriesId, seasonNumber = season, episodeNumber = episode, name = "", streamUrl = "u$id")

    // Two versions of one show, both S1E1-2 and S2E1, in one list (as a stale screen list could be).
    private val hd = listOf(ep(1, 10, 1, 2), ep(2, 10, 1, 1), ep(3, 10, 2, 1))
    private val uhd = listOf(ep(11, 20, 1, 1), ep(12, 20, 1, 2), ep(13, 20, 2, 1))
    private val mixed = hd + uhd

    @Test fun `the season queue holds only the playing version, in episode order`() {
        assertEquals(listOf(11L, 12L), EpisodeQueues.seasonQueue(mixed, uhd[0]).map { it.id })
        assertEquals(listOf(2L, 1L), EpisodeQueues.seasonQueue(mixed, hd[0]).map { it.id })
    }

    @Test fun `the next season continues in the same version`() {
        assertEquals(13L, EpisodeQueues.nextSeasonFirst(mixed, uhd[1])?.id)
        assertEquals(3L, EpisodeQueues.nextSeasonFirst(mixed, hd[0])?.id)
        assertNull(EpisodeQueues.nextSeasonFirst(mixed, uhd[2]))
    }
}
