package tv.own.owntv.features.settings

import kotlinx.coroutines.CancellationException
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.util.classifySyncFailure

/**
 * Shared semi-auto EPG sync used by both onboarding and Settings → Playlists. Registers the playlist's own
 * guide feed as an [EpgSource][tv.own.owntv.core.epg.EpgSource] in the [store] (re-using an existing entry
 * with the same URL) so it appears in Settings → EPG, then downloads it keyed by that source id while
 * reporting a live programme count through [setState].
 */
suspend fun runSemiAutoEpgSync(
    source: SourceEntity,
    epgRepository: EpgRepository,
    store: EpgSourceStore,
    setState: (EpgSyncUi) -> Unit,
) {
    val url = epgRepository.guideUrl(source) ?: run { setState(EpgSyncUi.Done); return }
    setState(EpgSyncUi.Syncing(0))
    // Show up in Settings → EPG like any other feed (so the user can re-sync / delete it there).
    val epgSource = store.getAll().firstOrNull { it.url == url } ?: store.add(source.name, url, source.userAgent)
    val now = System.currentTimeMillis()
    try {
        epgRepository.refreshUrl(epgSource.id, epgSource.url, epgSource.userAgent) { _, count -> setState(EpgSyncUi.Syncing(count)) }
        store.setSynced(epgSource.id, now, null)
        setState(EpgSyncUi.Done)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        // A failure must not stamp lastSyncAt: the automatic refresh reads it as the last *successful*
        // sync, so stamping it made a failed first guide download look fresh and skipped retries for a day.
        // Interrupted runs (app closed, process killed) leave it unset the same way, so the guide is
        // fetched by the next automatic check.
        store.markError(epgSource.id, e.message)
        setState(EpgSyncUi.Failed(classifySyncFailure(e.message, online = true)))
    }
}
