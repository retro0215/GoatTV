package tv.own.owntv.features.epg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 4.6.0: the cold TV Guide open — inputs read together, guide keys by skip scan, preload gives way. */
class GuideColdLoadTest {

    // ---- Guide keys, source by source ------------------------------------------------------------------

    @Test fun `keys of several sources are read once per source and merged`() = runBlocking {
        val asked = mutableListOf<Long>()
        val keys = GuideKeys.read(listOf(2L, 7L, 2L)) { id -> asked += id; if (id == 2L) listOf("a", "b") else listOf("b", "c") }
        assertEquals(listOf(2L, 7L), asked)
        assertEquals(setOf("a", "b", "c"), keys)
    }

    @Test fun `no sources, no keys`() = runBlocking {
        assertEquals(emptySet<String>(), GuideKeys.read(emptyList()) { error("not read") })
    }

    // ---- The preload gives way to browsing ---------------------------------------------------------

    @Test fun `the preload waits for half a second after the last key`() {
        var t = 10_000L
        val gate = GuideInputGate { t }
        assertEquals("no key yet", 0L, gate.pauseMs())
        gate.note()
        assertEquals(GuideInputGate.QUIET_MS, gate.pauseMs())
        t += 200; assertEquals(300L, gate.pauseMs())
        t += 300; assertEquals(0L, gate.pauseMs())
        gate.note(); t += 100
        assertEquals("held keys keep it waiting", 400L, gate.pauseMs())
    }

    @Test fun `a quiet guide does not delay the preload`() = runBlocking {
        val gate = GuideInputGate { 0L }
        gate.awaitQuiet() // returns at once
    }

    // ---- Wiring --------------------------------------------------------------------------------------

    private val vm = File("src/main/java/tv/own/owntv/features/epg/EpgViewModel.kt").readText().replace("\r\n", "\n")
    private val load = vm.substringAfter("    fun load() {")

    @Test fun `the reads before the channel query run together, before it`() {
        val inputs = load.substringAfter("val inputs = kotlinx.coroutines.coroutineScope {").substringBefore("timing.mark(\"inputs read\")")
        for (read in listOf("maxCatchupDays", "customize.observe", "observeFavoriteIds", "sortLive", "sortGuide", "maxProgrammeId", "countForSources")) {
            assertTrue("$read is read concurrently", read in inputs)
        }
        assertTrue(inputs.split("async {").size - 1 >= 7)
        assertTrue(load.indexOf("timing.mark(\"inputs read\")") < load.indexOf("timing.mark(\"channel query start\")"))
        // Read once: the list build uses the same values.
        val build = load.substringAfter("timing.mark(\"channel query end").substringBefore("first channel data published")
        assertTrue("settings.sortLive.first()" !in build && "observeFavoriteIds" !in build)
    }

    @Test fun `guide keys come from the skip scan, kept in the cache`() {
        val keys = vm.substringAfter("private suspend fun guideChannels(").substringBefore("\n    }\n")
        assertTrue("guideKeyCache.get(ids, signal, importRunning) ?: GuideKeys.read(ids) { epgDao.guideKeysForSource(it) }" in keys)
        assertTrue("timing.mark(\"guide keys read" in keys)
    }

    @Test fun `every preload page waits while the guide is browsed, and the grid reports its keys`() {
        val preload = vm.substringAfter("private suspend fun loadWindowGrouped(").substringBefore("init {")
        assertTrue(preload.indexOf("inputGate.awaitQuiet()") in 1 until preload.indexOf("programmesInWindowPage"))
        val screen = File("src/main/java/tv/own/owntv/features/epg/EpgScreen.kt").readText().replace("\r\n", "\n")
        val gridKey = screen.substringAfter("fun onGridKey(").substringBefore("if (e.key == Key.Back)")
        assertTrue("vm.noteGuideInput()" in gridKey)
    }
}
