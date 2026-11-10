package tv.own.owntv.features.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.backup.BackupManager
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ProfileEntity
import tv.own.owntv.core.database.entity.ProfileSourceCrossRef
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.sync.ImportStage
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncCounts
import tv.own.owntv.core.sync.SyncResult
import tv.own.owntv.core.sync.SyncScopeChoice
import tv.own.owntv.core.sync.SyncWarning
import tv.own.owntv.core.sync.work.CatalogSyncScheduler
import tv.own.owntv.core.util.FriendlySyncFailure
import tv.own.owntv.core.util.Pin
import tv.own.owntv.core.util.classifySyncFailure
import tv.own.owntv.core.launcher.LauncherIntegrationRepository
import tv.own.owntv.features.settings.data.PlaylistAutoRefresh
import tv.own.owntv.features.settings.data.SettingsRepository
import java.io.File

/**
 * Drives onboarding for a profile (first-run and "add profile"): create the profile, then add content
 * (new source, link an existing unlocked profile's playlists, restore a backup, or skip). The new
 * profile is only made active on [finish], so the wizard stays put until the user completes it.
 */
class SetupViewModel(
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val sourceRepository: SourceRepository,
    private val backup: BackupManager,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val importFinalizer: tv.own.owntv.core.sync.ImportFinalizer,
    private val epgRepository: tv.own.owntv.core.repository.EpgRepository,
    private val epgSourceStore: tv.own.owntv.core.epg.EpgSourceStore,
    private val launcherIntegrationRepository: LauncherIntegrationRepository,
    private val catalogSyncScheduler: CatalogSyncScheduler,
    private val stalkerAuth: tv.own.owntv.core.stalker.StalkerAuthManager,
    private val companion: tv.own.owntv.core.companion.CompanionController,
) : ViewModel() {

    // ---- Remote (companion) add-source: a LAN web form fills the Add Source screen from another device. ----
    /** Server lifecycle (Idle / Starting / Listening with PIN+QR / Failed) for the Remote screen. */
    val remoteState get() = companion.state

    /** Live submission stream — the Remote screen collects it to hand off to the Manual form. */
    val remotePayloads get() = companion.payloads

    /** Retained last submission, so the Manual form pre-fills even after the Remote screen left. */
    val remotePayload get() = companion.lastPayload

    fun startRemoteListener(port: Int) = companion.start(port)
    fun stopRemoteListener() = companion.stop()
    fun consumeRemotePayload() = companion.consumePayload()

    // ---- Remote restore: another device uploads a backup JSON to the TV over the LAN companion server. ----
    /** Uploaded backup files — the remote-restore screen collects this and hands each to [importBackup]. */
    val remoteBackups get() = companion.backups

    fun startRemoteRestore(port: Int) = companion.startForBackupRestore(port)
    fun stopRemoteRestore() = companion.stop()

    // Semi-auto EPG: after the first playlist imports, offer a one-tap guide sync (with a live count) if it
    // has a guide feed.
    private var pendingEpgSource: SourceEntity? = null
    private var epgJob: Job? = null
    // Set when the user leaves the wizard with "Run in background" (re-syncing linked playlists only, see
    // [importBackgroundAllowed]). A late failure must not silently DELETE a source the user thinks they
    // added — keep it (credentials intact) and only wipe partial content.
    private var backgroundHandoff = false

    /** What the import screen is running: a new playlist (asks about its guide) or linked existing ones. */
    enum class ImportKind { NEW_PLAYLIST, EXISTING_PLAYLISTS }

    private var importKind: ImportKind? = null
    private val _runInBackgroundAvailable = MutableStateFlow(false)

    /** Whether the import screen offers "Run in background" — see [importBackgroundAllowed]. */
    val runInBackgroundAvailable: StateFlow<Boolean> = _runInBackgroundAvailable.asStateFlow()

    private fun startImportOf(kind: ImportKind) {
        importKind = kind
        _runInBackgroundAvailable.value = importBackgroundAllowed(kind)
    }
    private val _epgSync = MutableStateFlow<tv.own.owntv.features.settings.EpgSyncUi>(tv.own.owntv.features.settings.EpgSyncUi.Hidden)
    val epgSync: StateFlow<tv.own.owntv.features.settings.EpgSyncUi> = _epgSync.asStateFlow()

    fun syncPendingEpg() {
        val src = pendingEpgSource ?: return
        if (epgJob?.isActive == true) return // a second tap must not start a second download
        epgJob = viewModelScope.launch {
            tv.own.owntv.features.settings.runSemiAutoEpgSync(src, epgRepository, epgSourceStore) { _epgSync.value = it }
        }
    }

    fun dismissPendingEpg() { pendingEpgSource = null; _epgSync.value = tv.own.owntv.features.settings.EpgSyncUi.Hidden }

    /**
     * "Run in background" for the semi-auto EPG sync: enter the app now while the guide keeps
     * downloading. The sync launched by [syncPendingEpg] runs in this activity-scoped [viewModelScope],
     * so it survives leaving the wizard — exactly like the playlist [continueInBackground]. We only
     * finish onboarding; the in-flight job is deliberately not cancelled.
     */
    fun syncEpgInBackground(onDone: (Long?) -> Unit = {}) {
        pendingEpgSource = null
        finish(onDone)
    }

    sealed interface SetupFailure {
        data object InvalidMac : SetupFailure
        data object BackupRead : SetupFailure
        data object Restore : SetupFailure
        data class Sync(val failure: FriendlySyncFailure) : SetupFailure
    }

    sealed interface ImportState {
        data object Idle : ImportState
        data object Running : ImportState
        data class Success(
            val counts: SyncCounts? = null,
            val warnings: List<SyncWarning> = emptyList(),
            val remainder: SyncContentTypes = SyncContentTypes(false, false, false),
            val restoredItems: Int? = null,
            val passwordsOmitted: Boolean = false,
            val skippedSources: Int = 0,
            val invalidLocale: Boolean = false,
        ) : ImportState
        data class Failed(val failure: SetupFailure) : ImportState
        /** Encrypted backup needs the backup password before restoring; [retry] after a wrong attempt.
         *  [sealed] marks a whole-file-encrypted `.own`, where the password is mandatory — there is
         *  nothing to restore without it, so the wizard hides "Skip". */
        data class NeedPassword(val file: File, val retry: Boolean = false, val sealed: Boolean = false) : ImportState
    }

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** Failed source preserved so AddSourceScreen pre-fills on retry — no re-typing on remote. */
    var lastFailedSource: SourceEntity? = null
        private set

    private val _progress = MutableStateFlow<ImportStage?>(null)
    val progress: StateFlow<ImportStage?> = _progress.asStateFlow()

    private var createdProfileId = -1L
    private var createdProfileName = ""
    private var importJob: Job? = null

    /** Creates the profile (not active yet); the rest of onboarding attaches content to it. */
    fun createProfile(name: String, avatarId: Int, isKids: Boolean, pin: String?, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch {
            createdProfileName = name
            createdProfileId = profileDao.insert(
                ProfileEntity(
                    name = name,
                    avatarColor = 0,
                    avatarId = avatarId,
                    isKids = isKids,
                    pinHash = pin?.takeIf { it.isNotBlank() }?.let { Pin.hash(it) },
                ),
            )
            onCreated(createdProfileId)
        }
    }

    fun startXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        userAgent: String = "",
        epgUrl: String = "",
        autoRefresh: PlaylistAutoRefresh = PlaylistAutoRefresh.OFF,
        live: SyncScopeChoice = SyncScopeChoice.Now,
        movies: SyncScopeChoice = SyncScopeChoice.Now,
        series: SyncScopeChoice = SyncScopeChoice.Now,
        preferHls: Boolean = false,
    ) {
        val enabled = SyncContentTypes.fromChoices(live, movies, series)
        val priority = SyncContentTypes.priorityFromChoices(live, movies, series)
        runImport(autoRefresh, priority, enabledScope = enabled, enqueueRemainder = true, requiresNetwork = true) { profileId ->
            sourceRepository.addXtreamSource(
                profileId = profileId,
                name = name.trim(),
                serverUrl = server.trim(),
                username = username.trim(),
                password = password,
                userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
                syncLive = enabled.live, syncMovies = enabled.movies, syncSeries = enabled.series,
                preferHls = preferHls,
            )
        }
    }

    /** Stalker/MAC portal onboarding — mirrors SettingsViewModel.addStalker: the handshake is verified
     *  BEFORE the source is saved, so a typo'd portal/MAC fails with a clear error instead of leaving a
     *  dead source on the brand-new profile. Defaults: Live Now, Movies/Series Later (Stalker VOD has
     *  no bulk endpoint). Off sections are never fetched or shown. */
    fun startStalker(
        name: String,
        portalUrl: String,
        mac: String,
        serialNumber: String = "",
        deviceId: String = "",
        deviceId2: String = "",
        signature: String = "",
        userAgent: String = "",
        autoRefresh: PlaylistAutoRefresh = PlaylistAutoRefresh.OFF,
        live: SyncScopeChoice = SyncScopeChoice.Now,
        movies: SyncScopeChoice = SyncScopeChoice.Later,
        series: SyncScopeChoice = SyncScopeChoice.Later,
    ) {
        val canonicalMac = tv.own.owntv.core.stalker.StalkerClient.canonicalizeMac(mac)
        if (canonicalMac == null) {
            _state.value = ImportState.Failed(SetupFailure.InvalidMac)
            return
        }
        val enabled = SyncContentTypes.fromChoices(live, movies, series)
        val priority = SyncContentTypes.priorityFromChoices(live, movies, series)
        runImport(autoRefresh, contentTypes = priority, enabledScope = enabled, enqueueRemainder = true, requiresNetwork = true) { profileId ->
            stalkerAuth.testConnection(
                tv.own.owntv.core.stalker.StalkerCredentials(
                    sourceId = STALKER_TEST_SOURCE_ID,
                    portalUrl = portalUrl.trim(),
                    mac = canonicalMac,
                    userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                    deviceIdentity = tv.own.owntv.core.stalker.StalkerDeviceIdentity(
                        serialNumber = serialNumber.trim().takeIf { it.isNotBlank() },
                        deviceId = deviceId.trim().takeIf { it.isNotBlank() },
                        deviceId2 = deviceId2.trim().takeIf { it.isNotBlank() },
                        signature = signature.trim().takeIf { it.isNotBlank() },
                    ),
                ),
            )
            sourceRepository.addStalkerSource(
                profileId, name.trim(), portalUrl.trim(), canonicalMac,
                serialNumber.trim().takeIf { it.isNotBlank() },
                deviceId.trim().takeIf { it.isNotBlank() },
                deviceId2.trim().takeIf { it.isNotBlank() },
                signature.trim().takeIf { it.isNotBlank() },
                userAgent.trim().takeIf { it.isNotBlank() },
                syncLive = enabled.live, syncMovies = enabled.movies, syncSeries = enabled.series,
            )
        }
    }

    fun startM3u(name: String, url: String, userAgent: String = "", epgUrl: String = "", autoRefresh: PlaylistAutoRefresh = PlaylistAutoRefresh.OFF) =
        runImport(autoRefresh, requiresNetwork = !url.isLocalPlaylistPath()) { profileId ->
            sourceRepository.addM3uSource(
                profileId = profileId,
                name = name.trim(),
                url = url.trim(),
                userAgent = userAgent.trim().takeIf { it.isNotBlank() },
                epgUrl = epgUrl.trim().takeIf { it.isNotBlank() },
            )
        }

    private fun runImport(
        autoRefresh: PlaylistAutoRefresh? = PlaylistAutoRefresh.OFF, // null: keep the playlist's own choice
        contentTypes: SyncContentTypes = SyncContentTypes(),
        enabledScope: SyncContentTypes = SyncContentTypes(),
        enqueueRemainder: Boolean = false,
        requiresNetwork: Boolean = true,
        addSource: suspend (Long) -> SourceEntity,
    ) {
        importJob?.cancel()
        // A new playlist: its guide question comes once it has imported, so "Run in background" waits
        // until then. A retry starts clean — no leftover hand-off or guide question from the last attempt.
        startImportOf(ImportKind.NEW_PLAYLIST)
        backgroundHandoff = false
        dismissPendingEpg()
        val job = viewModelScope.launch {
            _state.value = ImportState.Running
            _progress.value = null
            var source: SourceEntity? = null
            try {
                if (requiresNetwork && !connectivity.isOnlineNow()) {
                    _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(null, online = false)))
                    return@launch
                }
                val profileId = createdProfileId.takeIf { it > 0 } ?: ensureFallbackProfile()
                source = addSource(profileId)
                val freshSync = source.lastSyncAt == null
                val remainder = if (enqueueRemainder) {
                    enabledScope.remainderAfter(contentTypes)
                } else {
                    SyncContentTypes(live = false, movies = false, series = false)
                }
                autoRefresh?.let { settings.setPlaylistAutoRefresh(source.id, it) }
                when (val result = sourceRepository.sync(source, onProgress = { _progress.value = it }, contentTypes = contentTypes)) {
                    is SyncResult.Success -> {
                        // Just the playlist content — EPG is added separately (Settings → EPG sources).
                        val counts = importFinalizer.finalize(source, deferIndexes = freshSync)
                        val syncedSource = sourceDao.getById(source.id) ?: source
                        if (enqueueRemainder) enqueueRemainderSync(source, contentTypes, enabledScope)
                        if (freshSync && !remainder.hasAny) catalogSyncScheduler.enqueueContentIndexBuild(reason = "fresh_add")
                        lastFailedSource = null
                        // Survives the app being killed before setup finishes: the restart then resumes
                        // at the guide question instead of importing the playlist a second time.
                        settings.setOnboardingImportedSource(source.id)
                        _state.value = ImportState.Success(
                            counts = counts,
                            warnings = result.warnings,
                            remainder = remainder,
                        )
                        if (!backgroundHandoff && epgRepository.guideUrl(syncedSource) != null) {
                            pendingEpgSource = syncedSource
                            _epgSync.value = tv.own.owntv.features.settings.EpgSyncUi.Ask(syncedSource.name)
                        }
                        viewModelScope.launch { runCatching { launcherIntegrationRepository.refreshProfile(profileId) } }
                    }
                    is SyncResult.Failed -> {
                        cleanupFailedAdd(source)
                        _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(result.message, connectivity.isOnlineNow())))
                    }
                    SyncResult.Cancelled -> {
                        cleanupFailedAdd(source)
                        _state.value = ImportState.Idle
                    }
                }
            } catch (c: CancellationException) {
                cleanupFailedAdd(source)
                _state.value = ImportState.Idle
                _progress.value = null
                throw c
            } catch (e: Exception) {
                cleanupFailedAdd(source)
                _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(e.message, connectivity.isOnlineNow())))
            }
        }
        importJob = job
        job.invokeOnCompletion { if (importJob == job) importJob = null }
    }

    private fun String.isLocalPlaylistPath(): Boolean =
        startsWith("/") || startsWith("file://") || startsWith("content://")

    private fun enqueueRemainderSync(source: SourceEntity, priority: SyncContentTypes, enabledScope: SyncContentTypes) {
        val remainder = enabledScope.remainderAfter(priority)
        if (remainder.hasAny) {
            catalogSyncScheduler.enqueueSync(source.id, reason = "add_remainder", contentTypes = remainder, completesInitialSync = true)
        }
    }

    /** Playlists belonging to unlocked (no-PIN) profiles that aren't already on the new profile. */
    suspend fun availableExistingSources(): List<SourceEntity> {
        val unlocked = profileDao.getAllOnce().filter { it.pinHash == null && it.id != createdProfileId }.map { it.id }.toSet()
        if (unlocked.isEmpty()) return emptyList()
        val links = sourceDao.allLinks()
        val fromUnlocked = links.filter { it.profileId in unlocked }.map { it.sourceId }.toSet()
        val alreadyMine = links.filter { it.profileId == createdProfileId }.map { it.sourceId }.toSet()
        val wanted = fromUnlocked - alreadyMine
        return sourceDao.getAllOnce().filter { it.id in wanted }
    }

    /**
     * Link the chosen existing sources to the new profile (shared content, separate favorites/history),
     * then re-sync each one so its catalog is fresh — exactly like adding a brand-new source. Drives the
     * same [state]/[progress] as [runImport], so the wizard can show the import screen.
     */
    fun linkExisting(sourceIds: Set<Long>) {
        importJob?.cancel()
        startImportOf(ImportKind.EXISTING_PLAYLISTS)
        val job = viewModelScope.launch {
            _state.value = ImportState.Running
            _progress.value = null
            try {
                val pid = createdProfileId.takeIf { it > 0 } ?: ensureFallbackProfile()
                sourceIds.forEach { sourceDao.link(ProfileSourceCrossRef(profileId = pid, sourceId = it)) }
                val sources = sourceDao.getAllOnce().filter { it.id in sourceIds }
                var total = tv.own.owntv.core.sync.SyncCounts(0, 0, 0, 0)
                var failure: String? = null
                val warnings = mutableListOf<SyncWarning>()
                for (source in sources) {
                    when (val result = sourceRepository.sync(source, onProgress = { _progress.value = it })) {
                        is SyncResult.Success -> {
                            val c = importFinalizer.finalize(source)
                            total = tv.own.owntv.core.sync.SyncCounts(total.channels + c.channels, total.movies + c.movies, total.series + c.series, total.epg + c.epg)
                            warnings += result.warnings
                        }
                        is SyncResult.Failed -> failure = result.message
                        SyncResult.Cancelled -> {}
                    }
                }
                runCatching { launcherIntegrationRepository.refreshProfile(pid) }
                _state.value = failure?.let { ImportState.Failed(SetupFailure.Sync(classifySyncFailure(it, connectivity.isOnlineNow()))) }
                    ?: ImportState.Success(counts = total, warnings = warnings)
            } catch (c: CancellationException) {
                _state.value = ImportState.Idle
                _progress.value = null
                throw c
            } catch (e: Exception) {
                _state.value = ImportState.Failed(SetupFailure.Sync(classifySyncFailure(e.message, connectivity.isOnlineNow())))
            }
        }
        importJob = job
        job.invokeOnCompletion { if (importJob == job) importJob = null }
    }

    /** Restore everything from a backup file (replaces profiles & sources, then activates one). Encrypted
     *  backups first ask for the backup password via [ImportState.NeedPassword]. */
    fun importBackup(file: File, onDone: (Long?) -> Unit) {
        viewModelScope.launch {
            _state.value = ImportState.Running
            // A sealed .own reveals nothing before it is decrypted — ask for the password first.
            if (backup.isSealed(file)) {
                _state.value = ImportState.NeedPassword(file, sealed = true)
                return@launch
            }
            val inspection = backup.sectionsIn(file).getOrElse {
                _state.value = ImportState.Failed(SetupFailure.BackupRead)
                return@launch
            }
            if (inspection.encrypted) _state.value = ImportState.NeedPassword(file)
            else doRestore(file, null, onDone)
        }
    }

    /** Continue an encrypted restore once the user provides (or skips, password = null) the passphrase. */
    fun restoreWithPassword(file: File, password: String?, onDone: (Long?) -> Unit) {
        viewModelScope.launch {
            _state.value = ImportState.Running
            doRestore(file, password, onDone)
        }
    }

    private suspend fun doRestore(file: File, password: String?, onDone: (Long?) -> Unit) {
        backup.import(file, backupPassword = password).fold(
            onSuccess = { summary ->
                _state.value = ImportState.Success(
                    restoredItems = summary.items,
                    passwordsOmitted = password.isNullOrBlank(),
                    skippedSources = summary.skippedSources,
                    invalidLocale = summary.invalidLocale,
                )
                // A backup may restore several profiles or a PIN-locked active profile. Restoring
                // data is not authenticating a viewer, so let MainActivity require the selected
                // profile's PIN rather than treating the restore as a gate pass.
                onDone(null)
            },
            onFailure = {
                if (it is BackupManager.WrongPasswordException) {
                    _state.value = ImportState.NeedPassword(file, retry = true, sealed = backup.isSealed(file))
                }
                else _state.value = ImportState.Failed(SetupFailure.Restore)
            },
        )
    }

    private suspend fun ensureFallbackProfile(): Long {
        if (createdProfileId > 0) return createdProfileId
        createdProfileId = profileDao.insert(ProfileEntity(name = createdProfileName, avatarColor = 0, avatarId = 0))
        return createdProfileId
    }

    fun reset() {
        _state.value = ImportState.Idle
        _progress.value = null
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _state.value = ImportState.Idle
        _progress.value = null
    }

    /**
     * "Run in background": enter the app now while the import keeps running. This ViewModel is
     * activity-scoped, so the in-flight [importJob] survives leaving the wizard — the sync continues
     * exactly as if the user had waited (success still runs ImportFinalizer + the remainder enqueue).
     * Deliberately does NOT cancel: cancelling would run [cleanupFailedAdd] and delete the source.
     *
     * Refused while a new playlist imports ([importBackgroundAllowed]): leaving then used to skip its
     * guide question for good and the TV Guide stayed empty. That playlist's "Run in background" is the
     * guide dialog's, once the question has been answered.
     */
    fun continueInBackground(onDone: (Long?) -> Unit = {}) {
        if (!importBackgroundAllowed(importKind)) return
        backgroundHandoff = true
        dismissPendingEpg()
        finish(onDone)
    }

    /** Completes onboarding → makes the new profile active, routing the app into the shell. */
    fun finish(onDone: (Long?) -> Unit = {}) {
        viewModelScope.launch {
            if (createdProfileId > 0) settings.setActiveProfile(createdProfileId)
            settings.setOnboardingImportedSource(-1L)
            // A PIN-locked profile picked up from an interrupted setup was not just created by whoever is
            // in front of the TV now: pass no id, so the profile gate asks for its PIN.
            onDone(if (resumedLockedProfile) null else settings.activeProfileId.first().takeIf { it >= 0L })
        }
    }

    // ---- Interrupted first run: the app was killed after the profile (and maybe its playlist) existed
    // but before setup finished. The next launch has no active profile and would start setup from
    // scratch — a second profile, the same playlist again, and a full re-download. Resume instead. ----

    private var resumedLockedProfile = false

    /** What an interrupted first-run setup left behind. [importFinished]: its first import completed. */
    data class InterruptedSetup(val profile: ProfileEntity, val source: SourceEntity?, val importFinished: Boolean)

    /** Where the wizard picks up: the import screen (guide question or import), or adding a playlist. */
    enum class ResumePoint { IMPORT, ADD_PLAYLIST }

    /**
     * The newest profile left by an interrupted first run, with its newest playlist, or null when there is
     * nothing to resume: a profile is active, there are no profiles, or this ViewModel is itself in the
     * middle of setup (a configuration change, not an interruption).
     */
    suspend fun findInterruptedSetup(): InterruptedSetup? {
        if (createdProfileId > 0 || importJob != null || _state.value != ImportState.Idle) return null
        if (settings.activeProfileId.first() >= 0L) return null
        val profile = profileDao.getAllOnce().maxByOrNull { it.id } ?: return null
        val linked = sourceDao.allLinks().filter { it.profileId == profile.id }.mapTo(HashSet()) { it.sourceId }
        val source = sourceDao.getAllOnce().filter { it.id in linked }.maxByOrNull { it.id }
        val finished = source != null &&
            (source.lastSyncAt != null || settings.onboardingImportedSourceId.first() == source.id)
        return InterruptedSetup(profile, source, finished)
    }

    /**
     * Continue [setup] with its own profile and playlist (credentials and catalog kept, nothing added twice).
     * A finished import goes straight to the guide question (unless the guide was already chosen); an
     * unfinished one imports the same playlist again from a clean slate.
     */
    fun resumeInterruptedSetup(setup: InterruptedSetup): ResumePoint {
        createdProfileId = setup.profile.id
        createdProfileName = setup.profile.name
        resumedLockedProfile = setup.profile.pinHash != null
        val source = setup.source ?: return ResumePoint.ADD_PLAYLIST
        if (setup.importFinished) resumeAtGuideQuestion(source) else resumeImport(source)
        return ResumePoint.IMPORT
    }

    private fun resumeAtGuideQuestion(source: SourceEntity) {
        importJob?.cancel()
        startImportOf(ImportKind.NEW_PLAYLIST)
        dismissPendingEpg()
        _state.value = ImportState.Running
        val job = viewModelScope.launch {
            val counts = runCatching { importFinalizer.contentCounts(source.id) }.getOrNull()
            _state.value = ImportState.Success(counts = counts)
            val url = epgRepository.guideUrl(source)
            // Already accepted before the interruption: the feed is registered and the automatic refresh
            // fetches it (it was never synced, so it is due). Asking again would only duplicate it.
            val alreadyChosen = url != null && runCatching { epgSourceStore.getAll().any { it.url == url } }.getOrDefault(false)
            if (url != null && !alreadyChosen) {
                pendingEpgSource = source
                _epgSync.value = tv.own.owntv.features.settings.EpgSyncUi.Ask(source.name)
            }
        }
        importJob = job
        job.invokeOnCompletion { if (importJob == job) importJob = null }
    }

    private fun resumeImport(source: SourceEntity) {
        val enabled = SyncContentTypes(live = source.syncLive, movies = source.syncMovies, series = source.syncSeries)
        // Same as a new playlist's default: live channels now, films and series in the background.
        val priority = if (enabled.live) SyncContentTypes(live = true, movies = false, series = false) else enabled
        val local = source.type == tv.own.owntv.core.model.SourceType.M3U && source.url.isLocalPlaylistPath()
        runImport(autoRefresh = null, contentTypes = priority, enabledScope = enabled, enqueueRemainder = true, requiresNetwork = !local) {
            // The killed import left partial rows, and a never-synced playlist imports through insertFresh,
            // which assumes empty tables — clear them first or every row would be there twice.
            catalogSyncScheduler.cancelSync(source.id)
            sourceRepository.clearSourceContent(source.id)
            sourceDao.getById(source.id) ?: source
        }
    }

    private suspend fun cleanupFailedAdd(source: SourceEntity?) {
        if (source == null) return
        withContext(NonCancellable) {
            catalogSyncScheduler.cancelSync(source.id)
            if (backgroundHandoff) {
                // The user already entered the app with "Run in background" — deleting the source
                // would make the playlist they just added silently vanish. Keep it so they can
                // re-sync from Settings → Playlists; wipe only the partial content (a never-synced
                // source re-syncs via insertFresh, which assumes empty tables — leftovers duplicate).
                runCatching { sourceRepository.clearSourceContent(source.id) }
            } else {
                runCatching { sourceRepository.deleteSource(source) }
                runCatching { settings.setPlaylistAutoRefresh(source.id, PlaylistAutoRefresh.OFF) }
            }
        }
    }

    private companion object {
        /** Sentinel sourceId for the pre-save Stalker handshake (same as SettingsViewModel's). */
        const val STALKER_TEST_SOURCE_ID = -1L
    }
}

/**
 * Whether the onboarding import screen may offer "Run in background" while it imports.
 *
 * Never for a new playlist: its TV Guide question is asked once the playlist has imported, and leaving
 * before that skipped the question for good, so the guide stayed empty. After the question the choice
 * is the guide dialog's ("Run in background" while the guide downloads) or the import is already done
 * ("Not now" → Continue). Re-syncing playlists a new profile links to asks no guide question, so it may.
 */
internal fun importBackgroundAllowed(kind: SetupViewModel.ImportKind?): Boolean =
    kind == SetupViewModel.ImportKind.EXISTING_PLAYLISTS
