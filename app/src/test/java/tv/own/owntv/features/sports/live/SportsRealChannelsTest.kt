package tv.own.owntv.features.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/**
 * Phase C2C: real production `channels[]` (GoatTV, `?brand=goat`) → local verification → the existing
 * event playback architecture. Channel rows below mirror real production entries (2026-10-03).
 */
class SportsRealChannelsTest {

    // ---------------------------------------------------------------- branded requests

    @Test
    fun `brand - only a plain brand id is ever used, this GoatTV build sends goat`() {
        assertEquals("goat", SportsChannelFeature.brandOrNull("goat"))
        assertEquals("goat", SportsChannelFeature.brandOrNull(" GOAT "))
        assertNull(SportsChannelFeature.brandOrNull(""))
        assertNull(SportsChannelFeature.brandOrNull(null))
        assertNull(SportsChannelFeature.brandOrNull("goat&user=x"))
        assertNull(SportsChannelFeature.brandOrNull("../goat"))
        // Unit tests run the GoatTV flavor (standardGoat).
        assertEquals("goat", SportsChannelFeature.BRAND)
        assertTrue(SportsChannelFeature.ENABLED)
    }

    @Test
    fun `branded home, since-feed, extended football and soccer requests`() {
        assertEquals("/sports/home?brand=goat", SportsApiClient.homePath("goat"))
        assertEquals("/sports/home", SportsApiClient.homePath(null))
        assertEquals("/sports/events?since=2026-10-03T02%3A00%3A00.000Z&brand=goat", SportsApiClient.sincePath("2026-10-03T02:00:00.000Z", "goat"))
        val now = SportsApiParser.parseIsoUtcMs("2026-10-03T02:00:00Z")!!
        // Extended football keeps its window: now − 18 h through now + 7 days.
        val football = SportsApiClient.eventsForSportPath(
            "football", now - SportsSlateLogic.HOME_PAST_MS, now + SportsSlateLogic.EXTENDED_WINDOWS.getValue("football"), "goat",
        )
        assertEquals("/sports/events?sport=football&from=2026-10-02T08%3A00%3A00Z&to=2026-10-10T02%3A00%3A00Z&brand=goat", football)
        assertTrue(SportsApiClient.eventsForSportPath("soccer", now, now + 1, "goat").endsWith("&brand=goat"))
        // Without a brand the paths are exactly the unbranded ones; a malformed brand is never sent.
        assertFalse(SportsApiClient.eventsForSportPath("football", now, now + 1, null).contains("brand"))
        assertEquals("/sports/home", SportsApiClient.homePath("goat&password=x"))
    }

    @Test
    fun `only the brand identifier is sent - no credentials, servers, playlists or stream URLs`() {
        val paths = listOf(
            SportsApiClient.homePath("goat"),
            SportsApiClient.sincePath("c", "goat"),
            SportsApiClient.eventsForSportPath("football", 0, 1, "goat"),
        )
        for (p in paths) {
            assertFalse(p, Regex("user|pass|token|server|playlist|m3u|stream|http", RegexOption.IGNORE_CASE).containsMatchIn(p))
            assertEquals(p, listOf("goat"), Regex("brand=([^&]+)").findAll(p).map { it.groupValues[1] }.toList())
        }
    }

    // ---------------------------------------------------------------- channels[] parsing

    private fun ch(remote: String, name: String, epg: String?, network: String = "espn", category: String = "USA | Sports", extra: String = "") =
        JSONObject("""{"channelId":"sch_${remote.padStart(24, '0').takeLast(24)}","remoteId":"$remote","epgChannelId":${epg?.let { "\"$it\"" } ?: "null"},
            "name":"$name","category":"$category","logoUrl":null,"networkKey":"$network","confidence":95,"reason":"network_exact"$extra}""")

    /** PITT @ VT as served by production on 2026-10-03. */
    private val pittVt = JSONArray(
        listOf(
            ch("52497", "US: ESPN", "ESPN.us"),
            ch("96161", "US: ESPN", "ESPN.us"),
            ch("934703", "US: ESPN FHD", "ESPN.us"),
            ch("375477", "ESPN 4K UHD", "ESPN4K.us", category = "4K / UHD Channels"),
        ),
    )

    private fun event(id: String, channels: JSONArray?, league: String = "ncaaf", status: String = "LIVE") =
        SportsFixtures.parsed(SportsFixtures.event(id, league = league, status = status, channels = channels))

