package tv.own.owntv.core.catalog.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.versions.EpisodeMatcher.Match
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.model.SourceType

class EpisodeMatcherTest {

    private fun ep(id: Long, season: Int, episode: Int, seriesId: Long = 1, name: String = "Episode title $id", remoteId: String? = "x$id") =
        EpisodeEntity(id = id, seriesId = seriesId, seasonNumber = season, episodeNumber = episode, name = name, streamUrl = "u$id", remoteId = remoteId)

    private fun xtream(eps: List<EpisodeEntity>) = EpisodeMatcher.trustedIndex(eps, SourceType.XTREAM, "Show")

    // ---- Matching by season and episode number, never by provider id ----

    @Test fun `the same season and episode in another version is found despite different provider ids`() {
        val hd = listOf(ep(1, 1, 1, remoteId = "1001"), ep(2, 1, 2, remoteId = "1002"))
        val uhd = listOf(ep(11, 1, 1, seriesId = 2, remoteId = "77"), ep(12, 1, 2, seriesId = 2, remoteId = "78"))
        val m = EpisodeMatcher.match(hd[1], xtream(hd), uhd, xtream(uhd))
        assertEquals(Match.Found(uhd[1]), m)
    }

    @Test fun `a shared provider id never makes two different episodes match`() {
        val hd = listOf(ep(1, 1, 3, remoteId = "500"))
        val uhd = listOf(ep(11, 1, 4, seriesId = 2, remoteId = "500"), ep(12, 1, 5, seriesId = 2, remoteId = "501"))
        assertEquals(Match.Missing, EpisodeMatcher.match(hd[0], xtream(hd), uhd, xtream(uhd)))
    }

    @Test fun `an episode the other version lacks is missing`() {
        val hd = listOf(ep(1, 2, 7))
        val uhd = listOf(ep(11, 2, 1, seriesId = 2), ep(12, 2, 2, seriesId = 2))
        assertEquals(Match.Missing, EpisodeMatcher.match(hd[0], xtream(hd), uhd, xtream(uhd)))
        // A season the other version does not have at all is missing too.
        val noSeason = listOf(ep(21, 1, 1, seriesId = 3))
        assertEquals(Match.Missing, EpisodeMatcher.match(hd[0], xtream(hd), noSeason, xtream(noSeason)))
    }

    // ---- Ambiguous or app-made numbering is never matched ----

    @Test fun `duplicated numbers are not matched on either side`() {
        val hd = listOf(ep(1, 1, 2), ep(2, 1, 2))
        val uhd = listOf(ep(11, 1, 2, seriesId = 2))
        assertEquals(Match.Unmatched, EpisodeMatcher.match(hd[0], xtream(hd), uhd, xtream(uhd)))
        val hd2 = listOf(ep(3, 1, 2))
        val dup = listOf(ep(12, 1, 2, seriesId = 2), ep(13, 1, 2, seriesId = 2))
        assertEquals(Match.Unmatched, EpisodeMatcher.match(hd2[0], xtream(hd2), dup, xtream(dup)))
    }

    @Test fun `episode 0 and a name naming another episode are not trusted`() {
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 0), SourceType.XTREAM, "Show"))
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 4, name = "Show S01E05"), SourceType.XTREAM, "Show"))
        assertTrue(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 5, name = "Show S01E05"), SourceType.XTREAM, "Show"))
        assertTrue(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 5, name = "Show 1x05"), SourceType.XTREAM, "Show"))
    }

    @Test fun `an M3U entry numbered by position (titled only by the show) is not trusted`() {
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 3, name = "Tales From The Crypt", remoteId = null), SourceType.M3U, "Tales from the Crypt"))
        assertTrue(EpisodeMatcher.hasTrustedNumber(ep(1, 1, 3, name = "The Pilot", remoteId = null), SourceType.M3U, "Show"))
    }

    @Test fun `a Stalker season is trusted only when its id states it`() {
        assertTrue(EpisodeMatcher.hasTrustedNumber(ep(1, 2, 5, name = "", remoteId = "280:2:5"), SourceType.STALKER, "Show"))
        // Season numbered by its place in the list: the id says nothing about season 2.
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 2, 5, name = "", remoteId = "9912:5"), SourceType.STALKER, "Show"))
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 2, 5, name = "", remoteId = "280:3:5"), SourceType.STALKER, "Show"))
        assertFalse(EpisodeMatcher.hasTrustedNumber(ep(1, 2, 5, name = "", remoteId = null), SourceType.STALKER, "Show"))
    }

    @Test fun `doubtful rows in the same season make a miss unmatched instead of missing`() {
        val hd = listOf(ep(1, 1, 3))
        val uhd = listOf(ep(11, 1, 1, seriesId = 2), ep(12, 1, 0, seriesId = 2))
        assertEquals(Match.Unmatched, EpisodeMatcher.match(hd[0], xtream(hd), uhd, xtream(uhd)))
        // Doubt in another season does not hide a clear miss.
        val other = listOf(ep(21, 1, 1, seriesId = 3), ep(22, 2, 0, seriesId = 3))
        assertEquals(Match.Missing, EpisodeMatcher.match(hd[0], xtream(hd), other, xtream(other)))
    }

    @Test fun `nothing loaded or nothing trusted is unmatched, never missing`() {
        val hd = listOf(ep(1, 1, 1))
        assertEquals(Match.Unmatched, EpisodeMatcher.match(hd[0], xtream(hd), emptyList(), emptyMap()))
        val stalker = listOf(ep(11, 1, 1, seriesId = 2, name = "", remoteId = "9:1"))
        assertEquals(Match.Unmatched, EpisodeMatcher.match(hd[0], xtream(hd), stalker, EpisodeMatcher.trustedIndex(stalker, SourceType.STALKER, "Show")))
    }

    // ---- Keeping the customer's place across a version switch ----

    @Test fun `switching keeps the same episode when it matches`() {
        val hd = listOf(ep(1, 1, 1), ep(2, 2, 4))
        val uhd = listOf(ep(11, 1, 1, seriesId = 2), ep(12, 2, 4, seriesId = 2))
        val carry = EpisodeMatcher.carry(hd[1], 2, xtream(hd), uhd, xtream(uhd))
        assertEquals(EpisodeMatcher.Carry(2, 12, episodeLost = false), carry)
    }

    @Test fun `a missing episode keeps the season when it exists, else the first one, and says so`() {
        val hd = listOf(ep(1, 2, 9))
        val uhd = listOf(ep(11, 1, 1, seriesId = 2), ep(12, 2, 1, seriesId = 2))
        assertEquals(EpisodeMatcher.Carry(2, null, episodeLost = true), EpisodeMatcher.carry(hd[0], 2, xtream(hd), uhd, xtream(uhd)))
        val onlyS1 = listOf(ep(21, 1, 1, seriesId = 3))
        assertEquals(EpisodeMatcher.Carry(1, null, episodeLost = true), EpisodeMatcher.carry(hd[0], 2, xtream(hd), onlyS1, xtream(onlyS1)))
        assertEquals(EpisodeMatcher.Carry(1, null, episodeLost = false), EpisodeMatcher.carry(null, 2, xtream(hd), onlyS1, xtream(onlyS1)))
    }

    @Test fun `numbers stated in names are read in both common forms`() {
        assertEquals(EpisodeMatcher.Key(1, 5), EpisodeMatcher.numbersInName("Show S01E05 - Pilot"))
        assertEquals(EpisodeMatcher.Key(2, 13), EpisodeMatcher.numbersInName("Show 2x13"))
        assertNull(EpisodeMatcher.numbersInName("The Pilot"))
    }
}
