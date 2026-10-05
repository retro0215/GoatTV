package tv.own.owntv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.diagnostics.DiagnosticTestFacts.healthy
import tv.own.owntv.core.diagnostics.DiagnosticTestFacts.playlist

class DiagnosticEvaluatorTest {

    private fun status(check: DiagCheck, f: DiagnosticFacts) = DiagnosticEvaluator.status(check, f)
    private fun summary(f: DiagnosticFacts) = DiagnosticEvaluator.summary(DiagnosticEvaluator.results(f), finished = true)

    @Test
    fun `healthy box passes every check with no recommendations`() {
        DiagCheck.entries.forEach { assertEquals(it.name, DiagStatus.PASS, status(it, healthy)) }
        assertTrue(summary(healthy).allPassed)
        assertEquals(emptyList<Recommendation>(), DiagnosticEvaluator.recommendations(healthy))
    }

    @Test
    fun `nothing run yet is all NOT_CHECKED, zero issues, and not complete`() {
        val f = DiagnosticFacts(app = DiagnosticTestFacts.app)
        DiagCheck.entries.forEach { assertEquals(it.name, DiagStatus.NOT_CHECKED, status(it, f)) }
        val s = DiagnosticEvaluator.summary(DiagnosticEvaluator.results(f), finished = false)
        assertEquals(0, s.issues)
        assertFalse("an unfinished run must never read as ALL CHECKS PASSED", s.allPassed)
    }

