package tv.own.owntv.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.BuildConfig
import tv.own.owntv.R
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.diagnostics.AndroidDiagnosticProbes
import tv.own.owntv.core.diagnostics.AppFact
import tv.own.owntv.core.diagnostics.CheckResult
import tv.own.owntv.core.diagnostics.DiagnosticEvaluator
import tv.own.owntv.core.diagnostics.DiagnosticFacts
import tv.own.owntv.core.diagnostics.DiagnosticProgress
import tv.own.owntv.core.diagnostics.DiagnosticReport
import tv.own.owntv.core.diagnostics.DiagnosticRunner
import tv.own.owntv.core.diagnostics.DiagnosticSummary
import tv.own.owntv.core.diagnostics.DiagnosticText
import tv.own.owntv.core.diagnostics.EpgResync
import tv.own.owntv.core.diagnostics.Recommendation
import tv.own.owntv.core.diagnostics.VodEngine
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.sync.ImportFinalizer
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.sync.work.EpgSyncScheduler
import tv.own.owntv.features.settings.data.SettingsRepository
import tv.own.owntv.player.EnginePreference

/**
 * Settings → Diagnostic Center. Runs [DiagnosticRunner] over [AndroidDiagnosticProbes] and exposes the
 * quick fixes, each through the app's existing production path: playlist/EPG re-sync go through the
 * same WorkManager schedulers as Settings → Playlists / EPG Sources, the player switch writes the same
 * "Movies & Series player" preference as Settings → Video Player.
 *
 * Settings sub-screens share the activity-scoped ViewModel store, so this ViewModel outlives the
 * screen: the screen calls [cancel] when it leaves, which stops a running diagnostic immediately.
 * The last finished result is kept, so coming back shows it instead of a blank page.
 */
