package tv.own.owntv.core.sync.work

import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ReminderEntity

/**
 * The one way screens set, remove and read programme reminders: the bottom Live TV guide, the full
 * Guide's programme dialog, Live TV's programme details and Settings → Upcoming reminders. It writes the
 * same `reminders` rows and the same [ReminderScheduler] WorkManager jobs as 4.5.0, so the 60-second
 * warning, [ReminderRelinker]'s refresh re-linking and the automatic tune at the start are unchanged.
 *
 * Ownership: a reminder belongs to the profile that set it ([ReminderEntity.profileId]) and to its
 * channel's playlist ([ReminderEntity.sourceId]). A profile can only set one on a playlist linked to it,
 * only sees and removes its own, and only its own reminders may tune ([tuneChannelId]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramReminders(
    private val reminderDao: ReminderDao,
    private val channelDao: ChannelDao,
    private val sourceDao: SourceDao,
    private val relinker: ReminderRelinker,
    private val jobs: ReminderJobs,
    private val activeProfileId: Flow<Long>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One reminder slot: the unique (channel, programme start) the table is keyed on. */
    data class Key(val channelId: Long, val programStartMs: Long)

    enum class Status { NONE, SET, OTHER_PROFILE }

    enum class SetResult { SET, ALREADY_SET, OTHER_PROFILE, NOT_IN_PROFILE, CHANNEL_MISSING, ENDED }

    enum class RemoveResult { REMOVED, NOT_SET, OTHER_PROFILE }

    /** Reminder slots of the active profile — drives the bells, and updates as soon as one changes. */
    fun observeScheduled(): Flow<Set<Key>> =
        observeForActiveProfile().map { list -> list.mapTo(HashSet()) { Key(it.channelId, it.programStartMs) } }

    /** The active profile's reminders, soonest first (Settings → Upcoming reminders). */
    fun observeUpcoming(): Flow<List<ReminderEntity>> = observeForActiveProfile()

    private fun observeForActiveProfile(): Flow<List<ReminderEntity>> =
        activeProfileId.flatMapLatest { pid -> reminderDao.observeForProfile(pid) }

    suspend fun status(channel: ChannelEntity, programStartMs: Long): Status {
        val row = reminderDao.getFor(channel.id, programStartMs) ?: return Status.NONE
        return if (row.profileId == activeProfileId.first()) Status.SET else Status.OTHER_PROFILE
    }

    /**
     * Sets a reminder for the programme on [channel]. A programme that already ended is refused;
     * one airing now is accepted (as 4.5.0 did): its warning and tune run straight away.
     */
    suspend fun set(channel: ChannelEntity, title: String, startMs: Long, stopMs: Long): SetResult {
        if (stopMs <= now()) return SetResult.ENDED
        // A screen can hold a channel row from before a playlist refresh replaced it; a reminder on that
        // id could never tune (ids are never reused), so refuse it rather than save a dead one.
        val current = channelDao.getById(channel.id)
        if (current == null || current.sourceId != channel.sourceId) return SetResult.CHANNEL_MISSING
        val pid = activeProfileId.first()
        if (channel.sourceId !in sourceDao.sourceIdsForProfile(pid)) return SetResult.NOT_IN_PROFILE
        reminderDao.getFor(channel.id, startMs)?.let { existing ->
            return if (existing.profileId == pid) SetResult.ALREADY_SET else SetResult.OTHER_PROFILE
        }
        val reminder = ReminderEntity(
            profileId = pid,
            sourceId = channel.sourceId,
            channelId = channel.id,
            channelName = channel.name,
            channelNumber = channel.number,
            programId = null,
            programTitle = title,
            programStartMs = startMs,
            programEndMs = stopMs,
            triggerAtMs = startMs - WARNING_LEAD_MS,
            createdAtMs = now(),
        )
        val id = reminderDao.insert(reminder)
        if (id <= 0L) {
            // Lost a race with another set of the same slot (unique index, IGNORE): report who holds it.
            val holder = reminderDao.getFor(channel.id, startMs)
            return if (holder == null || holder.profileId == pid) SetResult.ALREADY_SET else SetResult.OTHER_PROFILE
        }
        jobs.schedule(reminder.copy(id = id))
        return SetResult.SET
    }

    /** Removes the active profile's reminder for the programme, and its pending warning and tune jobs. */
    suspend fun remove(channel: ChannelEntity, programStartMs: Long): RemoveResult {
        val row = reminderDao.getFor(channel.id, programStartMs) ?: return RemoveResult.NOT_SET
        return delete(row)
    }

    /** Settings → Upcoming reminders: cancel one by id. */
    suspend fun cancel(reminderId: Long): RemoveResult {
        val row = reminderDao.getById(reminderId) ?: return RemoveResult.NOT_SET
        return delete(row)
    }

    private suspend fun delete(row: ReminderEntity): RemoveResult {
        if (row.profileId != activeProfileId.first()) return RemoveResult.OTHER_PROFILE
        reminderDao.deleteById(row.id)
        jobs.cancel(row.channelId, row.programStartMs)
        return RemoveResult.REMOVED
    }

    /** Whether the reminder banner should be shown for [reminder] to the profile now on screen. */
    suspend fun belongsToActiveProfile(reminder: ReminderEntity): Boolean {
        val stored = reminderDao.getById(reminder.id) ?: reminder
        return stored.profileId == activeProfileId.first()
    }

    /**
     * The channel a firing reminder may tune, or null. On top of [ReminderRelinker.safeTuneChannelId]
     * (the channel must still exist and be in the reminder's own playlist), the reminder must belong to
     * the profile on screen and that profile must still have the playlist.
     */
    suspend fun tuneChannelId(reminder: ReminderEntity): Long? {
        val stored = reminderDao.getById(reminder.id) ?: reminder
        val pid = activeProfileId.first()
        if (stored.profileId != pid || stored.sourceId !in sourceDao.sourceIdsForProfile(pid)) {
            Log.i(TAG, "reminder tune skipped id=${stored.id} sourceId=${stored.sourceId} reason=other profile")
            return null
        }
        return relinker.safeTuneChannelId(stored)
    }

    /**
     * 4.5.0 saved every reminder as profile 1. Where the reminder's playlist is linked to exactly one
     * profile and that isn't the stored one, that profile set it: give it the reminder. A playlist
     * shared by several profiles leaves the stored owner alone (no guessing).
     */
    suspend fun repairLegacyOwners() {
        for (r in reminderDao.getAll()) {
            val owners = sourceDao.profileIdsForSource(r.sourceId)
            if (r.profileId !in owners && owners.size == 1) {
                reminderDao.updateProfileId(r.id, owners.single())
                Log.i(TAG, "reminder owner repaired id=${r.id} sourceId=${r.sourceId}")
            }
        }
    }

    private companion object {
        const val TAG = "PROGRAM_REMINDERS"

        /** The warning notice comes one minute before the start (4.5.0 behaviour). */
        const val WARNING_LEAD_MS = 60_000L
    }
}
