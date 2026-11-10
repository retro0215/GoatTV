package tv.own.owntv.core.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.database.dao.ProviderMetadataDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProviderMetadataEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.parser.XtEpisode
import tv.own.owntv.core.parser.XtProviderMetadata
import tv.own.owntv.core.parser.XtSeriesInfo
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.stalker.StalkerAuthManager

/**
 * Opening a show reads its episodes from the cache while it is fresh — before 4.6.1 the provider was
 * asked on every open and the cache only decided whether the answer was stored. Synthetic panel only.
 */
class SeriesEpisodeCacheTest {

    private val now = System.currentTimeMillis()
    private val minute = 60_000L

    private val seriesDao = mockk<SeriesDao>(relaxed = true)
    private val metadataDao = mockk<ProviderMetadataDao>(relaxed = true)
    private val sourceDao = mockk<SourceDao>()
    private val xtream = mockk<XtreamClient>()
    private val stalkerAuth = mockk<StalkerAuthManager>(relaxed = true)

    private val repo = SeriesRepository(seriesDao, metadataDao, sourceDao, xtream, mockk(relaxed = true), mockk(relaxed = true), stalkerAuth)

    private val xtreamSource = SourceEntity(id = 1, name = "Synthetic", type = SourceType.XTREAM, url = "http://panel.invalid", username = "u", password = "p")
    private val stalkerSource = SourceEntity(id = 2, name = "Synthetic portal", type = SourceType.STALKER, url = "http://portal.invalid/c/", mac = "00:1A:79:00:00:01")
    private val m3uSource = SourceEntity(id = 3, name = "Synthetic list", type = SourceType.M3U, url = "http://list.invalid/get.m3u")

    private val info = XtProviderMetadata(
        title = "Synthetic Show", plot = "Plot.", rating = 7.5, releaseDate = "2019-07-26", year = 2019, genre = "Drama",
        durationSecs = null, director = "Someone", actors = "A, B", trailer = "abc123",
        backdropUrls = listOf("http://img.invalid/b1.jpg", "http://img.invalid/b2.jpg"), posterUrl = "http://img.invalid/p.jpg", tmdbId = "76479",
    )

    private fun show(sourceId: Long = 1, stamp: Long, id: Long = 10) =
        SeriesEntity(id = id, sourceId = sourceId, name = "Synthetic Show", remoteId = "500", episodesSyncedAt = stamp)

    private fun given(series: SeriesEntity, source: SourceEntity, cachedEpisodes: Int) {
        coEvery { seriesDao.episodeCount(series.id) } returns cachedEpisodes
        coEvery { seriesDao.getSeriesById(series.id) } returns series
        coEvery { sourceDao.getById(source.id) } returns source
        coEvery { seriesDao.episodesBySeriesOnce(series.id) } returns emptyList()
        coEvery { xtream.getSeriesInfo(any(), any()) } returns XtSeriesInfo(
            listOf(XtEpisode("9001", 1, 1, "Pilot", "mkv"), XtEpisode("9002", 1, 2, "Second", "mkv")),
            info,
        )
        every { xtream.seriesEpisodeUrl(any(), any(), any()) } answers { "http://panel.invalid/series/x/y/${secondArg<String>()}.mkv" }
    }

    private fun cachedInfo(series: SeriesEntity) = ProviderMetadataEntity(
        key = repo.getCacheKey(series), sourceId = series.sourceId, remoteId = series.remoteId!!,
        title = info.title, plot = info.plot, rating = info.rating, releaseDate = info.releaseDate, year = info.year,
        genre = info.genre, durationSecs = info.durationSecs, director = info.director, actors = info.actors,
        trailer = info.trailer, backdropUrl = info.backdropUrls.first(), posterUrl = info.posterUrl, tmdbId = info.tmdbId,
        updatedAt = now,
    )