    @Test
    fun `channels parse - 0, 1 and many, backend order kept, duplicate display names kept, unknown fields ignored`() {
        assertTrue(event("evt_zero", JSONArray()).channels.isEmpty())
        assertTrue(event("evt_absent", null).channels.isEmpty())
        val one = event("evt_one", JSONArray(listOf(ch("51934", "US: NFL Network", "NFLNetwork.us", network = "nfl-network"))), league = "nfl").channels
        assertEquals(listOf("US: NFL Network"), one.map { it.name })
        val many = event("evt_many", pittVt).channels
        assertEquals(listOf("US: ESPN", "US: ESPN", "US: ESPN FHD", "ESPN 4K UHD"), many.map { it.name })
        assertEquals(listOf("52497", "96161", "934703", "375477"), many.map { it.remoteId })
        val future = JSONArray(listOf(ch("85796", "US: TNT", "TNT.us", network = "tnt", extra = ""","feedLabel":"East","quality":{"uhd":false}""")))
        assertEquals("US: TNT", event("evt_future", future, league = "nhl").channels.single().name)
        val noEpg = event("evt_noepg", JSONArray(listOf(ch("61000", "US: NBC Sports Bay Area", null)))).channels.single()
        assertNull(noEpg.epgChannelId)
    }

    // ---------------------------------------------------------------- local verification of real rows

    private val goat = SourceEntity(id = 1, name = "GoatTV", type = SourceType.XTREAM, url = "user-entered")

    private fun local(id: Long, remote: String, name: String, epg: String?, sourceId: Long = 1) =
        ChannelEntity(id = id, sourceId = sourceId, name = name, streamUrl = "built-locally", remoteId = remote, epgChannelId = epg)

    /** The device's channel table (as on the verification SHIELD): KCOP's EPG id changed since the index import. */
    private val device = listOf(
        local(1, "52497", "US: ESPN", "ESPN.us"),
        local(2, "96161", "US: ESPN", "ESPN.us"),
        local(3, "934703", "US: ESPN FHD", "ESPN.us"),
        local(4, "375477", "ESPN 4K UHD", "ESPN4K.us"),
        local(5, "53854", "CA | Los Angeles | My 13 KCOP", "mntkcop.us"),
        local(6, "85796", "US: TNT", "TNT.us"),
    )

    private fun lookup(active: SourceEntity? = goat, channels: List<ChannelEntity> = device) = object : SportsChannelLookup {
        override suspend fun activeSource() = active
        override suspend fun findByRemote(sourceId: Long, remoteId: String) = channels.firstOrNull { it.sourceId == sourceId && it.remoteId == remoteId }
    }

    @Test
    fun `real multi-feed event - every verified feed in backend order, both ESPN streams kept`() = runBlocking {
        val r = SportsChannelResolver(lookup()).resolve(event("evt_pitt_vt", pittVt).channels)
        assertEquals(listOf(1L, 2L, 3L, 4L), r.accepted.map { it.channel.id })
        assertTrue(r.rejected.isEmpty())
        val action = SportsWatchAction.of(r.accepted, featureEnabled = true) as SportsWatchAction.Select
        assertEquals(listOf("US: ESPN", "US: ESPN", "US: ESPN FHD", "ESPN 4K UHD"), action.channels.map { it.channel.name })
    }

    @Test
    fun `KCOP EPG drift is rejected safely - other feeds still resolve, no fallback to name`() = runBlocking {
        val refs = event(
            "evt_ducks",
            JSONArray(listOf(ch("53854", "CA | Los Angeles | My 13 KCOP", "13kcoplosangeles.us", network = "kcop"), ch("85796", "US: TNT", "TNT.us", network = "tnt"))),
            league = "nhl",
        ).channels
        val r = SportsChannelResolver(lookup()).resolve(refs)
        assertEquals(listOf("US: TNT"), r.accepted.map { it.channel.name })
        assertEquals(listOf(RejectReason.IDENTITY_MISMATCH), r.rejected.map { it.reason })
        // KCOP alone: nothing playable → Where to Watch "no playable channel", no Watch action.
        val alone = SportsChannelResolver(lookup()).resolve(refs.take(1))
        assertEquals(SportsWatchAction.None, SportsWatchAction.of(alone.accepted, featureEnabled = true))
    }

    @Test
    fun `real rows on a non-Xtream or missing active source - nothing resolves`() = runBlocking {
        val refs = event("evt_pitt_vt", pittVt).channels
        for (source in listOf(goat.copy(type = SourceType.M3U), goat.copy(type = SourceType.STALKER), null)) {
            val r = SportsChannelResolver(lookup(active = source)).resolve(refs)
            assertTrue(r.accepted.isEmpty())
            assertTrue(r.rejected.all { it.reason == RejectReason.NO_ELIGIBLE_SOURCE })
        }
        // Same remoteId on another source is never used.
        val other = SportsChannelResolver(lookup(channels = device.map { it.copy(sourceId = 2) })).resolve(refs)
        assertTrue(other.accepted.isEmpty())
    }