class DiagnosticCenterViewModel(
    private val appContext: Context,
    private val probes: AndroidDiagnosticProbes,
    private val settings: SettingsRepository,
    private val importFinalizer: ImportFinalizer,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val epgSyncScheduler: EpgSyncScheduler,
    private val epgSources: EpgSourceStore,
    private val epgDao: EpgDao,
    private val okHttp: okhttp3.OkHttpClient,
) : ViewModel() {

    data class UiState(
        val running: Boolean = false,
        val facts: DiagnosticFacts,
        val results: List<CheckResult>,
        val summary: DiagnosticSummary? = null,
        val recommendations: List<Recommendation> = emptyList(),
        val finishedAtMs: Long? = null,
    )

    /** One-shot confirmations shown under the actions. */
    enum class Notice { PLAYLIST_RESYNC_STARTED, NO_PLAYLIST, EPG_RESYNC_STARTED, EPG_ALREADY_RUNNING, NO_EPG, PLAYER_SWITCHED, CACHE_CLEARED, REPORT_COPIED, SHARE_UNAVAILABLE }

    private val app = AppFact(
        brandName = appContext.getString(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )

    private val _state = MutableStateFlow(idleState(DiagnosticFacts(app = app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** The current "Movies & Series player", live, for the Switch Video Player action. */
    val vodEngine: StateFlow<VodEngine> = settings.vodEnginePreference
        .map { if (it.startsOnMpv) VodEngine.MPV else VodEngine.EXO }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodEngine.MPV)

    /**
     * Live EPG sync state, read from WorkManager (the production sync owns its lifetime; this screen only
     * observes it). Null = idle. Re-reads the real state whenever the screen comes back.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val epgSync: StateFlow<EpgResync.Progress?> = epgSources.sources
        .map { list -> list.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else combine(ids.map { epgSyncScheduler.observeSync(it) }) { it.toList() }
        }
        .map(EpgResync::progressOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var job: Job? = null

    private fun idleState(facts: DiagnosticFacts) = UiState(facts = facts, results = DiagnosticEvaluator.results(facts))

    /**
     * The full diagnostic never runs the bandwidth test itself (only the lightweight latency check);
     * a speed result the customer already ran this session is carried over and informs the advice.
     */
    fun runFull() {
        if (job?.isActive == true) return
        _notice.value = null
        val keptSpeed = _state.value.facts.speed
        job = viewModelScope.launch {
            _state.value = idleState(DiagnosticFacts(app = app, speed = keptSpeed)).copy(running = true)
            try {
                val done = DiagnosticRunner(probes).run(app) { p -> _state.value = progressState(p.copy(facts = p.facts.copy(speed = keptSpeed))) }
                val facts = done.facts.copy(speed = _state.value.facts.speed ?: keptSpeed)
                val results = DiagnosticRunner.results(done.copy(facts = facts))
                _state.value = UiState(
                    running = false,
                    facts = facts,
                    results = results,
                    summary = DiagnosticEvaluator.summary(results, finished = true),
                    recommendations = DiagnosticEvaluator.recommendations(facts),
                    finishedAtMs = System.currentTimeMillis(),
                )
            } finally {
                // Cancelled (Back): drop the half-finished run rather than show partial results as final.
                if (_state.value.running) _state.value = idleState(DiagnosticFacts(app = app, speed = keptSpeed))
            }
        }
    }

    private fun progressState(p: DiagnosticProgress) = UiState(running = true, facts = p.facts, results = DiagnosticRunner.results(p))

    // --- Internet speed test (user-started only; never part of the full diagnostic) ---

    private val _speed = MutableStateFlow<tv.own.owntv.core.diagnostics.SpeedTestState>(tv.own.owntv.core.diagnostics.SpeedTestState.Idle)
    val speed: StateFlow<tv.own.owntv.core.diagnostics.SpeedTestState> = _speed.asStateFlow()

    private val _speedAtMs = MutableStateFlow<Long?>(null)
    /** When the last completed speed test finished (for Advanced Details). */
    val speedAtMs: StateFlow<Long?> = _speedAtMs.asStateFlow()

    private var speedJob: Job? = null

    /**
     * Starts a brand-new measurement (a new engine and transport every time — nothing carries over
     * from a previous run). The UI receives only values the engine measured, at most every
     * [SPEED_UI_INTERVAL_MS] during the download; phase changes and the final result always go through.
     */
    fun runSpeedTest() {
        if (speedJob?.isActive == true) return
        speedJob = viewModelScope.launch {
            val transport = tv.own.owntv.core.diagnostics.OkHttpSpeedTestTransport(okHttp, tv.own.owntv.core.diagnostics.SpeedTestEndpoint.configured())
            val lastEmit = java.util.concurrent.atomic.AtomicLong(0)
            _speed.value = tv.own.owntv.core.diagnostics.SpeedTestState.Latency(0, 0)
            try {
                val end = tv.own.owntv.core.diagnostics.SpeedTestEngine(transport).run { st ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    val sameDownloadPhase = st is tv.own.owntv.core.diagnostics.SpeedTestState.Downloading &&
                        _speed.value is tv.own.owntv.core.diagnostics.SpeedTestState.Downloading
                    if (!sameDownloadPhase || now - lastEmit.get() >= SPEED_UI_INTERVAL_MS) {
                        lastEmit.set(now)
                        _speed.value = st
                    }
                }
                _speed.value = end
                if (end is tv.own.owntv.core.diagnostics.SpeedTestState.Done) {
                    _speedAtMs.value = System.currentTimeMillis()
                    val s = _state.value
                    val facts = s.facts.copy(speed = end.result)
                    _state.value = s.copy(
                        facts = facts,
                        recommendations = if (s.summary != null) DiagnosticEvaluator.recommendations(facts) else s.recommendations,
                    )
                }
            } finally {
                transport.release()
                // Cancelled (Cancel / Back): back to a clean, startable state; nothing half-measured is kept.
                val v = _speed.value
                if (v !is tv.own.owntv.core.diagnostics.SpeedTestState.Done && v !is tv.own.owntv.core.diagnostics.SpeedTestState.Failed) {
                    _speed.value = tv.own.owntv.core.diagnostics.SpeedTestState.Idle
                }
            }
        }
    }

    fun cancelSpeedTest() {
        speedJob?.cancel()
        speedJob = null
    }

    /** Leaving the screen stops a running diagnostic and a running speed test (no background bandwidth). */
    fun cancel() {
        job?.cancel()
        job = null
        cancelSpeedTest()
    }

    /** Re-sync every active playlist through the normal (non-pruning) Settings → Playlists path. */
    fun resyncPlaylist() {
        viewModelScope.launch {
            val sources = probes.activeSources()
            if (sources.isEmpty()) { _notice.value = Notice.NO_PLAYLIST; return@launch }
            for (s in sources) {
                val counts = importFinalizer.contentCounts(s.id)
                catalogSyncScheduler.enqueueSync(
                    s.id,
                    reason = RESYNC_REASON,
                    contentTypes = SyncContentTypes.enabledOf(s),
                    baseItemCount = counts.channels + counts.movies + counts.series,
                    // A playlist sync already running (startup refresh, or an earlier press) is left alone.
                    policy = ExistingWorkPolicy.KEEP,
                )
            }
            _notice.value = Notice.PLAYLIST_RESYNC_STARTED
        }
    }

    /**
     * Re-sync every EPG source through the production EpgSyncWorker (Settings → EPG Sources → Re-sync).
     * The work belongs to WorkManager, so it keeps running after this screen or the app is left. A sync
     * that is already running is never started again: the button reports it instead, and KEEP makes
     * WorkManager ignore a second request even if two presses race.
     */
    fun resyncEpg() {
        viewModelScope.launch {
            val sources = epgSources.getAll()
            val current = EpgResync.progressOf(sources.map { epgSyncScheduler.observeSync(it.id).first() })
            when (EpgResync.decide(sources.size, current)) {
                EpgResync.Decision.NO_SOURCES -> _notice.value = Notice.NO_EPG
                EpgResync.Decision.ALREADY_RUNNING -> _notice.value = Notice.EPG_ALREADY_RUNNING
                EpgResync.Decision.START -> {
                    for (s in sources) {
                        epgSyncScheduler.enqueueSync(s.id, RESYNC_REASON, epgDao.countForSources(listOf(s.id)), ExistingWorkPolicy.KEEP)
                    }
                    _notice.value = Notice.EPG_RESYNC_STARTED
                }
            }
        }
    }

    /**
     * MPV ↔ ExoPlayer for Movies & Series. Moves to the other engine's "first" mode, so automatic
     * fallback to the other engine stays on — the safest choice for a customer who is troubleshooting.
     */
    fun switchVodPlayer() {
        viewModelScope.launch {
            val target = DiagnosticEvaluator.switchTarget(vodEngine.value)
            settings.setVodEnginePreference(if (target == VodEngine.MPV) EnginePreference.MPV_FIRST else EnginePreference.EXO_FIRST)
            _notice.value = Notice.PLAYER_SWITCHED
            val s = _state.value
            if (s.facts.player != null) {
                val facts = s.facts.copy(player = s.facts.player.copy(vodEngine = target, exclusive = false))
                _state.value = s.copy(facts = facts)
            }
        }
    }

    /**
     * Clears only the poster/logo image cache (memory + disk) through the image loader's own API.
     * Everything else in the app's cache directory is left alone on purpose: it also holds EPG import
     * files and pending remote-import/backup files, which are not safe to delete underneath the app.
     * Playlists, profiles, favorites, history, settings and databases live elsewhere and are untouched.
     */
    fun clearImageCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val loader = SingletonImageLoader.get(appContext)
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
            }
            _notice.value = Notice.CACHE_CLEARED
        }
    }

    fun buildReport(text: DiagnosticText): String {
        val s = _state.value
        val summary = s.summary ?: DiagnosticEvaluator.summary(s.results, finished = false)
        return DiagnosticReport.build(s.facts, s.results, summary, s.recommendations, text, System.currentTimeMillis())
    }

    fun notify(notice: Notice) { _notice.value = notice }

    fun clearNotice() { _notice.value = null }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    private companion object {
        const val RESYNC_REASON = "diagnostic_resync"
        const val SPEED_UI_INTERVAL_MS = 250L
    }
}
