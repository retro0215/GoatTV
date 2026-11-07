package tv.own.owntv.features.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SportsViewModelTest {

    @Test
    fun testMatchSportsSection_Ncaaf() {
        val section = matchSportsSection("USA | NCAAF")
        assertNotNull(section)
        assertEquals(SportsSection.NCAAF, section)
    }

    @Test
    fun testMatchSportsSection_Nfl() {
        val section = matchSportsSection("USA | NFL")
        assertNotNull(section)
        assertEquals(SportsSection.NFL, section)
    }

    @Test
    fun testMatchSportsSection_Nba() {
        val section = matchSportsSection("USA | NBA")
        assertNotNull(section)
        assertEquals(SportsSection.NBA, section)
    }

    @Test
    fun testMatchSportsSection_Mlb() {
        val section = matchSportsSection("USA | MLB")
        assertNotNull(section)
        assertEquals(SportsSection.MLB, section)
    }

    @Test
    fun testMatchSportsSection_Wnba() {
        val section = matchSportsSection("USA | WNBA")
        assertNotNull(section)
        assertEquals(SportsSection.WNBA, section)
    }
}
