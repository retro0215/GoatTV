package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class SportsWatchPresentationTest {

    private val event = GameCenterTestData.game(id = "evt_1", status = SportsEventStatus.LIVE).copy(
        broadcasts = listOf(SportsBroadcast("ESPN", "TV", "NATIONAL", "espn"), SportsBroadcast("ABC", "TV", "NATIONAL", "abc")),
    )

    private fun ch(id: Long, name: String, epg: String? = null) =
        ChannelEntity(id = id, sourceId = 7, name = name, streamUrl = "built-locally", remoteId = "r$id", epgChannelId = epg)

    private fun ref(c: ChannelEntity, networkKey: String? = null, category: String? = "USA | Sports", epg: String? = c.epgChannelId, name: String = c.name, remote: String = c.remoteId!!) =
        SportsChannelRef("sch_secret_${c.id}", remote, epg, name, category, null, networkKey, 97, "network_exact")

    @Test
    fun `rows keep backend rank order, primary before 4K, and expose no implementation details`() {
        val primary = ch(1, "US: ESPN")
        val uhd = ch(2, "US: ESPN 4K UHD")
        val rows = SportsWatchPresentation.rows(event, listOf(ResolvedSportsChannel(ref(primary, "espn"), primary), ResolvedSportsChannel(ref(uhd), uhd)), selectedChannelId = null)
        assertEquals(listOf("US: ESPN", "US: ESPN 4K UHD"), rows.map { it.name })
        assertEquals(listOf(false, true), rows.map { it.uhd })
        assertEquals("ESPN", rows[0].network)
        assertNull(rows[1].network)
        val shown = rows.joinToString { listOfNotNull(it.name, it.network, it.category).joinToString() }
        for (secret in listOf("r1", "sch_secret", "97", "network_exact", "espn.us")) assertFalse(shown.contains(secret))
    }

    @Test
    fun `selected feed is marked`() {
        val a = ch(1, "US: ESPN"); val b = ch(2, "US: ESPN 2")
        val rows = SportsWatchPresentation.rows(event, listOf(ResolvedSportsChannel(ref(a), a), ResolvedSportsChannel(ref(b), b)), selectedChannelId = 2)
        assertEquals(listOf(false, true), rows.map { it.selected })
    }

    @Test
    fun `UHD detection`() {
        assertTrue(SportsWatchPresentation.isUhd("CA: Sportsnet 4K"))
        assertTrue(SportsWatchPresentation.isUhd("Sky Sports Main Event UHD"))
        assertFalse(SportsWatchPresentation.isUhd("US: ESPN FHD"))
        assertFalse(SportsWatchPresentation.isUhd("Channel 4"))
    }

    @Test
    fun `scoreboard overlay from event data only`() {
        val s = SportsWatchPresentation.scoreboard(GameCenterTestData.game(awayScore = "21", homeScore = "17", detail = "3rd 4:32"))!!
        assertEquals(listOf("PHI", "21", "DAL", "17", "3rd 4:32"), listOf(s.awayLabel, s.awayScore, s.homeLabel, s.homeScore, s.detail))
        val ht = SportsWatchPresentation.scoreboard(GameCenterTestData.game(detail = "Halftime"))!!
        assertTrue(ht.halftime)
        assertNull(ht.detail)
        assertNull(SportsWatchPresentation.scoreboard(GameCenterTestData.fightCard()))
        val upcoming = SportsWatchPresentation.scoreboard(GameCenterTestData.game(status = SportsEventStatus.SCHEDULED, awayScore = null, homeScore = null, detail = null))!!
        assertNull(upcoming.awayScore) // never a fake 0
    }

    @Test
    fun `Game Details actions follow LOCALLY verified channels`() {
        assertEquals(listOf(SportsEventAction.CLOSE), SportsEventPresentation.detailActions(0, channelsEnabled = true))
        assertEquals(listOf(SportsEventAction.WATCH, SportsEventAction.ADD_TO_MULTISCREEN, SportsEventAction.CLOSE), SportsEventPresentation.detailActions(1, channelsEnabled = true))
        assertEquals(
            listOf(SportsEventAction.WATCH, SportsEventAction.SELECT_CHANNEL, SportsEventAction.ADD_TO_MULTISCREEN, SportsEventAction.CLOSE),
            SportsEventPresentation.detailActions(3, channelsEnabled = true),
        )
        assertEquals(listOf(SportsEventAction.CLOSE), SportsEventPresentation.detailActions(3, channelsEnabled = false))
    }

    // --- gating: a build without a channel brand keeps event channels off ---------------------------

    @Test
    fun `brand without channels - event channels off, backend channels never read, fixtures absent`() = runBlocking {
        val withChannels = event.copy(channels = listOf(ref(ch(1, "US: ESPN"))))
        assertTrue(SportsEventChannelSource.production(enabled = false).refs(withChannels).isEmpty())
        val config = SportsGameCenterConfig.create(fixtures = null, resolver = SportsChannelResolver(lookup(emptyList())), channelFeature = false)
        assertFalse(config.channelsEnabled)
        assertNull(config.fixtures)
        assertNull(SportsGameCenterDevMode.active(isDebugBuild = false))
    }

    @Test
    fun `GoatTV production - real backend channels are read and still go through the resolver`() = runBlocking {
        val withChannels = event.copy(channels = listOf(ref(ch(1, "US: ESPN"))))
        assertEquals(1, SportsEventChannelSource.production(enabled = true).refs(withChannels).size)
        val config = SportsGameCenterConfig.create(fixtures = null, resolver = SportsChannelResolver(lookup(emptyList())), channelFeature = true)
        assertTrue(config.channelsEnabled)
        assertNull("no fixtures in production", config.fixtures)
        assertEquals(1, config.channelSource.refs(withChannels).size)
        // No resolver → nothing can ever be played, whatever the backend sent.
        assertFalse(SportsGameCenterConfig.create(fixtures = null, resolver = null, channelFeature = true).channelsEnabled)
    }

    @Test
    fun `debug fixtures enable event channels only with a resolver, refs still go through it`() = runBlocking {
        val local = ch(1, "US: ESPN", epg = "ESPN.us")
        val fixtures = object : SportsGameCenterFixtures {
            override val leagues = emptyList<SportsLeague>()
            override fun events(nowMs: Long) = emptyList<SportsEvent>()
            override fun detail(eventId: String): GameCenterDetail? = null
            override suspend fun channelRefs(eventId: String) = if (eventId == "evt_1") listOf(ref(local)) else null
        }
        assertFalse(SportsGameCenterConfig.create(fixtures = fixtures, resolver = null).channelsEnabled)
        val config = SportsGameCenterConfig.create(fixtures = fixtures, resolver = SportsChannelResolver(lookup(listOf(local))))
        assertTrue(config.channelsEnabled)
        assertEquals(1, config.channelSource.refs(event).size)
        assertTrue("non-fixture events keep the production rule", config.channelSource.refs(event.copy(id = "real")).isEmpty())
    }

    // --- resolver at action time ---------------------------------------------------------------------

    private val xtream = SourceEntity(id = 7, name = "GoatTV", type = SourceType.XTREAM, url = "user-entered")

    private fun lookup(channels: List<ChannelEntity>) = object : SportsChannelLookup {
        override suspend fun activeSource() = xtream
        override suspend fun findByRemote(sourceId: Long, remoteId: String) = channels.firstOrNull { it.sourceId == sourceId && it.remoteId == remoteId }
    }

    @Test
    fun `EPG mismatch, name mismatch and invalid remote id are rejected - only verified feeds remain`() = runBlocking {
        val nbc = ch(1, "US: NBC East", epg = "NBCEast.us")
        val nbcsn = ch(2, "US: NBCSN", epg = "NBCSN.us")
        val cbs = ch(3, "US: CBS Sports HQ", epg = null)
        val r = SportsChannelResolver(lookup(listOf(nbc, nbcsn, cbs))).resolve(
            listOf(
                ref(nbcsn, epg = "Mismatch.fixture"),
                ref(cbs, epg = null, name = "Unrelated Fixture Channel"),
                ref(nbc, remote = "0"),
                ref(nbc),
            ),
        )
        assertEquals(listOf(1L), r.accepted.map { it.channel.id })
        assertEquals(listOf(RejectReason.IDENTITY_MISMATCH, RejectReason.IDENTITY_MISMATCH, RejectReason.NOT_FOUND), r.rejected.map { it.reason })
    }

    @Test
    fun `re-verification at action time drops a channel that changed while the dialog was open`() = runBlocking {
        val espn = ch(1, "US: ESPN", epg = "ESPN.us")
        val refs = listOf(ref(espn))
        assertEquals(1, SportsChannelResolver(lookup(listOf(espn))).resolve(refs).accepted.size)
        val changed = espn.copy(epgChannelId = "ESPN2.us") // playlist refresh re-pointed the stream
        assertTrue(SportsChannelResolver(lookup(listOf(changed))).resolve(refs).accepted.isEmpty())
    }

    @Test
    fun `zero, one and several verified channels`() {
        val a = ch(1, "US: ESPN"); val b = ch(2, "US: ESPN 2"); val c = ch(3, "US: ESPN 4K")
        val one = listOf(ResolvedSportsChannel(ref(a), a))
        val three = listOf(ResolvedSportsChannel(ref(a), a), ResolvedSportsChannel(ref(b), b), ResolvedSportsChannel(ref(c), c))
        assertTrue(SportsWatchPresentation.rows(event, emptyList(), null).isEmpty())
        assertEquals(1, SportsWatchPresentation.rows(event, one, null).size)
        assertEquals(listOf(1L, 2L, 3L), SportsWatchPresentation.rows(event, three, null).map { it.localChannelId })
    }
}
