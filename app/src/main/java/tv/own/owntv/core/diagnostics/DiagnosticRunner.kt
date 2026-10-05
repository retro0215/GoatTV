package tv.own.owntv.core.diagnostics

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

/** One read-only probe per fact. The Android implementation is [AndroidDiagnosticProbes]; tests fake it. */
interface DiagnosticProbes {
    suspend fun internet(): InternetFact
    suspend fun service(): ServiceFact
    suspend fun playlist(): PlaylistFact
    suspend fun epg(): EpgFact
    suspend fun sports(): SportsFact
    suspend fun player(): PlayerFact
    suspend fun device(): DeviceFact
    suspend fun update(): UpdateFact
}

/** Where a running diagnostic is: the facts so far and the check being worked on (null = done). */
data class DiagnosticProgress(val facts: DiagnosticFacts, val current: DiagCheck?, val errored: Set<DiagCheck>)

/**
 * Runs a full diagnostic: the probes one after another (bounded, so a box on a weak connection is
 * never hit with parallel requests), each under its own timeout. Cancelling the calling coroutine —
 * the user pressing Back — stops at the next suspension point; nothing is left running.
 *
 * A probe that throws or times out marks only its own check FAILED ([DiagnosticProgress.errored]) and
 * the run continues, so one broken subsystem never hides the rest of the picture.
 */
class DiagnosticRunner(
    private val probes: DiagnosticProbes,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    private val updateTimeoutMs: Long = UPDATE_TIMEOUT_MS,
) {
    suspend fun run(app: AppFact, onProgress: (DiagnosticProgress) -> Unit): DiagnosticProgress {
        var facts = DiagnosticFacts(app = app)
        val errored = mutableSetOf<DiagCheck>()

        suspend fun <T : Any> step(check: DiagCheck, timeoutMs: Long, probe: suspend () -> T, apply: (T) -> DiagnosticFacts) {
            onProgress(DiagnosticProgress(facts, check, errored.toSet()))
            val value = try {
                withTimeout(timeoutMs) { probe() }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (value == null) errored += check else facts = apply(value)
        }

        step(DiagCheck.INTERNET, probeTimeoutMs, probes::internet) { facts.copy(internet = it) }
        step(DiagCheck.SERVICE, probeTimeoutMs, probes::service) { facts.copy(service = it) }
        step(DiagCheck.PLAYLIST, probeTimeoutMs, probes::playlist) { facts.copy(playlist = it) }
        step(DiagCheck.EPG, probeTimeoutMs, probes::epg) { facts.copy(epg = it) }
        step(DiagCheck.SPORTS, probeTimeoutMs, probes::sports) { facts.copy(sports = it) }
        step(DiagCheck.PLAYER, probeTimeoutMs, probes::player) { facts.copy(player = it) }
        step(DiagCheck.DEVICE, probeTimeoutMs, probes::device) { facts.copy(device = it) }
        step(DiagCheck.APP_VERSION, updateTimeoutMs, probes::update) { facts.copy(update = it) }

        return DiagnosticProgress(facts, null, errored.toSet()).also(onProgress)
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 20_000L
        const val UPDATE_TIMEOUT_MS = 30_000L

        /** Results for a progress snapshot: errored checks FAIL, the running one shows CHECKING. */
        fun results(p: DiagnosticProgress): List<CheckResult> = DiagnosticEvaluator.results(p.facts).map { r ->
            when {
                r.check in p.errored -> r.copy(status = DiagStatus.FAILED)
                p.current != null && r.check in checking(p.current) -> r.copy(status = DiagStatus.CHECKING)
                else -> r
            }
        }

        /** Live TV and Movies & Series are derived from the playlist step, so they spin with it. */
        private fun checking(current: DiagCheck): Set<DiagCheck> = when (current) {
            DiagCheck.PLAYLIST -> setOf(DiagCheck.PLAYLIST, DiagCheck.LIVE_TV, DiagCheck.VOD)
            else -> setOf(current)
        }
    }
}