    @Test
    fun `internet down - restart router, never a VPN or ISP claim`() {
        val f = healthy.copy(
            internet = InternetFact(false, NetworkTransport.WIFI),
            service = ServiceFact.Unreachable(ServiceKind.XTREAM),
            sports = SportsFact.Unavailable,
        )
        assertEquals(DiagStatus.FAILED, status(DiagCheck.INTERNET, f))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SERVICE, f))
        val recs = DiagnosticEvaluator.recommendations(f)
        assertEquals(Recommendation.RESTART_ROUTER, recs.first())
        assertFalse(Recommendation.SERVICE_UNREACHABLE_VPN in recs)
        assertFalse("sports advice is noise while the internet itself is down", Recommendation.SPORTS_LATER in recs)
    }

    @Test
    fun `internet up but service unreachable - router restart and VPN may help`() {
        val f = healthy.copy(service = ServiceFact.Unreachable(ServiceKind.XTREAM))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SERVICE, f))
        assertEquals(listOf(Recommendation.SERVICE_UNREACHABLE_VPN), DiagnosticEvaluator.recommendations(f))
        assertFalse(Recommendation.RESTART_ROUTER in DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `service refuses the login - account advice, not VPN`() {
        val f = healthy.copy(service = ServiceFact.Rejected(ServiceKind.XTREAM))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SERVICE, f))
        assertEquals(listOf(Recommendation.CHECK_ACCOUNT), DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `live TV passes only with channels AND a working service, and warns otherwise`() {
        assertEquals(DiagStatus.PASS, status(DiagCheck.LIVE_TV, healthy))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.LIVE_TV, healthy.copy(service = ServiceFact.Unreachable(ServiceKind.XTREAM))))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.LIVE_TV, healthy.copy(playlist = playlist.copy(channels = 0))))
        assertEquals(DiagStatus.NOT_CHECKED, status(DiagCheck.LIVE_TV, healthy.copy(playlist = playlist.copy(liveEnabled = false))))
    }

    @Test
    fun `no playlist is one problem with one fix, not a cascade of failures`() {
        val f = healthy.copy(service = ServiceFact.NoSource, playlist = PlaylistFact.NONE)
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SERVICE, f))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.PLAYLIST, f))
        assertEquals(DiagStatus.NOT_CHECKED, status(DiagCheck.LIVE_TV, f))
        assertEquals(DiagStatus.NOT_CHECKED, status(DiagCheck.VOD, f))
        val recs = DiagnosticEvaluator.recommendations(f)
        assertEquals(listOf(Recommendation.ADD_PLAYLIST), recs)
    }

    @Test
    fun `empty playlist recommends a re-sync`() {
        val f = healthy.copy(playlist = playlist.copy(channels = 0, movies = 0, series = 0))
        assertEquals(DiagStatus.FAILED, status(DiagCheck.PLAYLIST, f))
        assertTrue(Recommendation.RESYNC_PLAYLIST in DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `EPG - none set up, empty, or last sync failed all warn with the matching fix`() {
        val none = healthy.copy(epg = EpgFact(0, 0, 0, null, false))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.EPG, none))
        assertEquals(listOf(Recommendation.ADD_EPG), DiagnosticEvaluator.recommendations(none))

        val empty = healthy.copy(epg = EpgFact(1, 0, 0, null, false))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.EPG, empty))
        assertEquals(listOf(Recommendation.RESYNC_EPG), DiagnosticEvaluator.recommendations(empty))

        val failed = healthy.copy(epg = EpgFact(1, 10, 5, 1L, lastSyncFailed = true))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.EPG, failed))
        assertEquals(listOf(Recommendation.RESYNC_EPG), DiagnosticEvaluator.recommendations(failed))
    }

    @Test
    fun `movies and series - library PASS is availability only, sections off are not checked`() {
        assertEquals(DiagStatus.PASS, status(DiagCheck.VOD, healthy))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.VOD, healthy.copy(playlist = playlist.copy(movies = 0, series = 0))))
        assertEquals(DiagStatus.NOT_CHECKED, status(DiagCheck.VOD, healthy.copy(playlist = playlist.copy(moviesEnabled = false, seriesEnabled = false))))
    }

    @Test
    fun `player switch flips MPV and Exo`() {
        assertEquals(VodEngine.EXO, DiagnosticEvaluator.switchTarget(VodEngine.MPV))
        assertEquals(VodEngine.MPV, DiagnosticEvaluator.switchTarget(VodEngine.EXO))
    }

    @Test
    fun `player row is informational - never counted as an issue`() {
        val f = healthy.copy(player = PlayerFact(VodEngine.MPV, exclusive = true))
        assertEquals(DiagStatus.PASS, status(DiagCheck.PLAYER, f))
        assertEquals(0, summary(f).issues)
    }

    @Test
    fun `update states`() {
        assertEquals(DiagStatus.PASS, status(DiagCheck.APP_VERSION, healthy))
        val available = healthy.copy(update = UpdateFact.Available("4.4.25"))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.APP_VERSION, available))
        assertEquals(listOf(Recommendation.UPDATE_APP), DiagnosticEvaluator.recommendations(available))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.APP_VERSION, healthy.copy(update = UpdateFact.CheckFailed)))
        assertEquals(DiagStatus.NOT_CHECKED, status(DiagCheck.APP_VERSION, healthy.copy(update = UpdateFact.Busy)))
    }

    @Test
    fun `sports - zero events warns, unreachable fails, a live game is never required`() {
        assertEquals(DiagStatus.PASS, status(DiagCheck.SPORTS, healthy.copy(sports = SportsFact.Connected(3, gameCenterOk = null))))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.SPORTS, healthy.copy(sports = SportsFact.Connected(0, null))))
        val down = healthy.copy(sports = SportsFact.Unavailable)
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SPORTS, down))
        assertEquals(listOf(Recommendation.SPORTS_LATER), DiagnosticEvaluator.recommendations(down))
    }

    @Test
    fun `low storage warns and recommends freeing space`() {
        val low = healthy.copy(device = DiagnosticTestFacts.device.copy(storageFreeBytes = 100L shl 20))
        assertEquals(DiagStatus.WARNING, status(DiagCheck.DEVICE, low))
        assertEquals(listOf(Recommendation.FREE_STORAGE), DiagnosticEvaluator.recommendations(low))
        val unknown = healthy.copy(device = DiagnosticTestFacts.device.copy(storageFreeBytes = 0, storageTotalBytes = 0))
        assertEquals("unknown storage is not 'low'", DiagStatus.PASS, status(DiagCheck.DEVICE, unknown))
    }

    @Test
    fun `summary counts WARNING and FAILED only`() {
        val f = healthy.copy(
            service = ServiceFact.Unreachable(ServiceKind.XTREAM), // SERVICE FAILED + LIVE_TV WARNING
            update = UpdateFact.Available("9.9.9"), // WARNING
            player = PlayerFact(VodEngine.MPV, exclusive = false), // informational PASS
        )
        assertEquals(3, summary(f).issues)
        assertFalse(summary(f).allPassed)
    }
}
