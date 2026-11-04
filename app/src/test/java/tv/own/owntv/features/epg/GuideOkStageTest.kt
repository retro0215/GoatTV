package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.features.shell.GuidePlayback
import tv.own.owntv.features.shell.GuidePlayback.Preview
import tv.own.owntv.features.shell.MainSection
import java.io.File

/**
 * 4.6.0: OK in the TV Guide works in two stages. First OK plays the highlighted programme's channel (its live
 * broadcast) in the Guide's preview and keeps the Guide open; a second OK on the same programme opens its
 * details. Moving to another programme or channel starts over. Holding OK still opens the channel's options.
 */
class GuideOkStageTest {

    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L
    private fun prog(start: Long, title: String = "P") =
        EpgProgrammeEntity(sourceId = 1, epgChannelId = "e", startMs = start, stopMs = start + hour, title = title)

    private val onAir = prog(now - hour / 2)
    private val later = prog(now + hour / 2)
    private val earlier = prog(now - 3 * hour / 2)

    private fun src(path: String) = File("src/main/java/tv/own/owntv/$path").readText().replace("\r\n", "\n")
    private val screen by lazy { src("features/epg/EpgScreen.kt") }
    private val shell by lazy { src("features/shell/OwnTVShell.kt") }
    private val okRelease by lazy { screen.substringAfter("if (e.type == KeyEventType.KeyUp) {").substringBefore("if (e.type != KeyEventType.KeyDown)") }

    // ---- The two stages --------------------------------------------------------------------------------

    @Test fun `first OK previews, a second OK on the same programme opens it`() {
        val t = GuideOkStage.target(10, onAir)
        assertEquals(GuideOkStage.Action.PREVIEW, GuideOkStage.onOk(null, t))
        assertEquals(GuideOkStage.Action.OPEN_PROGRAMME, GuideOkStage.onOk(t, t))
    }

    @Test fun `a state, not a double-click - nothing in the rules depends on time`() {
        // The second OK may come any time later; the stage is only the programme the first one was on.
        val t = GuideOkStage.target(10, onAir)
        repeat(3) { assertEquals(GuideOkStage.Action.OPEN_PROGRAMME, GuideOkStage.onOk(t, GuideOkStage.target(10, onAir))) }
        assertFalse("no timing in the Guide's OK handling", "uptimeMillis" in okRelease || "eventTime" in okRelease || "delay(" in okRelease)
    }

    @Test fun `moving to another programme or channel resets the stage`() {
        val t = GuideOkStage.target(10, onAir)
        assertNull("next programme", GuideOkStage.afterMove(t, GuideOkStage.target(10, later)))
        assertNull("another channel, same time", GuideOkStage.afterMove(t, GuideOkStage.target(11, onAir)))
        assertNull("a row without guide data", GuideOkStage.afterMove(t, GuideOkStage.target(10, null)))
        // Away and back: the first OK there previews again.
        val back = GuideOkStage.afterMove(GuideOkStage.afterMove(t, GuideOkStage.target(10, later)), t)
        assertEquals(GuideOkStage.Action.PREVIEW, GuideOkStage.onOk(back, t))
    }

    @Test fun `a Now-Next or details refresh of the same programme keeps the stage`() {
        val t = GuideOkStage.target(10, onAir)
        val refreshed = onAir.copy(id = 99, title = "New title", description = "Details", contentHash = 7)
        assertEquals(t, GuideOkStage.afterMove(t, GuideOkStage.target(10, refreshed)))
        assertEquals(GuideOkStage.Action.OPEN_PROGRAMME, GuideOkStage.onOk(t, GuideOkStage.target(10, refreshed)))
    }

    @Test fun `the Guide resets on moves, on a reopen and on a profile or playlist change`() {
        assertTrue("var okArmed by remember(okStageKey) { mutableStateOf<GuideOkStage.Target?>(null) }" in screen)
        assertTrue("snapshotFlow { okTargetAt(cursor) }.collect { okArmed = GuideOkStage.afterMove(okArmed, it) }" in screen)
        assertTrue("okStageKey = activeProfileId to activePlaylistId," in shell)
        // Watch or catch-up take the player full screen; back in the Guide, OK starts at the preview again.
        assertTrue("onWatch = { detail = null; okArmed = null;" in screen)
    }