    // ---------------------------------------------------------------- real Game Center + real channel together

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `live event - real Game Center at once, detail loads, the verified real channel tunes only after the dwell`() {
        val tnt = local(6, "85796", "US: TNT", "TNT.us")
        val dwell = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        val detail = GameCenterDetail("evt_live", GameCenterAvailability.AVAILABLE, teamStats = listOf(GameCenterTeamStat("shots", "Shots", "17", "24")))
        val controller = SportsPreviewController(
            scope,
            source = { GameCenterFetch.Loaded(detail) },
            channels = { e -> SportsChannelResolver(lookup()).resolve(e.channels).accepted },
            channelsEnabled = true,
            dwell = { dwell.await() },
            detailWait = { ms -> if (ms == SportsPreviewController.DETAIL_SETTLE_MS) settle.await() else kotlinx.coroutines.awaitCancellation() },
        )
        val live = event("evt_live", JSONArray(listOf(ch("85796", "US: TNT", "TNT.us", network = "tnt"))), league = "nhl")
        controller.onEventFocused(live)
        assertEquals(SportsPreviewMode.EventGameCenter("evt_live"), controller.mode.value)
        settle.complete(Unit)
        assertEquals(GameCenterDetailState.Ready("evt_live", detail), controller.detail.value)
        assertEquals("no tune before the dwell", SportsPreviewMode.EventGameCenter("evt_live"), controller.mode.value)
        dwell.complete(Unit)
        assertEquals(SportsPreviewMode.EventVideo("evt_live", tnt), controller.mode.value)
        // Double OK: back to the (still loaded) Game Center, and again to the same real feed.
        controller.toggleEventVideo(live)
        assertEquals(SportsPreviewMode.EventGameCenter("evt_live"), controller.mode.value)
        assertTrue(controller.detail.value is GameCenterDetailState.Ready)
        controller.toggleEventVideo(live)
        assertEquals(SportsPreviewMode.EventVideo("evt_live", tnt), controller.mode.value)
    }

    @Test
    fun `selected non-primary real feed plays at once and is remembered across the double-OK toggle`() {
        val controller = SportsPreviewController(
            scope,
            source = GameCenterDetailSource.None,
            channels = { e -> SportsChannelResolver(lookup()).resolve(e.channels).accepted },
            channelsEnabled = true,
            dwell = { kotlinx.coroutines.awaitCancellation() },
            detailWait = GameCenterTestData.skipSettleOnly,
        )
        val pitt = event("evt_pitt_vt", pittVt)
        val second = device[1] // the second "US: ESPN" stream
        controller.onEventFocused(pitt)
        controller.selectFeed(pitt, second)
        assertEquals(SportsPreviewMode.EventVideo("evt_pitt_vt", second), controller.mode.value)
        controller.toggleEventVideo(pitt)
        controller.toggleEventVideo(pitt)
        assertEquals(SportsPreviewMode.EventVideo("evt_pitt_vt", second), controller.mode.value)
    }

    @Test
    fun `event with zero real channels - stays Game Center, no tune on double OK`() {
        val controller = SportsPreviewController(
            scope,
            source = GameCenterDetailSource.None,
            channels = { e -> SportsChannelResolver(lookup()).resolve(e.channels).accepted },
            channelsEnabled = true,
            dwell = {},
            detailWait = GameCenterTestData.skipSettleOnly,
        )
        val none = event("evt_none", JSONArray(), league = "nfl")
        controller.onEventFocused(none)
        controller.toggleEventVideo(none)
        assertEquals(SportsPreviewMode.EventGameCenter("evt_none"), controller.mode.value)
    }

    // ---------------------------------------------------------------- branded API failure keeps last good channels

    private class ScriptedApi : SportsApi {
        val home = ArrayDeque<SportsApiResult<SportsHomePayload>>()
        val delta = ArrayDeque<SportsApiResult<SportsEventsPayload>>()
        override suspend fun home() = home.removeFirst()
        override suspend fun eventsSince(cursor: String) = delta.removeFirst()
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
            SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
    }

    @Test
    fun `branded API failures never erase channels already loaded`() = runBlocking {
        var now = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!
        val api = ScriptedApi()
        val store = SportsLiveStore(api) { now }
        val withChannels = SportsFixtures.event("evt_a", status = "LIVE", channels = pittVt)
        api.home += SportsApiResult.Success(SportsApiParser.parseHome(SportsFixtures.home(listOf(withChannels), listOf("evt_a")))!!)
        store.refresh()
        fun channelsOf() = (store.state.value as SportsLiveState.Content).slate.eventsById.getValue("evt_a").channels.map { it.remoteId }
        assertEquals(listOf("52497", "96161", "934703", "375477"), channelsOf())
        for (failure in listOf(SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK), SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER), SportsApiResult.RateLimited(30))) {
            now += 60_000
            api.delta += failure
            store.refresh()
            assertTrue((store.state.value as SportsLiveState.Content).stale)
            assertEquals(listOf("52497", "96161", "934703", "375477"), channelsOf())
        }
        // A branded delta that carries the event again (e.g. its channels changed) replaces them.
        now += 60_000
        val changed = SportsFixtures.event("evt_a", status = "LIVE", updated = "2026-10-02T12:00:00.000Z", channels = JSONArray(listOf(ch("85796", "US: TNT", "TNT.us"))))
        api.delta += SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(listOf(changed)))!!)
        store.refresh()
        assertEquals(listOf("85796"), channelsOf())
    }
}
