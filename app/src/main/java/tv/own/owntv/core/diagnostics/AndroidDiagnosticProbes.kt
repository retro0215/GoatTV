package tv.own.owntv.core.diagnostics

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.stalker.StalkerAuthManager
import tv.own.owntv.core.stalker.StalkerClient
import tv.own.owntv.core.stalker.stalkerCredentials
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.update.UpdateManager
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.features.sports.live.SportsApiClient
import tv.own.owntv.features.sports.live.SportsApiResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * The real, read-only probes behind the Diagnostic Center. Every one reuses the app's existing
 * client for that job — the shared OkHttp stack (proxy/DNS honoured), [XtreamClient] /
 * [StalkerAuthManager] with the user's saved playlist, [SportsApiClient], [UpdateManager] — and
 * returns only typed facts. Nothing here writes settings, starts a sync or plays a stream; nothing
 * logs a URL or credential (the shared clients already redact their own logs).
 */
class AndroidDiagnosticProbes(
    private val context: Context,
    okHttp: OkHttpClient,
    private val http: HttpClient,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val importFinalizer: ImportFinalizer,
    private val xtream: XtreamClient,
    private val stalkerAuth: StalkerAuthManager,
    private val epgSources: EpgSourceStore,
    private val epgDao: EpgDao,
    private val sports: SportsApiClient,
    private val updates: UpdateManager,
) : DiagnosticProbes {

    // Short timeouts: a diagnostic should say "could not connect" quickly, not hang the screen.
    private val probeHttp: OkHttpClient = okHttp.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** The playlists the user is browsing: the active profile's sources, narrowed to the chosen one. */
    suspend fun activeSources(): List<SourceEntity> = activeProfileSources(settings, sourceDao).first().sources

    override suspend fun internet(): InternetFact {
        val transport = transport()
        // Android's own captive-portal endpoints: tiny 204 replies, independent of the playlist provider
        // and of the Sports service, so "internet works" is never confused with "the service works".
        val reachable = INTERNET_ENDPOINTS.any { url -> statusOf(url) == 204 }
        return InternetFact(reachable, transport)
    }

    private fun transport(): NetworkTransport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NetworkTransport.OTHER
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return NetworkTransport.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            else -> NetworkTransport.OTHER
        }
    }

    /** HTTP status of a GET, or null on any network failure. Cancelling the coroutine cancels the call. */
    private suspend fun statusOf(url: String): Int? = suspendCancellableCoroutine { cont ->
        val call = probeHttp.newCall(Request.Builder().url(url).get().build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.use { it.code }
                if (cont.isActive) cont.resume(code)
            }
        })
    }

    override suspend fun service(): ServiceFact {
        val sources = activeSources()
        if (sources.isEmpty()) return ServiceFact.NoSource
        // Bounded: a household with many playlists gets the first few tested, one at a time.
        val outcomes = sources.take(MAX_SERVICE_SOURCES).map { serviceOf(it) }
        return outcomes.firstOrNull { it is ServiceFact.Rejected }
            ?: outcomes.firstOrNull { it is ServiceFact.Unreachable }
            ?: outcomes.first()
    }

    private suspend fun serviceOf(s: SourceEntity): ServiceFact = when (s.type) {
        SourceType.XTREAM -> {
            // fetchAccountDetails never throws: null = no usable answer (network/HTTP/parse failure).
            val details = xtream.fetchAccountDetails(s)
            when {
                details == null -> ServiceFact.Unreachable(ServiceKind.XTREAM)
                details.authenticated == false -> ServiceFact.Rejected(ServiceKind.XTREAM)
                else -> ServiceFact.Ok(ServiceKind.XTREAM)
            }
        }
        SourceType.STALKER -> {
            val mac = s.mac?.let { StalkerClient.canonicalizeMac(it) }
            if (mac == null) {
                ServiceFact.Rejected(ServiceKind.STALKER)
            } else {
                try {
                    // The add-playlist form's "Test connection": a fresh handshake + profile.
                    stalkerAuth.testConnection(s.stalkerCredentials(mac))
                    ServiceFact.Ok(ServiceKind.STALKER)
                } catch (e: StalkerClient.StalkerAuthException) {
                    ServiceFact.Rejected(ServiceKind.STALKER)
                } catch (e: IOException) {
                    ServiceFact.Unreachable(ServiceKind.STALKER)
                }
            }
        }
        SourceType.M3U -> m3uReachable(s)
        SourceType.LOCAL_BACKUP -> ServiceFact.Ok(ServiceKind.OTHER)
    }

    /** Reads at most the first kilobyte of the playlist (Range request); the rest is never downloaded. */
    private suspend fun m3uReachable(s: SourceEntity): ServiceFact = try {
        http.get(s.url, s.userAgent, headers = mapOf(RANGE_HEADER to RANGE_FIRST_KB)) { input ->
            input.read(ByteArray(1024))
        }
        ServiceFact.Ok(ServiceKind.M3U)
    } catch (e: HttpClient.HttpStatusException) {
        if (e.code == 401 || e.code == 403) ServiceFact.Rejected(ServiceKind.M3U) else ServiceFact.Unreachable(ServiceKind.M3U)
    } catch (e: IOException) {
        ServiceFact.Unreachable(ServiceKind.M3U)
    }

    override suspend fun playlist(): PlaylistFact {
        val sources = activeSources()
        if (sources.isEmpty()) return PlaylistFact.NONE
        var channels = 0
        var movies = 0
        var series = 0
        for (s in sources) {
            val c = importFinalizer.contentCounts(s.id)
            if (s.syncLive) channels += c.channels
            if (s.syncMovies) movies += c.movies
            if (s.syncSeries) series += c.series
        }
        return PlaylistFact(
            hasSource = true,
            channels = channels,
            movies = movies,
            series = series,
            liveEnabled = sources.any { it.syncLive },
            moviesEnabled = sources.any { it.syncMovies },
            seriesEnabled = sources.any { it.syncSeries },
            lastSyncAtMs = sources.mapNotNull { it.lastSyncAt }.maxOrNull(),
        )
    }

    override suspend fun epg(): EpgFact {
        val list = epgSources.getAll()
        val ids = list.map { it.id }
        val programmes = if (ids.isEmpty()) 0 else epgDao.countForSources(ids)
        val guideChannels = if (ids.isEmpty()) 0 else epgDao.countGuideChannels(ids)
        return EpgFact(
            sourceCount = list.size,
            programmes = programmes,
            guideChannels = guideChannels,
            lastSyncAtMs = list.mapNotNull { it.lastSyncAt }.maxOrNull(),
            lastSyncFailed = list.any { it.lastError != null },
        )
    }

    override suspend fun sports(): SportsFact = when (val home = sports.home()) {
        is SportsApiResult.Success -> {
            val first = home.value.events.firstOrNull()
            // Capability check only: one Game Center request for an event the API itself listed.
            val gameCenterOk = first?.let { sports.gameCenter(it.id) is SportsApiResult.Success }
            SportsFact.Connected(home.value.events.size, gameCenterOk)
        }
        else -> SportsFact.Unavailable
    }

    override suspend fun player(): PlayerFact {
        val pref = settings.vodEnginePreference.first()
        return PlayerFact(
            vodEngine = if (pref.startsOnMpv) VodEngine.MPV else VodEngine.EXO,
            exclusive = !pref.allowsHandover,
        )
    }

    override suspend fun update(): UpdateFact {
        if (updates.state.value is UpdateManager.State.Downloading) return UpdateFact.Busy
        updates.check()
        val result = updates.state.first { it !is UpdateManager.State.Checking && it !is UpdateManager.State.Idle }
        val fact = when (result) {
            UpdateManager.State.UpToDate -> UpdateFact.UpToDate
            is UpdateManager.State.Available -> UpdateFact.Available(result.info.version)
            is UpdateManager.State.Downloading -> UpdateFact.Busy
            else -> UpdateFact.CheckFailed
        }
        // Leave the shared updater idle so no other screen reacts to a check the user didn't start
        // there; CHECK FOR UPDATE opens the normal update dialog, which checks again.
        if (fact !is UpdateFact.Busy) updates.reset()
        return fact
    }

    override suspend fun device(): DeviceFact = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        DeviceFact(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            storageFreeBytes = stat?.availableBytes ?: 0L,
            storageTotalBytes = stat?.totalBytes ?: 0L,
            screenWidthPx = mode?.physicalWidth ?: 0,
            screenHeightPx = mode?.physicalHeight ?: 0,
            refreshRateHz = mode?.refreshRate ?: (display?.refreshRate ?: 0f),
            codecs = codecs(),
        )
    }

    /**
     * Which decoders the device *reports*. Presence of a decoder does not prove every stream in that
     * format will play (profile, level, bit depth and container all matter) — the screen says so.
     */
    private fun codecs(): Map<DiagCodec, CodecSupport> {
        val decoderTypes = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.map { t -> t.lowercase(java.util.Locale.ROOT) } }
                .toSet()
        }.onFailure { Log.w(TAG, "codec list unavailable: ${it.javaClass.simpleName}") }.getOrDefault(emptySet())
        return CODEC_MIME.mapValues { (_, mime) -> if (mime in decoderTypes) CodecSupport.SUPPORTED else CodecSupport.NOT_REPORTED }
    }

    companion object {
        private const val TAG = "Diagnostics"
        private const val MAX_SERVICE_SOURCES = 3
        private const val RANGE_HEADER = "Range"
        private const val RANGE_FIRST_KB = "bytes=0-1023"
        private val INTERNET_ENDPOINTS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
        )
        // MIME constants are compile-time strings, inlined into the APK; reading them is safe below API 29.
        @SuppressLint("InlinedApi")
        private val CODEC_MIME = mapOf(
            DiagCodec.H264 to MediaFormat.MIMETYPE_VIDEO_AVC,
            DiagCodec.HEVC to MediaFormat.MIMETYPE_VIDEO_HEVC,
            DiagCodec.AV1 to MediaFormat.MIMETYPE_VIDEO_AV1,
            DiagCodec.AAC to MediaFormat.MIMETYPE_AUDIO_AAC,
            DiagCodec.AC3 to MediaFormat.MIMETYPE_AUDIO_AC3,
            DiagCodec.EAC3 to MediaFormat.MIMETYPE_AUDIO_EAC3,
        )
    }
}
