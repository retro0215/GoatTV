package tv.own.owntv.core.sync.work

import android.util.Log
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.M3uSyncer

/**
 * Keeps reminders attached to their channel across a playlist refresh. Channel rows are rewritten on
 * every sync (clear-then-insert / REPLACE upserts), so a reminder's `channelId` goes stale and its
 * auto-tune silently found nothing. Ids are AUTOINCREMENT and never reused, so a stale id can only
 * miss, never land on another channel — this class re-links it, and refuses whenever it can't be sure.
 *
 * Usage: [snapshot] before the sync (old ids still valid), [relink] after it. A reminder is moved only
 * when its channel's provider id resolves to exactly one channel of the same playlist:
 *  - Xtream / Stalker: the provider's stream id. Display names are never used.
 *  - M3U: the synthesized `name␁group` key, only when no other entry in the group shares the name
 *    (before or after the refresh); duplicates carry an `␁#n` ordinal that can shift between refreshes.
 * Anything else leaves the reminder untouched: it still notifies, and [safeTuneChannelId] keeps it from
 * tuning anything.
 */
class ReminderRelinker(
    private val reminderDao: ReminderDao,
    private val channelDao: ChannelDao,
    private val jobs: ReminderJobs,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A reminder's channel identity, captured before the refresh. [providerKey] null = no safe key. */
    data class Snapshot(
        val reminderId: Long,
        val oldChannelId: Long,
        val programStartMs: Long,
        val providerKey: String?,
        val unsafeReason: String? = null,
    )

    enum class Outcome { UNCHANGED, RELINKED, MISSING, AMBIGUOUS, NO_KEY, CONFLICT, GONE }

    /** Upcoming reminders of [sourceId] (still inside the worker's 30-minute window) and their keys. */
    suspend fun snapshot(sourceId: Long, sourceType: SourceType): List<Snapshot> =
        reminderDao.getForSourceStartingAfter(sourceId, now() - LATE_WINDOW_MS).map { r ->
            val channel = channelDao.getById(r.channelId)
            val key = channel?.remoteId?.takeIf { channel.sourceId == sourceId && it.isNotEmpty() }
            val reason = when {
                key == null -> if (channel == null) "channel already missing" else "no provider id"
                sourceType == SourceType.M3U && !isUniqueM3uKey(sourceId, key) -> "duplicate M3U name in group"
                sourceType != SourceType.M3U && sourceType != SourceType.XTREAM && sourceType != SourceType.STALKER ->
                    "unsupported source type"
                else -> null
            }
            Snapshot(r.id, r.channelId, r.programStartMs, key.takeIf { reason == null }, reason)
        }

    /** Re-attaches each [snapshots] reminder to its channel's new row; returns the outcome per reminder id. */
    suspend fun relink(sourceId: Long, sourceType: SourceType, snapshots: List<Snapshot>): Map<Long, Outcome> {
        val outcomes = LinkedHashMap<Long, Outcome>()
        for (snap in snapshots) {
            val outcome = runCatching { relinkOne(sourceId, sourceType, snap) }
                .onFailure { Log.w(TAG, "reminder relink failed id=${snap.reminderId} sourceId=$sourceId", it) }
                .getOrDefault(Outcome.MISSING)
            outcomes[snap.reminderId] = outcome
            if (outcome != Outcome.UNCHANGED) {
                Log.i(TAG, "reminder relink id=${snap.reminderId} sourceId=$sourceId outcome=$outcome" +
                    (snap.unsafeReason?.let { " reason=$it" } ?: ""))
            }
        }
        return outcomes
    }

    private suspend fun relinkOne(sourceId: Long, sourceType: SourceType, snap: Snapshot): Outcome {
        // Cancelled, fired or changed while the sync ran: nothing of ours to move.
        val reminder = reminderDao.getById(snap.reminderId) ?: return Outcome.GONE
        if (reminder.channelId != snap.oldChannelId || reminder.programStartMs != snap.programStartMs) return Outcome.GONE
        val key = snap.providerKey ?: return Outcome.NO_KEY

        val current = channelDao.getById(reminder.channelId)
        if (current != null && current.sourceId == sourceId && current.remoteId == key) return Outcome.UNCHANGED

        val matches = channelDao.findAllByRemote(sourceId, key)
        if (matches.isEmpty()) return Outcome.MISSING
        if (matches.size > 1) return Outcome.AMBIGUOUS
        if (sourceType == SourceType.M3U && !isUniqueM3uKey(sourceId, key)) return Outcome.AMBIGUOUS
        val target = matches.single()

        // The same programme already has a reminder on the new row (set again after the refresh):
        // the unique (channelId, programStartMs) index forbids a second one, and that one already tunes.
        if (reminderDao.exists(target.id, reminder.programStartMs)) return Outcome.CONFLICT

        reminderDao.updateChannelId(reminder.id, target.id)
        // Re-enqueue under the new channel's work names so a later cancel finds them. Once the start
        // has passed the pending jobs are left alone: ReminderWorker looks the row up by reminder id,
        // so it reads the new channel, and re-enqueueing would only repeat the 60-second notice.
        if (reminder.programStartMs > now()) {
            jobs.cancel(reminder.channelId, reminder.programStartMs)
            jobs.schedule(reminder.copy(channelId = target.id))
        }
        return Outcome.RELINKED
    }

    /** A first-occurrence M3U key with no `␁#2` sibling: the name is unique within its group. */
    private suspend fun isUniqueM3uKey(sourceId: Long, key: String): Boolean =
        !key.contains(M3U_ORDINAL_MARK) &&
            channelDao.findAllByRemote(sourceId, key + M3U_ORDINAL_MARK + "2").isEmpty()

    /**
     * The channel a reminder may tune, or null. Reads the stored row first (an event or banner may hold
     * a copy from before a refresh re-linked it), then requires the channel to belong to the reminder's
     * own playlist, so an orphaned reminder can never switch to an unrelated channel.
     */
    suspend fun safeTuneChannelId(event: ReminderEntity): Long? {
        val reminder = reminderDao.getById(event.id) ?: event
        val channel: ChannelEntity = channelDao.getById(reminder.channelId) ?: return null.also {
            Log.i(TAG, "reminder tune skipped id=${reminder.id} sourceId=${reminder.sourceId} reason=channel missing")
        }
        if (channel.sourceId != reminder.sourceId) {
            Log.i(TAG, "reminder tune skipped id=${reminder.id} sourceId=${reminder.sourceId} reason=other playlist")
            return null
        }
        return channel.id
    }

    /** App start: drop reminders whose programme already started, re-enqueue the rest (WorkManager REPLACE). */
    suspend fun restoreAfterRestart() {
        val now = now()
        for (reminder in reminderDao.getAll()) {
            if (reminder.programStartMs <= now) {
                reminderDao.delete(reminder.channelId, reminder.programStartMs)
            } else {
                jobs.schedule(reminder)
            }
        }
    }

    private companion object {
        const val TAG = "REMINDER_RELINK"

        /** ReminderWorker still acts up to 30 minutes after the start; keep those reminders linked too. */
        const val LATE_WINDOW_MS = 30 * 60 * 1000L

        const val M3U_ORDINAL_MARK = M3uSyncer.KEY_SEPARATOR + "#"
    }
}
