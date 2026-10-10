package tv.own.owntv.core.diagnostics

/**
 * Pure rules: facts → per-check status, recommendations and the summary line. No Android, no I/O, so
 * every combination is unit-tested.
 *
 * Wording discipline lives here too, as status choices: a check only PASSes for what was actually
 * verified. Live TV and Movies & Series pass on "the library is there and the service answers" —
 * playback itself is never started by Phase 1, and the screen says so. Rows that do not apply (no
 * playlist yet, a section switched off for the playlist, an update already downloading) are
 * NOT_CHECKED rather than failures, so one missing playlist doesn't read as five separate problems.
 */
object DiagnosticEvaluator {

    /** Above this best-case round trip the connection itself is treated as weak (no VPN suggestion). */
    const val SLOW_LATENCY_MS: Long = 250

    /** Below this much free storage the device check warns (updates, EPG and artwork need room). */
    const val LOW_STORAGE_BYTES: Long = 500L * 1024 * 1024

    fun status(check: DiagCheck, f: DiagnosticFacts): DiagStatus = when (check) {
        DiagCheck.INTERNET -> f.internet?.let { if (it.reachable) DiagStatus.PASS else DiagStatus.FAILED } ?: DiagStatus.NOT_CHECKED
        DiagCheck.SERVICE -> when (f.service) {
            null -> DiagStatus.NOT_CHECKED
            is ServiceFact.Ok -> DiagStatus.PASS
            ServiceFact.NoSource, is ServiceFact.Rejected, is ServiceFact.Unreachable -> DiagStatus.FAILED
        }
        DiagCheck.PLAYLIST -> f.playlist?.let { p ->
            if (!p.hasSource || p.total == 0) DiagStatus.FAILED else DiagStatus.PASS
        } ?: DiagStatus.NOT_CHECKED
        DiagCheck.EPG -> f.epg?.let { e ->
            if (e.sourceCount == 0 || e.programmes == 0 || e.lastSyncFailed) DiagStatus.WARNING else DiagStatus.PASS
        } ?: DiagStatus.NOT_CHECKED
        DiagCheck.LIVE_TV -> liveStatus(f)
        DiagCheck.VOD -> vodStatus(f)
        DiagCheck.SPORTS -> when (val s = f.sports) {
            null -> DiagStatus.NOT_CHECKED
            is SportsFact.Connected -> if (s.events > 0) DiagStatus.PASS else DiagStatus.WARNING
            SportsFact.Unavailable -> DiagStatus.FAILED
        }
        DiagCheck.PLAYER -> if (f.player != null) DiagStatus.PASS else DiagStatus.NOT_CHECKED
        DiagCheck.APP_VERSION -> when (f.update) {
            // GitHub unreachable says nothing about the app itself: not verified, not a warning.
            null, UpdateFact.Busy, UpdateFact.CheckFailed -> DiagStatus.NOT_CHECKED
            UpdateFact.UpToDate -> DiagStatus.PASS
            // A confirmed newer release is always surfaced.
            is UpdateFact.Available -> DiagStatus.WARNING
        }
        DiagCheck.DEVICE -> f.device?.let { if (isLowStorage(it)) DiagStatus.WARNING else DiagStatus.PASS } ?: DiagStatus.NOT_CHECKED
    }

    private fun liveStatus(f: DiagnosticFacts): DiagStatus {
        val p = f.playlist ?: return DiagStatus.NOT_CHECKED
        if (!p.hasSource || !p.liveEnabled) return DiagStatus.NOT_CHECKED
        if (p.channels == 0) return DiagStatus.FAILED
        return when (f.service) {
            null -> DiagStatus.NOT_CHECKED
            is ServiceFact.Ok -> DiagStatus.PASS
            else -> DiagStatus.WARNING
        }
    }