    // ---- First OK: the channel's live broadcast in the preview ----------------------------------------

    @Test fun `past, current and future programmes all preview the channel's live broadcast`() {
        // The preview takes the channel only — never the programme — so a past programme does not start
        // catch-up and a future one is not "played"; both are in the details, one OK away.
        assertTrue("onPreviewChannel: (channel: ChannelEntity) -> Boolean = { false }," in screen)
        val preview = okRelease.substringAfter("GuideOkStage.Action.PREVIEW -> {").substringBefore("GuideOkStage.Action.OPEN_PROGRAMME")
        assertTrue("if (onPreviewChannel(channel)) vm.noteChannelTuned(channel)" in preview)
        assertFalse(preview.contains("onPlayChannel") || preview.contains("onPlayCatchup"))
        val wiring = shell.substringAfter("onPreviewChannel = { ch ->").substringBefore("okStageKey =")
        assertTrue("liveVm.previewFromGuide(ch)" in wiring)
        assertFalse("no catch-up from the first OK", "Catchup" in wiring)
        for (p in listOf(earlier, onAir, later)) assertEquals(GuideOkStage.Action.PREVIEW, GuideOkStage.onOk(null, GuideOkStage.target(10, p)))
    }

    @Test fun `the Guide stays open - no full screen, no fullscreen player mode`() {
        val wiring = shell.substringAfter("onPreviewChannel = { ch ->").substringBefore("okStageKey =")
        assertFalse("playerMode =" in wiring)
        assertFalse("onSelectSection" in wiring)
        assertTrue("guideSession = GuidePlayback.afterPreview(guideSession)" in wiring)
    }

    @Test fun `the channel already in the preview is not tuned again`() {
        val live = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(Preview.ALREADY_PLAYING, GuidePlayback.preview(live, playerShown = false, externalPlayer = false, playingChannelId = 10, channelId = 10))
        assertEquals(Preview.TUNE, GuidePlayback.preview(live, playerShown = false, externalPlayer = false, playingChannelId = 10, channelId = 11))
        // Not in the preview yet (the Guide opened from the menu): tune, which promotes a stream already loaded.
        assertEquals(Preview.TUNE, GuidePlayback.preview(GuidePlayback.NONE, playerShown = false, externalPlayer = false, playingChannelId = 10, channelId = 10))
        val wiring = shell.substringAfter("onPreviewChannel = { ch ->").substringBefore("okStageKey =")
        assertTrue("GuidePlayback.Preview.ALREADY_PLAYING -> true" in wiring)
    }

    @Test fun `no in-app preview - external player, mini player or audio bar - opens the details at once`() {
        val live = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(Preview.UNAVAILABLE, GuidePlayback.preview(live, playerShown = false, externalPlayer = true, playingChannelId = 1, channelId = 2))
        assertEquals(Preview.UNAVAILABLE, GuidePlayback.preview(GuidePlayback.NONE, playerShown = true, externalPlayer = false, playingChannelId = 1, channelId = 2))
        val preview = okRelease.substringAfter("GuideOkStage.Action.PREVIEW -> {").substringBefore("GuideOkStage.Action.OPEN_PROGRAMME")
        assertTrue("else programme?.let { detail = channel to it }" in preview)
    }

    @Test fun `a preview tune uses the one shared live path, with History's usual delay`() {
        val vm = src("features/live/LiveViewModel.kt")
        val tune = vm.substringAfter("fun previewFromGuide(channel: ChannelEntity) {").substringBefore("\n    }\n")
        assertTrue("armZapList(channel)" in tune && "ensurePlaying(channel)" in tune)
        assertFalse("not every channel glanced at goes to History at once", "immediate = true" in tune)
    }

