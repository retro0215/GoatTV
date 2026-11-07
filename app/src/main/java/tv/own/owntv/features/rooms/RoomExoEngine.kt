package tv.own.owntv.features.rooms

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import tv.own.owntv.core.drm.DrmConfig
import tv.own.owntv.core.drm.toMediaDrmConfiguration
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.network.StreamHeaders
import tv.own.owntv.core.network.StreamingHttpClient
import tv.own.owntv.player.ownTVRenderers

@UnstableApi
class RoomExoEngine(
    private val context: Context,
    private val streamingHttp: StreamingHttpClient,
) {
    private var player: ExoPlayer? = null
    private var surface: Surface? = null

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Room Exo error: ${error.errorCodeName}", error)
        }
    }

    fun setSurface(s: Surface?) {
        surface = s
        if (s != null) player?.setVideoSurface(s) else player?.clearVideoSurface()
    }

    fun play(url: String, headersMap: Map<String, String>, userAgent: String?, drmConfig: String?) {
        val effectiveUa = StreamHeaders.userAgentOf(headersMap) ?: userAgent?.takeIf { it.isNotBlank() } ?: HttpClient.DEFAULT_USER_AGENT
        val requestHeaders = headersMap.filterKeys { !it.equals("User-Agent", ignoreCase = true) }

        val dataSourceFactory = OkHttpDataSource.Factory(streamingHttp.client)
            .setUserAgent(effectiveUa)
            .setDefaultRequestProperties(requestHeaders)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        drmConfig?.let { drm ->
            val decodedDrm = DrmConfig.decode(drm)
            decodedDrm?.let { d ->
                mediaItemBuilder.setDrmConfiguration(d.toMediaDrmConfiguration(multiSession = true))
            }
        }

        val p = ExoPlayer.Builder(context)
            .setRenderersFactory(ownTVRenderers(context, forceStereo = true))
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addListener(listener)
            }
        player = p
        surface?.let { p.setVideoSurface(it) }
        p.setMediaItem(mediaItemBuilder.build())
        p.prepare()
        p.playWhenReady = true
    }

    fun release() {
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
        surface = null
    }

    companion object {
        private const val TAG = "RoomExoEngine"
    }
}
