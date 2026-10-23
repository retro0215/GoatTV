package tv.own.owntv.core.sync.work

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ReminderDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.ReminderEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.sync.work.ReminderRelinker.Outcome

/**
 * Playlist refresh re-linking of reminders. The DAOs are in-memory tables; a "sync" replaces the
 * channel rows with new AUTOINCREMENT ids exactly like the real clear-then-insert does.
 */
class ReminderRelinkerTest {

    private val now = 1_000_000_000L
    private val start = now + 2 * 60 * 60 * 1000L
    private val sep = "\u0001"

    private val channels = mutableListOf<ChannelEntity>()
    private val reminders = mutableListOf<ReminderEntity>()
    private var nextChannelId = 10L

    /** Unique work names currently enqueued, named exactly as [ReminderScheduler] names them. */
    private val enqueued = mutableMapOf<String, Long>()
    private val jobs = object : ReminderJobs {
        override fun schedule(reminder: ReminderEntity) {
            enqueued[ReminderScheduler.warningWorkName(reminder.channelId, reminder.programStartMs)] = reminder.channelId
            enqueued[ReminderScheduler.autotuneWorkName(reminder.channelId, reminder.programStartMs)] = reminder.channelId
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

    private val reminderDao = mockk<ReminderDao>().also { dao ->
        coEvery { dao.getAll() } answers { reminders.toList() }
        coEvery { dao.getById(any()) } answers { reminders.find { it.id == firstArg<Long>() } }
        coEvery { dao.getForSourceStartingAfter(any(), any()) } answers {
            reminders.filter { it.sourceId == firstArg<Long>() && it.programStartMs > secondArg<Long>() }
        }
        coEvery { dao.exists(any(), any()) } answers {
            reminders.any { it.channelId == firstArg<Long>() && it.programStartMs == secondArg<Long>() }
        }
        coEvery { dao.updateChannelId(any(), any()) } answers {
            val i = reminders.indexOfFirst { it.id == firstArg<Long>() }
            if (i >= 0) reminders[i] = reminders[i].copy(channelId = secondArg())
        }
        coEvery { dao.delete(any(), any()) } answers {
            reminders.removeAll { it.channelId == firstArg<Long>() && it.programStartMs == secondArg<Long>() }
        }
        coEvery { dao.deleteById(any()) } answers { reminders.removeAll { it.id == firstArg<Long>() } }
    }

    private var clock = now
    private val relinker = ReminderRelinker(reminderDao, channelDao, jobs, now = { clock })

    private fun channel(sourceId: Long, name: String, remoteId: String?): ChannelEntity =
        ChannelEntity(id = nextChannelId++, sourceId = sourceId, name = name, streamUrl = "s", remoteId = remoteId)
            .also { channels += it }

    /** Simulates a refresh of [sourceId]: its rows are deleted and [rows] inserted with fresh ids. */
    private fun resync(sourceId: Long, vararg rows: Pair<String, String?>): List<ChannelEntity> {
        channels.removeAll { it.sourceId == sourceId }
        return rows.map { (name, remote) -> channel(sourceId, name, remote) }
    }

    private fun remind(channel: ChannelEntity, id: Long = 1, programStartMs: Long = start): ReminderEntity =
        ReminderEntity(
            id = id, profileId = 1, sourceId = channel.sourceId, channelId = channel.id, channelName = channel.name,
            channelNumber = null, programId = null, programTitle = "Match", programStartMs = programStartMs,
            programEndMs = programStartMs + 3_600_000, triggerAtMs = programStartMs - 60_000, createdAtMs = now,
        ).also { reminders += it; jobs.schedule(it) }

    private suspend fun refresh(sourceId: Long, type: SourceType, vararg rows: Pair<String, String?>): Map<Long, Outcome> {
        val snapshot = relinker.snapshot(sourceId, type)
        resync(sourceId, *rows)
        return relinker.relink(sourceId, type, snapshot)
    }

    @Test
    fun `xtream reminder follows its stream id to the new row and its jobs move with it`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        channel(1, "CNN", "502")
        remind(espn)

        val outcome = refresh(1, SourceType.XTREAM, "CNN" to "502", "ESPN" to "501")

        val newEspn = channels.single { it.remoteId == "501" }
        assertEquals(mapOf(1L to Outcome.RELINKED), outcome)
        assertEquals(newEspn.id, reminders.single().channelId)
        assertEquals(start, reminders.single().programStartMs)
        assertEquals("Match", reminders.single().programTitle)
        assertEquals(mapOf(
            ReminderScheduler.warningWorkName(newEspn.id, start) to newEspn.id,
            ReminderScheduler.autotuneWorkName(newEspn.id, start) to newEspn.id,
        ), enqueued)
    }

    @Test
    fun `missing channel keeps the reminder and its notification jobs, and tunes nothing`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        remind(espn)
        val jobsBefore = enqueued.toMap()

        val outcome = refresh(1, SourceType.XTREAM, "CNN" to "502")

        assertEquals(Outcome.MISSING, outcome[1L])
        assertEquals(espn.id, reminders.single().channelId)
        assertEquals(jobsBefore, enqueued)
        assertNull(relinker.safeTuneChannelId(reminders.single()))
    }

