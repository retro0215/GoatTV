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

    // --- VPN guidance: absent by default, only for a repeated service failure on a healthy line ---

    private fun vpn(f: DiagnosticFacts) = Recommendation.SERVICE_UNREACHABLE_VPN in DiagnosticEvaluator.recommendations(f)
    private val repeatedOutage = ServiceFact.Unreachable(ServiceKind.XTREAM, repeated = true)

    @Test
    fun `healthy connection and service - no VPN`() {
        assertFalse(vpn(healthy))
        assertFalse(DiagnosticEvaluator.vpnSuggested(healthy))
    }

    @Test
    fun `service down twice on a healthy line - conditional VPN guidance, no router-only advice duplicate`() {
        val f = healthy.copy(service = repeatedOutage)
        assertEquals(DiagStatus.FAILED, status(DiagCheck.SERVICE, f))
        assertEquals(listOf(Recommendation.SERVICE_UNREACHABLE_VPN), DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `a single service failure is a blip - router advice, no VPN`() {
        val f = healthy.copy(service = ServiceFact.Unreachable(ServiceKind.XTREAM, repeated = false))
        assertFalse(vpn(f))
        assertEquals(listOf(Recommendation.RESTART_ROUTER), DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `slow internet - no automatic VPN even when the service is down`() {
        val slowLatency = healthy.copy(
            internet = InternetFact(true, NetworkTransport.WIFI, latencyMs = DiagnosticEvaluator.SLOW_LATENCY_MS + 1),
            service = repeatedOutage,
        )
        assertFalse(vpn(slowLatency))
        val poorSpeed = healthy.copy(service = repeatedOutage, speed = SpeedTestResult(30, 3.2, 1_000_000, 6_000, SpeedRating.POOR))
        assertFalse(vpn(poorSpeed))
        assertTrue(Recommendation.RESTART_ROUTER in DiagnosticEvaluator.recommendations(poorSpeed))
    }

    @Test
    fun `internet down - no VPN, just restart the router`() {
        val f = healthy.copy(internet = InternetFact(false, NetworkTransport.WIFI), service = repeatedOutage)
        assertFalse(vpn(f))
        assertEquals(Recommendation.RESTART_ROUTER, DiagnosticEvaluator.recommendations(f).first())
    }

    @Test
    fun `service healthy or refusing the login - never VPN`() {
        assertFalse(vpn(healthy.copy(service = ServiceFact.Ok(ServiceKind.XTREAM))))
        assertFalse(vpn(healthy.copy(service = ServiceFact.Rejected(ServiceKind.XTREAM))))
        assertFalse(vpn(healthy.copy(service = ServiceFact.NoSource)))
    }

    @Test
    fun `slow speed, playlist or EPG problems alone never suggest a VPN`() {
        val f = healthy.copy(
            speed = SpeedTestResult(40, 4.0, 1_000_000, 6_000, SpeedRating.POOR),
            playlist = playlist.copy(channels = 0, movies = 0, series = 0),
            epg = EpgFact(0, 0, 0, null, false),
        )
        assertFalse(vpn(f))
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

    // --- speed results and VPN: a speed result alone never triggers VPN guidance ---

    private fun speed(mbps: Double) = SpeedTestResult(28, mbps, 20_000_000, 6_000, SpeedTestEngine.rate(mbps))

    @Test
    fun `300 Mbps with a healthy service - no VPN, no advice`() {
        val f = healthy.copy(speed = speed(300.0))
        assertFalse(vpn(f))
        assertEquals(emptyList<Recommendation>(), DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `5 Mbps with a healthy service - slow-connection advice, no VPN`() {
        val f = healthy.copy(speed = speed(5.0))
        assertFalse(vpn(f))
        assertEquals(listOf(Recommendation.SLOW_CONNECTION), DiagnosticEvaluator.recommendations(f))
    }

    @Test
    fun `5 Mbps and the service failing twice - the poor connection first, never VPN`() {
        val f = healthy.copy(speed = speed(5.0), service = repeatedOutage)
        assertFalse(vpn(f))
        val recs = DiagnosticEvaluator.recommendations(f)
        assertEquals(Recommendation.SLOW_CONNECTION, recs.first())
        assertFalse(Recommendation.SERVICE_UNREACHABLE_VPN in recs)
    }

    @Test
    fun `80 Mbps, good latency and the service failing twice - conditional VPN suggestion`() {
        val f = healthy.copy(speed = speed(80.0), service = repeatedOutage)
        assertTrue(vpn(f))
        assertEquals(listOf(Recommendation.SERVICE_UNREACHABLE_VPN), DiagnosticEvaluator.recommendations(f))
    }

    // --- general internet latency vs speed-test endpoint latency are separate ---

    @Test
    fun `high speed-test endpoint latency does not suppress or trigger VPN advice - only the internet check's latency counts`() {
        val r2Slow = SpeedTestResult(400, 80.0, 30_000_000, 6_000, SpeedRating.EXCELLENT)
        // Healthy service: a slow test endpoint alone never brings VPN advice.
        assertFalse(vpn(healthy.copy(speed = r2Slow)))
        assertEquals(emptyList<Recommendation>(), DiagnosticEvaluator.recommendations(healthy.copy(speed = r2Slow)))
        // Service failing twice on a fast general connection: same answer with or without the slow endpoint.
        val failing = healthy.copy(service = repeatedOutage)
        assertEquals(vpn(failing), vpn(failing.copy(speed = r2Slow)))
        assertEquals(DiagnosticEvaluator.recommendations(failing), DiagnosticEvaluator.recommendations(failing.copy(speed = r2Slow)))
    }

    @Test
    fun `slow general internet latency blocks VPN advice even when the speed test endpoint was fast`() {
        val r2Fast = SpeedTestResult(20, 80.0, 30_000_000, 6_000, SpeedRating.EXCELLENT)
        val f = healthy.copy(
            internet = healthy.internet!!.copy(latencyMs = 400),
            service = repeatedOutage,
            speed = r2Fast,
        )
        assertFalse(vpn(f))
    }

    @Test
    fun `a speed result never changes the check statuses or the issue count`() {
        assertEquals(summary(healthy).issues, summary(healthy.copy(speed = speed(3.0))).issues)
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
