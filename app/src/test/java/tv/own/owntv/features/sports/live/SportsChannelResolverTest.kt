package tv.own.owntv.features.sports.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

class SportsChannelResolverTest {

    private val xtream = SourceEntity(id = 7, name = "GoatTV", type = SourceType.XTREAM, url = "user-entered")
    private val other = SourceEntity(id = 9, name = "Other", type = SourceType.XTREAM, url = "user-entered")

    private fun channel(id: Long, sourceId: Long, remoteId: String, name: String, epg: String?) =
        ChannelEntity(id = id, sourceId = sourceId, name = name, streamUrl = "built-locally", epgChannelId = epg, remoteId = remoteId)

    private fun ref(remoteId: String, name: String, epg: String?, channelId: String = "sch_$remoteId") =
        SportsChannelRef(channelId, remoteId, epg, name, "USA | Sports", null, "espn", 95, "network_exact")

    /** Local channel table keyed by (sourceId, remoteId); records every lookup. */
    private class FakeLookup(var active: SourceEntity?, channels: List<ChannelEntity>) : SportsChannelLookup {
        val byKey = channels.associateBy { it.sourceId to it.remoteId!! }
        val lookups = mutableListOf<Pair<Long, String>>()
        override suspend fun activeSource() = active
        override suspend fun findByRemote(sourceId: Long, remoteId: String): ChannelEntity? {
            lookups += sourceId to remoteId
            return byKey[sourceId to remoteId]
        }
    }

    private val locals = listOf(
        channel(1, 7, "52001", "US: ESPN", "ESPN.us"),
        channel(2, 7, "52002", "US: ESPN FHD", "ESPN.us"),
        channel(3, 7, "52099", "ESPN 4K UHD", "ESPN4K.us"),
        channel(4, 7, "61000", "US: NBC Sports Bay Area", null),
        channel(5, 7, "70000", "US: Fox Sports 1 HD", "FoxSports1.us"),
        channel(6, 9, "52001", "US: ESPN", "ESPN.us"), // same remote id on ANOTHER source
    )

    @Test
    fun `resolves by sourceId + remoteId on the active Xtream source, preserving backend order`() = runBlocking {
        val lookup = FakeLookup(xtream, locals)
        val r = SportsChannelResolver(lookup).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us"), ref("52099", "ESPN 4K UHD", "ESPN4K.us")))
        assertEquals(listOf(1L, 2L, 3L), r.accepted.map { it.channel.id })
        assertTrue(r.rejected.isEmpty())
        assertTrue("only the active source is ever queried", lookup.lookups.all { it.first == 7L })
    }