    @Test
    fun `a fresh cache answers without the provider, with the cached info block`() = runBlocking {
        val s = show(stamp = now - 10 * minute)
        given(s, xtreamSource, cachedEpisodes = 12)
        coEvery { metadataDao.getMetadata(repo.getCacheKey(s)) } returns cachedInfo(s)

        val result = repo.loadEpisodesWithInfo(s)

        coVerify(exactly = 0) { xtream.getSeriesInfo(any(), any()) }
        coVerify(exactly = 0) { seriesDao.upsertEpisodes(any()) }
        assertEquals("Plot.", result?.plot)
        assertEquals("A, B", result?.actors)
        assertEquals("abc123", result?.trailer)
        assertEquals("the page shows the first backdrop, which is the one cached", listOf("http://img.invalid/b1.jpg"), result?.backdropUrls)
        assertEquals("76479", result?.tmdbId)
    }

    @Test
    fun `a fresh cache for a panel that sends no info block stays quiet`() = runBlocking {
        val s = show(stamp = now - 10 * minute)
        given(s, xtreamSource, cachedEpisodes = 12)
        coEvery { metadataDao.getMetadata(any()) } returns null
        assertNull(repo.loadEpisodesWithInfo(s))
        coVerify(exactly = 0) { xtream.getSeriesInfo(any(), any()) }
    }

    @Test
    fun `an expired cache asks the provider and stores the answer`() = runBlocking {
        val s = show(stamp = now - 2 * 60 * minute)
        given(s, xtreamSource, cachedEpisodes = 12)
        val result = repo.loadEpisodesWithInfo(s)
        coVerify(exactly = 1) { xtream.getSeriesInfo(xtreamSource, "500") }
        coVerify { seriesDao.upsertEpisodes(match { it.size == 2 }) }
        coVerify { seriesDao.markEpisodesSynced(s.id, any()) }
        coVerify { metadataDao.upsertMetadata(any()) }
        assertEquals("Plot.", result?.plot)
    }

    @Test
    fun `an empty cache always asks the provider (missing episodes recover)`() = runBlocking {
        val s = show(stamp = now - minute)
        given(s, xtreamSource, cachedEpisodes = 0)
        repo.loadEpisodesWithInfo(s)
        coVerify(exactly = 1) { xtream.getSeriesInfo(any(), any()) }
    }

    @Test
    fun `a stamp cleared by a sync asks the provider`() = runBlocking {
        val s = show(stamp = 0)
        given(s, xtreamSource, cachedEpisodes = 12)
        repo.loadEpisodesWithInfo(s)
        coVerify(exactly = 1) { xtream.getSeriesInfo(any(), any()) }
    }

    @Test
    fun `a manual refresh asks the provider even with a fresh cache`() = runBlocking {
        val s = show(stamp = now - minute)
        given(s, xtreamSource, cachedEpisodes = 12)
        repo.loadEpisodesWithInfo(s, forceRefresh = true)
        coVerify(exactly = 1) { xtream.getSeriesInfo(any(), any()) }
        coVerify { seriesDao.upsertEpisodes(any()) }
    }

    @Test
    fun `a failed refresh keeps the cached episodes`() = runBlocking {
        val s = show(stamp = now - 2 * 60 * minute)
        given(s, xtreamSource, cachedEpisodes = 12)
        coEvery { xtream.getSeriesInfo(any(), any()) } throws java.io.IOException("synthetic connection drop")
        assertNull(repo.loadEpisodesWithInfo(s))
        coVerify(exactly = 0) { seriesDao.deleteEpisodesByIds(any()) }
        coVerify(exactly = 0) { seriesDao.markEpisodesSynced(any(), any()) }
    }

    @Test
    fun `a fresh Stalker cache does not reach the portal`() = runBlocking {
        val s = show(sourceId = 2, stamp = now - minute)
        given(s, stalkerSource, cachedEpisodes = 8)
        assertNull(repo.loadEpisodesWithInfo(s))
        coVerify(exactly = 0) { stalkerAuth.withAuthRetry<Any>(any(), any()) }
    }

    @Test
    fun `M3U series are not loaded on open, as before`() = runBlocking {
        val s = show(sourceId = 3, stamp = 0)
        given(s, m3uSource, cachedEpisodes = 0)
        assertNull(repo.loadEpisodesWithInfo(s))
        coVerify(exactly = 0) { xtream.getSeriesInfo(any(), any()) }
    }
}