    // ---- Long press ------------------------------------------------------------------------------------

    @Test fun `a long press opens channel options only - its release neither previews nor opens`() {
        // The release after a long press returns before the two stages are consulted.
        val longPressExit = okRelease.indexOf("if (okLongPressed) { okLongPressed = false; return true }")
        assertTrue(longPressExit > 0 && longPressExit < okRelease.indexOf("GuideOkStage.onOk"))
        // A release without its press in the grid (focus arrived mid-press) does nothing.
        assertTrue("if (!isOk || !okDown) return false" in okRelease)
        // The hold itself opens the options and leaves the stage alone.
        val hold = screen.substringAfter("} else if (okDown && !okLongPressed) {").substringBefore("true\n            } else false")
        assertTrue("matchChooser = channel" in hold)
        assertFalse("okArmed" in hold || "onPreviewChannel" in hold)
    }

    // ---- Player, decoder, audio, Back, reminders -------------------------------------------------------

    @Test fun `ExoPlayer stays docked on the one player view, mpv on its own surface`() {
        val previewed = GuidePlayback.afterPreview(GuidePlayback.NONE)
        assertEquals(GuidePlayback.Surface.EXO, GuidePlayback.previewSurface(previewed, playerShown = false, liveOnExo = true))
        assertEquals(GuidePlayback.Surface.MPV, GuidePlayback.previewSurface(previewed, playerShown = false, liveOnExo = false))
        assertFalse("mpv keeps playing in the preview", GuidePlayback.mayStopMpvOffLive(MainSection.EPG, playerShown = false, session = previewed))
        assertEquals(1, Regex("ExoPreviewSurface\\(").findAll(shell).count())
        assertTrue("GuidePlayback.Surface.EXO -> { m -> tv.own.owntv.player.VideoDockTarget(guideDock, m) }" in shell)
    }

    @Test fun `the preview is audible and holds audio focus on either engine`() {
        val previewed = GuidePlayback.afterPreview(GuidePlayback.NONE)
        assertEquals(GuidePlayback.FocusOwner.EXO, GuidePlayback.audioFocusOwner(playerShown = false, session = previewed, liveOnExo = true))
        assertEquals(GuidePlayback.FocusOwner.MPV, GuidePlayback.audioFocusOwner(playerShown = false, session = previewed, liveOnExo = false))
    }

    @Test fun `Back - a Guide opened from the player returns to it, on the previewed channel`() {
        val fromPlayer = GuidePlayback.openFromPlayer(MainSection.LIVE_TV)
        assertEquals(fromPlayer, GuidePlayback.afterPreview(fromPlayer))
        assertEquals(GuidePlayback.Back.RETURN_TO_PLAYER, GuidePlayback.back(GuidePlayback.afterPreview(fromPlayer)))
        // Opened from the menu: Back works as before; leaving the Guide stops the preview like the player's exit.
        val fromMenu = GuidePlayback.afterPreview(GuidePlayback.NONE)
        assertEquals(GuidePlayback.Back.DEFAULT, GuidePlayback.back(fromMenu))
        assertEquals(GuidePlayback.Teardown.STOP, GuidePlayback.onSectionChanged(fromMenu, MainSection.HOME, playerShown = false))
        assertEquals(GuidePlayback.Teardown.KEEP_FOR_LIVE_PANE, GuidePlayback.onSectionChanged(fromMenu, MainSection.LIVE_TV, playerShown = false))
    }

    @Test fun `a reminder still takes over - any player shown ends the preview session`() {
        // The reminder's tune shows the player, which ends the Guide's session; first OK does not change that rule.
        assertTrue("LaunchedEffect(playerMode) { if (playerMode != PlayerMode.NONE) guideSession = GuidePlayback.NONE }" in shell)
        assertEquals(GuidePlayback.Surface.PLACEHOLDER, GuidePlayback.previewSurface(GuidePlayback.afterPreview(GuidePlayback.NONE), playerShown = true, liveOnExo = true))
    }
}