    @Test
    fun `xtream channels are never matched by display name`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        remind(espn)

        // Same name, different stream id: a different channel as far as the provider is concerned.
        val outcome = refresh(1, SourceType.XTREAM, "ESPN" to "777")

        assertEquals(Outcome.MISSING, outcome[1L])
        assertEquals(espn.id, reminders.single().channelId)
        assertNull(relinker.safeTuneChannelId(reminders.single()))
    }

    @Test
    fun `duplicate provider ids are ambiguous and leave the reminder alone`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        remind(espn)

        val outcome = refresh(1, SourceType.XTREAM, "ESPN" to "501", "ESPN HD" to "501")

        assertEquals(Outcome.AMBIGUOUS, outcome[1L])
        assertEquals(espn.id, reminders.single().channelId)
    }

    @Test
    fun `unique m3u name in its group re-links`() = runBlocking {
        val key = "ESPN${sep}Sports"
        remind(channel(1, "ESPN", key))

        val outcome = refresh(1, SourceType.M3U, "ESPN" to key, "ESPN" to "ESPN${sep}News")

        assertEquals(Outcome.RELINKED, outcome[1L])
        assertEquals(channels.single { it.remoteId == key }.id, reminders.single().channelId)
    }

    @Test
    fun `m3u duplicate names in a group are never guessed`() = runBlocking {
        val key = "ESPN${sep}Sports"
        // Duplicate before the refresh: the reminder's channel is one of two same-named entries.
        val first = channel(1, "ESPN", key)
        channel(1, "ESPN", "$key$sep#2")
        remind(first, id = 1)
        // Reminder on the second duplicate itself (ordinal key).
        val second = channel(1, "Fox", "Fox${sep}Sports$sep#2")
        channel(1, "Fox", "Fox${sep}Sports")
        remind(second, id = 2)
        // Unique before, but a duplicate appears in the refreshed playlist.
        val news = channel(1, "BBC", "BBC${sep}News")
        remind(news, id = 3)

        val outcome = refresh(
            1, SourceType.M3U,
            "ESPN" to key, "ESPN" to "$key$sep#2",
            "Fox" to "Fox${sep}Sports", "Fox" to "Fox${sep}Sports$sep#2",
            "BBC" to "BBC${sep}News", "BBC" to "BBC${sep}News$sep#2",
        )

        assertEquals(mapOf(1L to Outcome.NO_KEY, 2L to Outcome.NO_KEY, 3L to Outcome.AMBIGUOUS), outcome)
        assertEquals(listOf(first.id, second.id, news.id), reminders.map { it.channelId })
    }

    @Test
    fun `already-correct channel is left exactly as it was`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        remind(espn)
        val jobsBefore = enqueued.toMap()
        val snapshot = relinker.snapshot(1, SourceType.XTREAM)

        // Incremental sync kept the row (same id).
        val outcome = relinker.relink(1, SourceType.XTREAM, snapshot)

        assertEquals(Outcome.UNCHANGED, outcome[1L])
        assertEquals(espn.id, reminders.single().channelId)
        assertEquals(jobsBefore, enqueued)
    }

    @Test
    fun `cancelling a re-linked reminder removes every job`() = runBlocking {
        remind(channel(1, "ESPN", "501"))
        refresh(1, SourceType.XTREAM, "ESPN" to "501")
        val r = reminders.single()

        // What the guide's "Cancel reminder" does: delete the row, cancel by the stored channel id.
        reminderDao.delete(r.channelId, r.programStartMs)
        jobs.cancel(r.channelId, r.programStartMs)

        assertTrue(reminders.isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `reminder cancelled while the refresh ran is not resurrected`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        val r = remind(espn)
        val snapshot = relinker.snapshot(1, SourceType.XTREAM)
        resync(1, "ESPN" to "501")
        reminders.clear(); jobs.cancel(r.channelId, r.programStartMs)

        val outcome = relinker.relink(1, SourceType.XTREAM, snapshot)

        assertEquals(Outcome.GONE, outcome[1L])
        assertTrue(reminders.isEmpty())
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun `a reminder already set on the new row is not duplicated`() = runBlocking {
        val old = channel(1, "ESPN", "501")
        remind(old, id = 1)
        val snapshot = relinker.snapshot(1, SourceType.XTREAM)
        val fresh = resync(1, "ESPN" to "501").single()
        remind(fresh, id = 2) // user set it again after the refresh

        val outcome = relinker.relink(1, SourceType.XTREAM, snapshot)

        assertEquals(Outcome.CONFLICT, outcome[1L])
        assertEquals(listOf(old.id, fresh.id), reminders.map { it.channelId })
    }

    @Test
    fun `re-linked reminder survives an app restart and is rescheduled on the new channel`() = runBlocking {
        remind(channel(1, "ESPN", "501"), id = 1)
        remind(channel(1, "Old", "9"), id = 2, programStartMs = now - 1)
        refresh(1, SourceType.XTREAM, "ESPN" to "501", "Old" to "9")
        val newEspn = channels.single { it.remoteId == "501" }

        enqueued.clear() // process death: nothing in memory, WorkManager re-enqueued from the table
        relinker.restoreAfterRestart()

        assertEquals(listOf(1L), reminders.map { it.id })
        assertEquals(newEspn.id, reminders.single().channelId)
        assertEquals(setOf(newEspn.id), enqueued.values.toSet())
        assertEquals(2, enqueued.size)
    }

    @Test
    fun `auto-tune after a refresh switches to the re-linked channel even from a stale event`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        val staleEvent = remind(espn) // e.g. the banner captured it before the refresh
        refresh(1, SourceType.XTREAM, "ESPN" to "501")

        assertEquals(channels.single { it.remoteId == "501" }.id, relinker.safeTuneChannelId(staleEvent))
    }

    @Test
    fun `never tunes a channel from another playlist or a missing row`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        val other = channel(2, "ESPN", "501")
        remind(espn)

        // Corrupt row pointing at another playlist's channel.
        reminders[0] = reminders[0].copy(channelId = other.id)
        assertNull(relinker.safeTuneChannelId(reminders[0]))

        reminders[0] = reminders[0].copy(channelId = 999)
        assertNull(relinker.safeTuneChannelId(reminders[0]))
    }

    @Test
    fun `other playlists' reminders are not touched by this refresh`() = runBlocking {
        val mine = channel(1, "ESPN", "501")
        val theirs = channel(2, "ESPN", "501")
        remind(mine, id = 1)
        remind(theirs, id = 2)

        val outcome = refresh(1, SourceType.XTREAM, "ESPN" to "501")

        assertEquals(setOf(1L), outcome.keys)
        assertEquals(theirs.id, reminders.single { it.id == 2L }.channelId)
    }

    @Test
    fun `programme that already started is re-linked without repeating its notice`() = runBlocking {
        val espn = channel(1, "ESPN", "501")
        remind(espn, programStartMs = now + 60_000)
        val snapshot = relinker.snapshot(1, SourceType.XTREAM)
        resync(1, "ESPN" to "501")
        clock = now + 5 * 60_000 // the refresh finished 4 minutes after the start
        val jobsBefore = enqueued.toMap()

        val outcome = relinker.relink(1, SourceType.XTREAM, snapshot)

        assertEquals(Outcome.RELINKED, outcome[1L])
        assertEquals(channels.single().id, reminders.single().channelId)
        assertEquals(jobsBefore, enqueued) // pending jobs read the row by reminder id
    }
}
