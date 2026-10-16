package tv.own.owntv.core.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpisodeEntity
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.repository.planEpisodeMerge

/**
 * `get_series_info` in every layout panels send (upstream OwnTV_Core 1714b33b). Fixtures in
 * src/test/resources/xtream use the real panel field set; the JVM parser runs through the test-only
 * android.util.JsonReader shim.
 */
class XtreamSeriesInfoParsingTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun parse(name: String): XtSeriesInfo =
        javaClass.classLoader!!.getResourceAsStream("xtream/$name")!!.use { client.parseSeriesInfo(it) }

    private fun parseText(json: String): XtSeriesInfo = client.parseSeriesInfo(json.byteInputStream())

    private fun XtSeriesInfo.layout() = episodes.map { Triple(it.id, it.seasonNumber, it.episodeNumber) }

    @Test
    fun `object-keyed seasons still parse as before`() {
        val info = parse("series_info_object_seasons.json")
        assertEquals(
            listOf(Triple("100001", 1, 1), Triple("100002", 1, 2), Triple("100003", 2, 1)),
            info.layout(),
        )
        val first = info.episodes.first()
        assertEquals("S01E01 Pilot", first.title)
        assertEquals("mkv", first.containerExt)
        assertEquals("Plot of S01E01 Pilot.", first.plot)
        assertEquals(1320, first.durationSecs)
        assertEquals("mp4", info.episodes.last().containerExt)
        assertNotNull(info.info)
        assertEquals("A show used as a parser fixture.", info.info!!.plot)
    }

    @Test
    fun `a PHP list of seasons with Specials keeps every episode`() {
        // Seasons 0,1,2 without a gap arrive as [[…],[…],[…]]; the old parser skipped the inner arrays
        // and the show came up empty.
        val info = parse("series_info_array_of_seasons_with_specials.json")
        assertEquals(
            listOf(
                Triple("200001", 0, 1), Triple("200002", 0, 2),
                Triple("200011", 1, 1), Triple("200012", 1, 2),
                Triple("200021", 2, 1),
            ),
            info.layout(),
        )
    }

    @Test
    fun `a flat episode list takes each episode's own season`() {
        assertEquals(
            listOf(Triple("300001", 1, 1), Triple("300002", 3, 1)),
            parse("series_info_flat_array.json").layout(),
        )
    }

    @Test
    fun `a season whose episode list is keyed with a gap is read`() {
        assertEquals(
            listOf(Triple("400001", 1, 1), Triple("400003", 1, 3), Triple("400011", 2, 1)),
            parse("series_info_keyed_season_list.json").layout(),
        )
    }

    @Test
    fun `a keyed season inside a PHP season list is read`() {
        assertEquals(
            listOf(Triple("500001", 0, 1), Triple("500011", 1, 1), Triple("500014", 1, 4)),
            parse("series_info_array_with_keyed_season.json").layout(),
        )
    }

    @Test
    fun `blank, zero and null episode numbers take their position in the season`() {
        assertEquals(
            listOf(Triple("600001", 1, 1), Triple("600002", 1, 2), Triple("600003", 1, 3), Triple("600004", 1, 7)),
            parse("series_info_blank_episode_numbers.json").layout(),
        )
    }

    @Test
    fun `remote ids are exactly the provider's episode ids in every layout`() {
        val ids = listOf(
            "series_info_object_seasons.json", "series_info_array_of_seasons_with_specials.json",
            "series_info_flat_array.json", "series_info_keyed_season_list.json",
            "series_info_array_with_keyed_season.json", "series_info_blank_episode_numbers.json",
        ).flatMap { f -> parse(f).episodes.map { it.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("\\d{6}")) })
    }

    @Test
    fun `empty, null and non-object bodies yield no episodes`() {
        assertTrue(parseText("""{"episodes":[]}""").episodes.isEmpty())
        assertTrue(parseText("""{"episodes":{}}""").episodes.isEmpty())
        assertTrue(parseText("""{"episodes":null,"info":[]}""").episodes.isEmpty())
        assertTrue(parseText("""[]""").episodes.isEmpty())
        assertTrue(parseText("""{"episodes":[{}, [], {"x":1}]}""").episodes.isEmpty())
    }

    @Test
    fun `episodes without an id are skipped, as before`() {
        val info = parseText("""{"episodes":{"1":[{"episode_num":1,"title":"no id"},{"id":"9","episode_num":2}]}}""")
        assertEquals(listOf(Triple("9", 1, 2)), info.layout())
    }

    @Test
    fun `numberBlankEpisodes never touches a real number and counts per season`() {
        fun ep(id: String, season: Int, number: Int) = XtEpisode(id, season, number, "", "mkv")
        val out = numberBlankEpisodes(listOf(ep("s", 0, 0), ep("a", 1, 5), ep("t", 0, 0), ep("b", 1, 0)))
        assertEquals(listOf(1, 5, 2, 2), out.map { it.episodeNumber })
    }

    @Test
    fun `renumbering blank episodes keeps every stored row, so history and resume stay attached`() {
        // Rows an older build stored with episode 0 are matched on the provider's remote id: the merge
        // updates them in place (same row id) instead of deleting and re-inserting.
        val stored = listOf("600001", "600002", "600003").mapIndexed { i, rid ->
            EpisodeEntity(id = 10L + i, seriesId = 1, seasonNumber = 1, episodeNumber = 0, name = "", streamUrl = "u$rid", remoteId = rid)
        }
        val incoming = parse("series_info_blank_episode_numbers.json").episodes.map { e ->
            EpisodeEntity(seriesId = 1, seasonNumber = e.seasonNumber, episodeNumber = e.episodeNumber, name = e.title, streamUrl = "u${e.id}", remoteId = e.id)
        }
        val plan = planEpisodeMerge(stored, incoming)
        assertTrue(plan.deleteIds.isEmpty())
        assertEquals(listOf(10L, 11L, 12L), plan.updates.map { it.id })
        assertEquals(listOf(1, 2, 3), plan.updates.map { it.episodeNumber })
        assertEquals(listOf("600004"), plan.inserts.map { it.remoteId })
    }
}
