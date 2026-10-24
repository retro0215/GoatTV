package tv.own.owntv.core.sync.work

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.work.ProgramReminders.Key
import tv.own.owntv.core.sync.work.ProgramReminders.RemoveResult
import tv.own.owntv.core.sync.work.ProgramReminders.SetResult
import tv.own.owntv.core.sync.work.ProgramReminders.Status

/**
 * The shared reminder action every screen uses. In-memory tables stand in for Room (including the
 * unique (channelId, programStartMs) index and IGNORE inserts); jobs are tracked by their real
 * WorkManager unique names.
 */
class ProgramRemindersTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val start = now + 2 * hour

    private val channels = mutableListOf<ChannelEntity>()
    private val reminders = MutableStateFlow<List<ReminderEntity>>(emptyList())
    private var nextReminderId = 1L
    private var nextChannelId = 100L

    /** profile → playlists linked to it. Profile 1 and 2 share playlist 30. */
    private val links = mutableMapOf(1L to listOf(10L, 30L), 2L to listOf(20L, 30L))
    private val activeProfile = MutableStateFlow(1L)

    private val enqueued = mutableMapOf<String, Long>()
    private val jobs = object : ReminderJobs {
        override fun schedule(reminder: ReminderEntity) {
            enqueued[ReminderScheduler.warningWorkName(reminder.channelId, reminder.programStartMs)] = reminder.id
            enqueued[ReminderScheduler.autotuneWorkName(reminder.channelId, reminder.programStartMs)] = reminder.id
        }
        override fun cancel(channelId: Long, programStartMs: Long) {
            enqueued.remove(ReminderScheduler.warningWorkName(channelId, programStartMs))
            enqueued.remove(ReminderScheduler.autotuneWorkName(channelId, programStartMs))
        }
    }

    private val channelDao = mockk<ChannelDao>().also { dao ->
        coEvery { dao.getById(any()) } answers { channels.find { it.id == firstArg<Long>() } }
        coEvery { dao.findAllByRemote(any(), any()) } answers {
            channels.filter { it.sourceId == firstArg<Long>() && it.remoteId == secondArg<String>() }
        }
    }

    private val sourceDao = mockk<SourceDao>().also { dao ->
        coEvery { dao.sourceIdsForProfile(any()) } answers { links[firstArg<Long>()].orEmpty() }
        coEvery { dao.profileIdsForSource(any()) } answers {
            links.filterValues { firstArg<Long>() in it }.keys.toList()
        }
    }

    private val reminderDao = mockk<ReminderDao>().also { dao ->
        coEvery { dao.insert(any()) } answers {
            val r = firstArg<ReminderEntity>()
            if (reminders.value.any { it.channelId == r.channelId && it.programStartMs == r.programStartMs }) {
                -1L // unique index + OnConflictStrategy.IGNORE
            } else {
                val id = nextReminderId++
                reminders.value = reminders.value + r.copy(id = id)
                id
            }
        }
        coEvery { dao.getFor(any(), any()) } answers {
            reminders.value.find { it.channelId == firstArg<Long>() && it.programStartMs == secondArg<Long>() }
        }
        coEvery { dao.getById(any()) } answers { reminders.value.find { it.id == firstArg<Long>() } }
        coEvery { dao.getAll() } answers { reminders.value }
        coEvery { dao.deleteById(any()) } answers { reminders.value = reminders.value.filterNot { it.id == firstArg<Long>() } }
        coEvery { dao.delete(any(), any()) } answers {
            reminders.value = reminders.value.filterNot { it.channelId == firstArg<Long>() && it.programStartMs == secondArg<Long>() }
        }
        coEvery { dao.updateProfileId(any(), any()) } answers {
            reminders.value = reminders.value.map { if (it.id == firstArg<Long>()) it.copy(profileId = secondArg()) else it }
        }
        coEvery { dao.updateChannelId(any(), any()) } answers {
            reminders.value = reminders.value.map { if (it.id == firstArg<Long>()) it.copy(channelId = secondArg()) else it }
        }
        coEvery { dao.getForSourceStartingAfter(any(), any()) } answers {
            reminders.value.filter { it.sourceId == firstArg<Long>() && it.programStartMs > secondArg<Long>() }
        }
        coEvery { dao.exists(any(), any()) } answers {
            reminders.value.any { it.channelId == firstArg<Long>() && it.programStartMs == secondArg<Long>() }
        }
        every { dao.observeForProfile(any()) } answers {
            val pid = firstArg<Long>()
            reminders.map { list -> list.filter { it.profileId == pid }.sortedBy { it.programStartMs } }
        }
    }

    private val relinker = ReminderRelinker(reminderDao, channelDao, jobs, now = { now })
    private val actions = ProgramReminders(reminderDao, channelDao, sourceDao, relinker, jobs, activeProfile, now = { now })

    private fun channel(sourceId: Long, remoteId: String = "${nextChannelId}", name: String = "Ch$remoteId") =
        ChannelEntity(id = nextChannelId++, sourceId = sourceId, name = name, streamUrl = "s", remoteId = remoteId, number = 7)
            .also { channels += it }

    private fun jobsFor(channel: ChannelEntity, startMs: Long = start) = setOf(
        ReminderScheduler.warningWorkName(channel.id, startMs),
        ReminderScheduler.autotuneWorkName(channel.id, startMs),
    )

    // ---- Set / remove / status -------------------------------------------------------------------

    @Test fun `set saves the 4_5_0 row and schedules the warning and tune jobs`() = runBlocking {
        val news = channel(10, name = "News")
        assertEquals(SetResult.SET, actions.set(news, "Evening News", start, start + hour))

        val r = reminders.value.single()
        assertEquals(1L, r.profileId)
        assertEquals(10L, r.sourceId)
        assertEquals(news.id, r.channelId)
        assertEquals("News", r.channelName)
        assertEquals(7, r.channelNumber)
        assertEquals("Evening News", r.programTitle)
        assertEquals(start, r.programStartMs)
        assertEquals(start + hour, r.programEndMs)
        assertEquals(start - 60_000L, r.triggerAtMs)
        assertEquals(jobsFor(news), enqueued.keys)
        assertTrue(enqueued.values.all { it == r.id })
    }

    @Test fun `remove deletes the row and cancels both jobs`() = runBlocking {
        val news = channel(10)
        actions.set(news, "News", start, start + hour)
        assertEquals(RemoveResult.REMOVED, actions.remove(news, start))
        assertTrue(reminders.value.isEmpty())
        assertTrue(enqueued.isEmpty())
        assertEquals(RemoveResult.NOT_SET, actions.remove(news, start))
    }

    @Test fun `status and the bell set agree across screens and update on change`() = runBlocking {
        val news = channel(10)
        assertEquals(Status.NONE, actions.status(news, start))
        assertEquals(emptySet<Key>(), actions.observeScheduled().first())

        actions.set(news, "News", start, start + hour) // e.g. from the bottom guide
        assertEquals(Status.SET, actions.status(news, start)) // e.g. read by the full guide
        assertEquals(setOf(Key(news.id, start)), actions.observeScheduled().first())

        actions.remove(news, start) // e.g. from the full guide
        assertEquals(Status.NONE, actions.status(news, start))
        assertEquals(emptySet<Key>(), actions.observeScheduled().first())
    }

    @Test fun `a programme that ended cannot take a reminder`() = runBlocking {
        val news = channel(10)
        assertEquals(SetResult.ENDED, actions.set(news, "Old", now - 2 * hour, now - hour))
        assertTrue(reminders.value.isEmpty())
    }

    @Test fun `a programme on air can still take one, as in 4_5_0`() = runBlocking {
        val news = channel(10)
        assertEquals(SetResult.SET, actions.set(news, "Live now", now - 10 * 60_000, now + hour))
    }

    // ---- Duplicates ----------------------------------------------------------------------------------

    @Test fun `setting the same programme twice keeps one reminder and one pair of jobs`() = runBlocking {
        val news = channel(10)
        assertEquals(SetResult.SET, actions.set(news, "News", start, start + hour))
        assertEquals(SetResult.ALREADY_SET, actions.set(news, "News", start, start + hour))
        assertEquals(1, reminders.value.size)
        assertEquals(2, enqueued.size)
    }

    @Test fun `losing an insert race schedules nothing`() = runBlocking {
        val news = channel(10)
        // Another caller inserts between our check and our insert.
        coEvery { reminderDao.getFor(news.id, start) } returnsMany listOf(null, null)
        reminders.value = listOf(row(id = 99, channel = news, profileId = 1))
        enqueued.clear()
        assertEquals(SetResult.ALREADY_SET, actions.set(news, "News", start, start + hour))
        assertEquals(1, reminders.value.size)
        assertTrue(enqueued.isEmpty())
    }

    // ---- Profile isolation ---------------------------------------------------------------------------

    @Test fun `another profile neither sees, removes nor duplicates a reminder on a shared playlist`() = runBlocking {
        val shared = channel(30)
        actions.set(shared, "Match", start, start + hour) // profile 1

        activeProfile.value = 2L
        assertEquals(Status.OTHER_PROFILE, actions.status(shared, start))
        assertEquals(emptySet<Key>(), actions.observeScheduled().first())
        assertTrue(actions.observeUpcoming().first().isEmpty())
        assertEquals(SetResult.OTHER_PROFILE, actions.set(shared, "Match", start, start + hour))
        assertEquals(RemoveResult.OTHER_PROFILE, actions.remove(shared, start))
        assertEquals(RemoveResult.OTHER_PROFILE, actions.cancel(reminders.value.single().id))
        assertEquals(1, reminders.value.size)
        assertEquals(jobsFor(shared), enqueued.keys)
    }

    @Test fun `another profile's reminder never tunes and shows no banner`() = runBlocking {
        val shared = channel(30)
        actions.set(shared, "Match", start, start + hour)
        val r = reminders.value.single()

        activeProfile.value = 2L
        assertNull(actions.tuneChannelId(r))
        assertEquals(false, actions.belongsToActiveProfile(r))

        activeProfile.value = 1L
        assertEquals(shared.id, actions.tuneChannelId(r))
        assertEquals(true, actions.belongsToActiveProfile(r))
    }

    // ---- Playlist isolation --------------------------------------------------------------------------

    @Test fun `a profile cannot set a reminder on a playlist it does not have`() = runBlocking {
        val profile2Only = channel(20)
        assertEquals(SetResult.NOT_IN_PROFILE, actions.set(profile2Only, "X", start, start + hour))
        assertTrue(reminders.value.isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `a reminder whose playlist was unlinked from the profile does not tune`() = runBlocking {
        val news = channel(10)
        actions.set(news, "News", start, start + hour)
        links[1L] = listOf(30L)
        assertNull(actions.tuneChannelId(reminders.value.single()))
    }

    @Test fun `a reminder pointing at another playlist's channel does not tune`() = runBlocking {
        val news = channel(10)
        val other = channel(30)
        actions.set(news, "News", start, start + hour)
        // Corrupt link: the row's channel now belongs to a different playlist.
        reminders.value = reminders.value.map { it.copy(channelId = other.id) }
        assertNull(actions.tuneChannelId(reminders.value.single()))
    }

    // ---- Missing / ambiguous channels ------------------------------------------------------------------

    @Test fun `a channel row replaced by a refresh cannot take a reminder`() = runBlocking {
        val stale = channel(10)
        channels.clear() // refresh replaced every row
        assertEquals(SetResult.CHANNEL_MISSING, actions.set(stale, "News", start, start + hour))
        assertTrue(reminders.value.isEmpty())
    }

    @Test fun `a missing channel tunes nothing`() = runBlocking {
        val news = channel(10)
        actions.set(news, "News", start, start + hour)
        channels.clear()
        assertNull(actions.tuneChannelId(reminders.value.single()))
    }

    // ---- Re-linking and automatic tuning ---------------------------------------------------------------

    @Test fun `a reminder set through the shared action is re-linked by a refresh and then tunes the new row`() = runBlocking {
        val espn = channel(10, remoteId = "501")
        actions.set(espn, "Match", start, start + hour)
        val snapshot = relinker.snapshot(10, SourceType.XTREAM)
        channels.clear()
        val newEspn = channel(10, remoteId = "501")
        relinker.relink(10, SourceType.XTREAM, snapshot)

        val r = reminders.value.single()
        assertEquals(newEspn.id, r.channelId)
        assertEquals(jobsFor(newEspn), enqueued.keys)
        // The worker emits the row as stored at fire time; it tunes the re-linked channel.
        assertEquals(newEspn.id, actions.tuneChannelId(r))
        // An event carrying the pre-refresh copy still resolves through the stored row.
        assertEquals(newEspn.id, actions.tuneChannelId(r.copy(channelId = espn.id)))
    }

    @Test fun `cancel from Upcoming reminders removes the row and its jobs`() = runBlocking {
        val news = channel(10)
        actions.set(news, "News", start, start + hour)
        val upcoming = actions.observeUpcoming().first()
        assertEquals(1, upcoming.size)
        assertEquals(RemoveResult.REMOVED, actions.cancel(upcoming.single().id))
        assertTrue(reminders.value.isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    @Test fun `upcoming reminders are the active profile's, soonest first`() = runBlocking {
        val news = channel(10)
        val shared = channel(30)
        actions.set(news, "Later", start + hour, start + 2 * hour)
        actions.set(shared, "Sooner", start, start + hour)
        activeProfile.value = 2L
        actions.set(channel(20), "Other profile", start, start + hour)
        activeProfile.value = 1L
        assertEquals(listOf("Sooner", "Later"), actions.observeUpcoming().first().map { it.programTitle })
    }

    // ---- 4.5.0 rows ------------------------------------------------------------------------------------

    @Test fun `4_5_0 rows saved as profile 1 move to the only profile that has their playlist`() = runBlocking {
        val profile2Only = channel(20)
        val shared = channel(30)
        reminders.value = listOf(row(id = 1, channel = profile2Only, profileId = 1), row(id = 2, channel = shared, profileId = 1))

        actions.repairLegacyOwners()

        assertEquals(2L, reminders.value.single { it.id == 1L }.profileId)
        assertEquals("shared playlist: no guessing", 1L, reminders.value.single { it.id == 2L }.profileId)
    }

    private fun row(id: Long, channel: ChannelEntity, profileId: Long) = ReminderEntity(
        id = id, profileId = profileId, sourceId = channel.sourceId, channelId = channel.id, channelName = channel.name,
        channelNumber = null, programId = null, programTitle = "Legacy", programStartMs = start,
        programEndMs = start + hour, triggerAtMs = start - 60_000, createdAtMs = now,
    )
}
