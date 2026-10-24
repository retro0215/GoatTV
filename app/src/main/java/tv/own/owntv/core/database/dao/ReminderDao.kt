package tv.own.owntv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tv.own.owntv.core.database.entity.ReminderEntity

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE channelId = :channelId AND programStartMs = :programStartMs")
    suspend fun delete(channelId: Long, programStartMs: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM reminders WHERE channelId = :channelId AND programStartMs = :programStartMs)")
    suspend fun exists(channelId: Long, programStartMs: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM reminders WHERE channelId = :channelId AND programStartMs = :programStartMs)")
    fun observeExists(channelId: Long, programStartMs: Long): Flow<Boolean>

    @Query("SELECT * FROM reminders WHERE triggerAtMs <= :currentTimeMs")
    suspend fun getDueReminders(currentTimeMs: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE profileId = :profileId")
    suspend fun getForProfile(profileId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    /** Reminders on [sourceId]'s channels whose start is after [startAfterMs] (playlist-refresh re-link). */
    @Query("SELECT * FROM reminders WHERE sourceId = :sourceId AND programStartMs > :startAfterMs")
    suspend fun getForSourceStartingAfter(sourceId: Long, startAfterMs: Long): List<ReminderEntity>

    /** The reminder for one programme slot (unique on channel + start), whoever set it. */
    @Query("SELECT * FROM reminders WHERE channelId = :channelId AND programStartMs = :programStartMs LIMIT 1")
    suspend fun getFor(channelId: Long, programStartMs: Long): ReminderEntity?

    /** A profile's reminders, soonest first. */
    @Query("SELECT * FROM reminders WHERE profileId = :profileId ORDER BY programStartMs ASC")
    fun observeForProfile(profileId: Long): Flow<List<ReminderEntity>>

    /** Re-owns a reminder 4.5.0 saved under the wrong profile (see ProgramReminders.repairLegacyOwners). */
    @Query("UPDATE reminders SET profileId = :profileId WHERE id = :id")
    suspend fun updateProfileId(id: Long, profileId: Long)

    /** Points a reminder at its channel's new row after a playlist refresh renumbered the channels. */
    @Query("UPDATE reminders SET channelId = :channelId WHERE id = :id")
    suspend fun updateChannelId(id: Long, channelId: Long)
}