    @Test
    fun `EPG agreement is required when the backend sends an EPG id (case-insensitive)`() = runBlocking {
        val ok = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("52001", "Different Name", "espn.US")))
        assertEquals(listOf(1L), ok.accepted.map { it.channel.id })
        val mismatch = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("52001", "US: ESPN", "ESPN2.us")))
        assertTrue(mismatch.accepted.isEmpty())
        assertEquals(RejectReason.IDENTITY_MISMATCH, mismatch.rejected.single().reason)
        val localHasNoEpg = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("61000", "US: NBC Sports Bay Area", "NBCSportsBayArea.us")))
        assertEquals(RejectReason.IDENTITY_MISMATCH, localHasNoEpg.rejected.single().reason)
    }

    @Test
    fun `without an EPG id the normalized names must agree`() = runBlocking {
        val ok = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("61000", "NBC Sports Bay Area HD", null)))
        assertEquals(listOf(4L), ok.accepted.map { it.channel.id })
        val mismatch = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("61000", "NBC Sports California", null)))
        assertEquals(RejectReason.IDENTITY_MISMATCH, mismatch.rejected.single().reason)
        assertTrue(SportsChannelResolver.identityAgrees(ref("x", "USA | Fox Sports 1", null), channel(9, 7, "x", "US: Fox Sports 1 HD", null)))
        assertFalse("4K never agrees with the normal feed", SportsChannelResolver.identityAgrees(ref("x", "ESPN 4K", null), channel(9, 7, "x", "US: ESPN", null)))
    }

    @Test
    fun `a remoteId missing on the active source is rejected - never a global name search`() = runBlocking {
        val lookup = FakeLookup(xtream, locals)
        val r = SportsChannelResolver(lookup).resolve(listOf(ref("99999", "US: ESPN", "ESPN.us")))
        assertTrue(r.accepted.isEmpty())
        assertEquals(RejectReason.NOT_FOUND, r.rejected.single().reason)
        assertEquals("exactly one lookup, by (active source, remoteId)", listOf(7L to "99999"), lookup.lookups)
    }

    @Test
    fun `a row from another source is rejected even if the lookup returned it`() = runBlocking {
        val leaky = object : SportsChannelLookup {
            override suspend fun activeSource() = xtream
            override suspend fun findByRemote(sourceId: Long, remoteId: String) = locals.last() // sourceId 9
        }
        val r = SportsChannelResolver(leaky).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us")))
        assertEquals(RejectReason.WRONG_SOURCE, r.rejected.single().reason)
    }

    @Test
    fun `M3U, Stalker and missing active sources are not eligible - nothing is looked up`() = runBlocking {
        for (type in listOf(SourceType.M3U, SourceType.STALKER, SourceType.LOCAL_BACKUP)) {
            val lookup = FakeLookup(xtream.copy(type = type), locals)
            val r = SportsChannelResolver(lookup).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us")))
            assertTrue(type.name, r.accepted.isEmpty())
            assertEquals(RejectReason.NO_ELIGIBLE_SOURCE, r.rejected.single().reason)
            assertTrue(lookup.lookups.isEmpty())
        }
        val none = SportsChannelResolver(FakeLookup(null, locals)).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us")))
        assertEquals(RejectReason.NO_ELIGIBLE_SOURCE, none.rejected.single().reason)
    }

    @Test
    fun `the active source decides - the same remoteId on another source is never used`() = runBlocking {
        val r = SportsChannelResolver(FakeLookup(other, locals)).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us")))
        assertEquals(listOf(6L), r.accepted.map { it.channel.id })
        assertEquals(RejectReason.NOT_FOUND, r.rejected.single().reason)
    }

    @Test
    fun `duplicate refs to the same local channel keep the higher-ranked one`() = runBlocking {
        val r = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(listOf(ref("52001", "US: ESPN", "ESPN.us", "sch_a"), ref("52001", "US: ESPN", "ESPN.us", "sch_b")))
        assertEquals(listOf("sch_a"), r.accepted.map { it.ref.channelId })
    }

    @Test
    fun `watch action - 0 none, 1 direct, 2+ select in backend order, disabled feature is always none`() = runBlocking {
        val resolved = SportsChannelResolver(FakeLookup(xtream, locals)).resolve(
            listOf(ref("52001", "US: ESPN", "ESPN.us"), ref("52002", "US: ESPN FHD", "ESPN.us"), ref("52099", "ESPN 4K UHD", "ESPN4K.us")),
        ).accepted
        assertEquals(SportsWatchAction.None, SportsWatchAction.of(emptyList(), featureEnabled = true))
        assertEquals(SportsWatchAction.Direct(resolved.first()), SportsWatchAction.of(resolved.take(1), featureEnabled = true))
        val select = SportsWatchAction.of(resolved, featureEnabled = true) as SportsWatchAction.Select
        assertEquals("4K/UHD alternate stays after the primary feeds", listOf("US: ESPN", "US: ESPN FHD", "ESPN 4K UHD"), select.channels.map { it.channel.name })
        assertEquals(SportsWatchAction.None, SportsWatchAction.of(resolved))
        assertFalse("Phase C1 ships with channels disabled", SportsChannelFeature.ENABLED)
    }
}
