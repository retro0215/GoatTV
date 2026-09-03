package tv.own.owntv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["channelId", "programStartMs"], unique = true),
        Index(value = ["profileId"]),
        Index(value = ["triggerAtMs"]),
    ]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val profileId: Long,
    val sourceId: Long,
    val channelId: Long,
    val channelName: String,
    val channelNumber: Int?,
    val programId: String?,
    val programTitle: String,
    val programStartMs: Long,
    val programEndMs: Long,
    val triggerAtMs: Long,
    val createdAtMs: Long,
)
