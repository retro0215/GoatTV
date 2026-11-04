package tv.own.owntv.features.shell

/**
 * Phase 4: how live playback moves between the full-screen player and the TV Guide's preview.
 *
 * One player plays at a time (the shared ExoPlayer [tv.own.owntv.player.LivePreviewEngine], or mpv for a
 * channel pinned to it); the Guide never starts its own. Opening the Guide from the player keeps that
 * stream running and moves its picture into the Guide's preview; moving through the guide never changes
 * it. OK on a programme plays its channel in the preview (4.6.0, [preview]); Watch (or an eligible reminder)
 * tunes a channel full screen.
 *
 * Pure rules, so the shell's wiring can be tested without Compose.
 */
internal object GuidePlayback {

    /**
     * What the Guide is showing.
     * @param live the Guide's preview shows the channel the player was on (it keeps playing, audibly).
     * @param backToPlayer Back in the Guide returns to that channel full screen (the Guide was opened
     *  from the player's TV Guide button). False when the Guide is where the customer came from.
     * @param returnSection the section the full-screen player sat over, restored on the way back.
     */
    data class Session(val live: Boolean = false, val backToPlayer: Boolean = false, val returnSection: MainSection? = null)

    val NONE = Session()

    /**
     * The player's TV Guide button: on a tuned live channel (not catch-up, a movie or an episode), whichever
     * engine plays it. Phase 4 also required mpv's `isLiveContent`, a plain field the screen never
     * re-reads, so the button was missing whenever the channel played on mpv.
     */
    fun canOpenGuide(isTunedLive: Boolean, hasChannel: Boolean): Boolean = isTunedLive && hasChannel

    /** TV Guide pressed in the full-screen player over [currentSection]. Playback is not touched. */
    fun openFromPlayer(currentSection: MainSection): Session =
        Session(live = true, backToPlayer = true, returnSection = currentSection)

    /**
     * Back out of the full-screen player. Over the Guide (the channel was tuned from it), live playback
     * carries on in the Guide's preview instead of stopping; Back in the Guide then leaves the Guide as
     * usual (it would otherwise loop between the two). Anywhere else: the player's usual exit.
     */
    fun onPlayerExit(section: MainSection, isTunedLive: Boolean, isLiveStream: Boolean, returnSection: MainSection?): Session =
        if (section == MainSection.EPG && returnSection == null && isTunedLive && isLiveStream) Session(live = true) else NONE

    /** What Back does in the Guide. */
    enum class Back { RETURN_TO_PLAYER, DEFAULT }

    fun back(session: Session): Back = if (session.live && session.backToPlayer) Back.RETURN_TO_PLAYER else Back.DEFAULT

    /**
     * The customer went to [newSection] while the Guide was playing live: the Guide's session ends and the
     * stream is stopped as the player's own exit would. Live TV keeps the ExoPlayer stream (its preview
     * pane re-takes it, muted); anything else stops it. Null when nothing has to change.
     */
    enum class Teardown { KEEP_FOR_LIVE_PANE, STOP }

    fun onSectionChanged(session: Session, newSection: MainSection, playerShown: Boolean): Teardown? = when {
        !session.live || newSection == MainSection.EPG || playerShown -> null
        newSection == MainSection.LIVE_TV -> Teardown.KEEP_FOR_LIVE_PANE
        else -> Teardown.STOP
    }

    /** Which picture the Guide's preview hosts. Only while the full-screen/mini player is not shown, so an
     *  engine never has two surfaces at once. */
    enum class Surface { EXO, MPV, PLACEHOLDER }

    fun previewSurface(session: Session, playerShown: Boolean, liveOnExo: Boolean): Surface = when {
        !session.live || playerShown -> Surface.PLACEHOLDER
        liveOnExo -> Surface.EXO
        else -> Surface.MPV
    }

    /** The engine that holds audio focus and the media session. The Guide's live preview is audible, so it
     *  keeps both; a hidden or muted engine holds neither. */
    enum class FocusOwner { EXO, MPV, NONE }

    fun audioFocusOwner(playerShown: Boolean, session: Session, liveOnExo: Boolean): FocusOwner = when {
        playerShown || session.live -> if (liveOnExo) FocusOwner.EXO else FocusOwner.MPV
        else -> FocusOwner.NONE
    }

    /**
     * 4.6.0: the Guide's first OK on a channel ([tv.own.owntv.features.epg.GuideOkStage]). [Preview.TUNE]: play
     * it in the Guide's preview. [Preview.ALREADY_PLAYING]: the preview shows it already, so nothing is
     * re-tuned. [Preview.UNAVAILABLE]: there is no in-app preview to play it in — the external player is on,
     * or the mini player or audio bar holds the stream — so OK opens the programme's details at once.
     */
    enum class Preview { TUNE, ALREADY_PLAYING, UNAVAILABLE }

    fun preview(session: Session, playerShown: Boolean, externalPlayer: Boolean, playingChannelId: Long?, channelId: Long): Preview = when {
        externalPlayer || playerShown -> Preview.UNAVAILABLE
        session.live && playingChannelId == channelId -> Preview.ALREADY_PLAYING
        else -> Preview.TUNE
    }

    /** The session once a channel is tuned into the preview: a Guide opened from the player keeps its way back
     *  to it (Back then returns to the new channel full screen); otherwise the preview is now live. */
    fun afterPreview(session: Session): Session = if (session.live) session else Session(live = true)

    /** Whether mpv may be stopped for being off Live TV with no player shown (the shell's section rule). The
     *  Guide's live preview may be mpv's picture, which must keep playing. */
    fun mayStopMpvOffLive(section: MainSection, playerShown: Boolean, session: Session): Boolean =
        section != MainSection.LIVE_TV && !playerShown && !session.live
}
