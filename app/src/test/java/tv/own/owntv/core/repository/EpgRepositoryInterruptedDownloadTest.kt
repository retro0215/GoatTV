package tv.own.owntv.core.repository

import android.content.Context
import androidx.room.withTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.BulkInsertHelper
import tv.own.owntv.core.database.OwnTVDatabase
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.network.HttpClient
import tv.own.owntv.core.parser.XtreamClient
import tv.own.owntv.features.settings.data.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/**
 * A guide download that stops part-way keeps the programmes it read, but must not be reported as a
 * successful import: callers stamp lastSyncAt only when refreshUrl returns, and a stamped half guide was
 * not retried for a day. It must also not prune the existing guide or become the trusted cache.
 * Runs the real EpgRepository and XmltvParser; the database, network and files are stand-ins.
 */
class EpgRepositoryInterruptedDownloadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val storeId = -3L
    private val url = "http://guide.invalid/xmltv.php"

    private val epgDao = mockk<EpgDao>(relaxed = true)
    private val http = mockk<HttpClient>()
    private val channelDao = mockk<ChannelDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val db = mockk<OwnTVDatabase>(relaxed = true)
    private val bulk = mockk<BulkInsertHelper>(relaxed = true)
    private lateinit var cacheDir: File
    private lateinit var repo: EpgRepository

    /** What the "server" sends for the next download. */
    private var body: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) }

    @Before
    fun setUp() {
        cacheDir = tmp.newFolder("cache")
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cacheDir
        every { settings.activeProfileId } returns flowOf(-1L)
        coEvery { channelDao.allEpgChannelIds() } returns emptyList()
        coEvery { epgDao.countForSources(any()) } returns 0
        coEvery { bulk.tableIsEmpty(any()) } returns true
        coEvery { bulk.withOptimizedBulkInsert<Any?>(any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Any?>(4).invoke() // block; the continuation follows it
        }
        mockkStatic("androidx.room.RoomDatabaseKt")
        // Static extension: args are (receiver, block), so the block is arg 1.
        coEvery { db.withTransaction(any<suspend () -> Any?>()) } coAnswers { arg<suspend () -> Any?>(1).invoke() }
        coEvery { http.get<Any?>(any(), any(), any(), any(), any(), any()) } coAnswers {
            arg<suspend (InputStream) -> Any?>(5).invoke(body()) // block; the continuation follows it
        }
        repo = EpgRepository(
            epgDao, http, mockk<XtreamClient>(relaxed = true), channelDao, mockk<CustomizationStore>(relaxed = true),
            settings, context, db, bulk,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    /** A synthetic guide whose programmes sit inside the repository's rolling window. */
    private fun feed(programmes: Int): ByteArray {
        val start = System.currentTimeMillis() - 60 * 60 * 1000L
        val fmt = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val text = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
            append("<channel id=\"synth.one\"><display-name>Synthetic One</display-name></channel>\n")
            repeat(programmes) { i ->
                val s = fmt.format(java.util.Date(start + i * 60_000L))
                val e = fmt.format(java.util.Date(start + (i + 1) * 60_000L))
                append("<programme channel=\"synth.one\" start=\"$s +0000\" stop=\"$e +0000\">")
                append("<title>Programme $i</title><desc>Synthetic padding text number $i.</desc></programme>\n")
            }
            append("</tv>\n")
        }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private val cacheFile get() = File(cacheDir, "epg_$storeId$EPG_CACHE_SUFFIX")
    private val tempFile get() = File(cacheDir, "epg_$storeId$EPG_CACHE_TEMP_SUFFIX")

    private fun refresh(): Int = runBlocking { repo.refreshUrl(storeId, url, null) }

    @Test
    fun `a complete guide is stored, cached and pruned as before`() {
        val bytes = feed(2000)
        body = { ByteArrayInputStream(bytes) }
        val written = refresh()
        assertTrue(written > 0)
        assertTrue("complete feed becomes the trusted cache", cacheFile.exists())
        coVerify { epgDao.epgChannelIdsForSource(storeId) } // removed-channel prune ran
    }

    @Test
    fun `a cut-off download keeps what it read but fails, without pruning or caching`() {
        val bytes = feed(6000)
        body = { ByteArrayInputStream(bytes.copyOf(bytes.size * 6 / 10)) }

        assertThrows(IOException::class.java) { refresh() }

        // The rows read before the cut are kept (a partial guide beats none)…
        coVerify(atLeast = 1) { epgDao.upsertProgrammes(any()) }
        // …but nothing after the cut is treated as removed, and the cut file is not trusted.
        coVerify(exactly = 0) { epgDao.epgChannelIdsForSource(any()) }
        coVerify(exactly = 0) { epgDao.deleteProgrammesByIds(any()) }
        coVerify(exactly = 0) { epgDao.clearSource(any()) }
        assertFalse("cut file must not become the trusted cache", cacheFile.exists())
        assertFalse("temp file cleaned up", tempFile.exists())
    }

    @Test
    fun `a connection dropped mid-download fails the same way`() {
        val bytes = feed(6000)
        body = {
            object : InputStream() {
                private val inner = ByteArrayInputStream(bytes)
                private var served = 0
                override fun read(): Int {
                    if (served >= bytes.size / 2) throw IOException("synthetic connection drop")
                    served++
                    return inner.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val left = bytes.size / 2 - served
                    if (left <= 0) throw IOException("synthetic connection drop")
                    val n = inner.read(b, off, minOf(len, left))
                    if (n > 0) served += n
                    return n
                }
            }
        }
        assertThrows(IOException::class.java) { refresh() }
        coVerify(exactly = 0) { epgDao.epgChannelIdsForSource(any()) }
        assertFalse(cacheFile.exists())
    }

    @Test
    fun `a download that delivers nothing still fails as before`() {
        body = { throw IOException("synthetic connection refused") }
        assertThrows(IOException::class.java) { refresh() }
        coVerify(exactly = 0) { epgDao.upsertProgrammes(any()) }
    }

    @Test
    fun `a retry after a cut-off download completes normally`() {
        val bytes = feed(3000)
        body = { ByteArrayInputStream(bytes.copyOf(bytes.size / 2)) }
        assertThrows(IOException::class.java) { refresh() }

        body = { ByteArrayInputStream(bytes) }
        assertTrue(refresh() > 0)
        assertTrue(cacheFile.exists())
        coVerify(exactly = 1) { epgDao.epgChannelIdsForSource(storeId) } // pruned once, by the complete run
    }
}
