package tv.own.owntv.player

import tv.own.owntv.core.stalker.ReconnectUrlProvider

/**
 * What [OwnTVPlayer] (mpv) was playing when the app went to the background — Home, the TV screensaver, or
 * the TV switched off (all three stop the activity) — so it can be reopened on return.
 *
 * The stream itself is always freed on the way out: holding mpv's demuxer cache and decoder while
 * invisible got the process killed on real TVs. A URL alone is not enough to reopen an item: the request
 * identity ([userAgent], [httpHeaders]) and, for an expiring-URL source (Stalker), its [reconnectProvider]
 * have to come back with it.
 *
 * Before 4.6.1 only films, episodes and catch-up were kept. A live channel mpv was playing — the fallback
 * engine, or a channel pinned to it — was simply dropped, so the channel came back to a black screen while
 * Live TV still showed it as playing on mpv. ExoPlayer's live engine already re-tunes its channel
 * ([LivePreviewEngine.onAppForegrounded]); this gives mpv's live channel the same.
 */
internal sealed interface PlayerBackgroundRestore {
    val url: String
    val meta: MediaMeta
    val userAgent: String?
    val httpHeaders: String?
    val reconnectProvider: ReconnectUrlProvider?

    /** A film, episode or catch-up archive: reopened paused or playing, at its position. */
    data class Vod(
        override val url: String,
        override val meta: MediaMeta,
        val positionMs: Long,
        val wasPlaying: Boolean,
        override val userAgent: String?,
        override val httpHeaders: String?,
        override val reconnectProvider: ReconnectUrlProvider?,
    ) : PlayerBackgroundRestore

    /** A live channel: re-tuned at the live edge, with its mute state and pre-buffer as before. */
    data class Live(
        override val url: String,
        override val meta: MediaMeta,
        val muted: Boolean,
        val prerollSecs: Int?,
        override val userAgent: String?,
        override val httpHeaders: String?,
        override val reconnectProvider: ReconnectUrlProvider?,
    ) : PlayerBackgroundRestore

    companion object {
        /**
         * The restore for what is loaded now, or null when nothing is: a channel the customer stopped or
         * left has no URL, and nothing comes back for it. [positionMs] is read only for non-live items.
         */
        fun capture(
            url: String?,
            isLive: Boolean,
            meta: MediaMeta,
            positionMs: () -> Long,
            isPlaying: Boolean,
            muted: Boolean,
            prerollSecs: Int?,
            userAgent: String?,
            httpHeaders: String?,
            reconnectProvider: ReconnectUrlProvider?,
        ): PlayerBackgroundRestore? {
            if (url == null) return null
            return if (isLive) {
                Live(url, meta, muted, prerollSecs, userAgent, httpHeaders, reconnectProvider)
            } else {
                Vod(url, meta, positionMs(), isPlaying, userAgent, httpHeaders, reconnectProvider)
            }
        }
    }
}

/**
 * Holds at most one [PlayerBackgroundRestore] between the activity's stop and start.
 *
 * - [take] hands it out once. When something else is already playing by then (a deep link, the Guide, a
 *   reminder tune got there first), it is dropped instead: the customer's newer choice wins and no second
 *   stream is opened.
 * - [discard] drops it (profile switch, sign-out): never bring back the previous user's channel.
 */
internal class BackgroundRestoreSlot {
    @Volatile private var pending: PlayerBackgroundRestore? = null

    fun remember(restore: PlayerBackgroundRestore) { pending = restore }

    fun take(somethingPlaying: Boolean): PlayerBackgroundRestore? {
        val restore = pending ?: return null
        pending = null
        return restore.takeUnless { somethingPlaying }
    }

    fun discard() { pending = null }

    val isPending: Boolean get() = pending != null
}
