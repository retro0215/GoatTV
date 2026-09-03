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
}