    private fun vodStatus(f: DiagnosticFacts): DiagStatus {
        val p = f.playlist ?: return DiagStatus.NOT_CHECKED
        if (!p.hasSource || !(p.moviesEnabled || p.seriesEnabled)) return DiagStatus.NOT_CHECKED
        return if (p.movies + p.series > 0) DiagStatus.PASS else DiagStatus.WARNING
    }

    fun isLowStorage(d: DeviceFact): Boolean = d.storageTotalBytes > 0 && d.storageFreeBytes < LOW_STORAGE_BYTES

    fun results(f: DiagnosticFacts): List<CheckResult> = DiagCheck.entries.map { CheckResult(it, status(it, f)) }

    /** Issues = WARNING + FAILED rows; informational and not-applicable rows never count. */
    fun summary(results: List<CheckResult>, finished: Boolean): DiagnosticSummary =
        DiagnosticSummary(
            issues = results.count { it.status == DiagStatus.WARNING || it.status == DiagStatus.FAILED },
            complete = finished,
        )

    /**
     * Plain next steps, most fundamental first. Deliberately conservative: an unreachable service with
     * working internet suggests a router restart and *mentions* that a VPN may help — it never states
     * that the ISP is blocking anything, because Phase 1 cannot know that.
     */
    fun recommendations(f: DiagnosticFacts): List<Recommendation> = buildList {
        val internetOk = f.internet?.reachable
        if (internetOk == false) add(Recommendation.RESTART_ROUTER)
        // A user-run speed test that measured a slow line is the first thing to address — before any
        // service-specific advice, and it rules out the VPN suggestion (see vpnSuggested).
        if (f.speed?.rating == SpeedRating.POOR) add(Recommendation.SLOW_CONNECTION)
        when (f.service) {
            ServiceFact.NoSource -> add(Recommendation.ADD_PLAYLIST)
            is ServiceFact.Rejected -> add(Recommendation.CHECK_ACCOUNT)
            is ServiceFact.Unreachable -> when {
                vpnSuggested(f) -> add(Recommendation.SERVICE_UNREACHABLE_VPN)
                internetOk == true -> add(Recommendation.RESTART_ROUTER)
                else -> Unit // internet down: RESTART_ROUTER was added above
            }
            else -> Unit
        }
        val p = f.playlist
        if (p != null && p.hasSource && p.total == 0) add(Recommendation.RESYNC_PLAYLIST)
        val e = f.epg
        if (e != null && p?.hasSource == true) {
            if (e.sourceCount == 0) add(Recommendation.ADD_EPG)
            else if (e.programmes == 0 || e.lastSyncFailed) add(Recommendation.RESYNC_EPG)
        }
        if (f.sports == SportsFact.Unavailable && internetOk == true) add(Recommendation.SPORTS_LATER)
        if (f.update is UpdateFact.Available) add(Recommendation.UPDATE_APP)
        if (f.device?.let(::isLowStorage) == true) add(Recommendation.FREE_STORAGE)
    }

    /**
     * Whether testing with a VPN could reasonably help isolate a network-path problem. Conservative on
     * purpose — it never claims the ISP is at fault, and needs ALL of:
     *  - the internet check passed, with a best-case latency that is not slow;
     *  - if the user ran a speed test, the result was not Poor (a slow line is its own explanation);
     *  - the streaming service was unreachable twice in a row (not a refusal, not a one-off blip).
     * Anything else — healthy service, internet down, slow connection, a single failure — gets no VPN
     * guidance at all.
     */
    fun vpnSuggested(f: DiagnosticFacts): Boolean {
        val net = f.internet ?: return false
        if (!net.reachable) return false
        if (net.latencyMs != null && net.latencyMs > SLOW_LATENCY_MS) return false
        if (f.speed?.rating == SpeedRating.POOR) return false
        val service = f.service as? ServiceFact.Unreachable ?: return false
        return service.repeated
    }

    /** The engine the "Switch video player" action moves to, keeping automatic fallback on. */
    fun switchTarget(current: VodEngine): VodEngine = if (current == VodEngine.MPV) VodEngine.EXO else VodEngine.MPV
}
