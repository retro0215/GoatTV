package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.features.sports.live.GameCenterTestData.NFL
import tv.own.owntv.features.sports.live.GameCenterTestData.UFC
import tv.own.owntv.features.sports.live.GameCenterTestData.detail
import tv.own.owntv.features.sports.live.GameCenterTestData.game

class SportsGameCenterDevModeTest {

    private val fixtureId = SportsGameCenterFixtures.FIXTURE_ID_PREFIX + "nfl_live"

    private val fake = object : SportsGameCenterFixtures {
        override val leagues = listOf(NFL, UFC)
        override fun events(nowMs: Long) = listOf(game(id = fixtureId))
        override fun detail(eventId: String): GameCenterDetail? = if (eventId == fixtureId) GameCenterTestData.detail(eventId) else null
    }

    @After
    fun tearDown() { SportsGameCenterDevMode.installed = null }

    @Test
    fun `fixture mode is never active in a production build, even if installed`() {
        SportsGameCenterDevMode.installed = { fake }
        assertNull(SportsGameCenterDevMode.active(isDebugBuild = false))
        assertSame(fake, SportsGameCenterDevMode.active(isDebugBuild = true))
    }

    @Test
    fun `nothing installed - no fixtures and the production config has no detail source`() = runBlocking {
        assertNull(SportsGameCenterDevMode.active(isDebugBuild = true))
        val config = SportsGameCenterConfig.create(fixtures = null)
        assertNull(config.fixtures)
        assertSame(GameCenterDetailSource.None, config.detailSource)
        assertNull(config.detailSource.detail(fixtureId))
    }

    @Test
    fun `fixture config answers only fixture events`() = runBlocking {
        val config = SportsGameCenterConfig.create(fixtures = fake)
        assertEquals(fixtureId, config.detailSource.detail(fixtureId)?.eventId)
        assertNull(config.detailSource.detail("evt_real"))
    }

    @Test
    fun `merge - fixtures lead Popular, real events and leagues kept`() {
        val real = game(id = "evt_real")
        val slate = SportsSlate(listOf(NFL), listOf("evt_real"), mapOf("evt_real" to real), "c", 1L, 1L)
        val merged = SportsGameCenterFixtureMerge.withFixtures(SportsLiveState.Content(slate, stale = true), fake, 0L) as SportsLiveState.Content
        assertEquals(listOf(fixtureId, "evt_real"), merged.slate.popularIds)
        assertEquals(setOf("evt_real", fixtureId), merged.slate.eventsById.keys)
        assertEquals(listOf("nfl", "ufc"), merged.slate.leagues.map { it.id }) // nfl not duplicated
        assertTrue(merged.stale)
        assertEquals("c", merged.slate.cursor)
    }

    @Test
    fun `merge - fixtures render even before the API answers`() {
        val merged = SportsGameCenterFixtureMerge.withFixtures(SportsLiveState.Loading, fake, 0L) as SportsLiveState.Content
        assertEquals(listOf(fixtureId), merged.slate.popularIds)
        val sections = SportsSlateLogic.sections(merged.slate, "", "Popular")
        assertEquals(SportsSlateLogic.POPULAR_KEY, sections.first().key)
    }
}
