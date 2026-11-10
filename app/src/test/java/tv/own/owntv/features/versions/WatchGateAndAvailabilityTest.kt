package tv.own.owntv.features.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.EnginePreference

/** The movie early-OK fix (Watch waits for the lookup) and versions listed but not choosable. */
class WatchGateAndAvailabilityTest {

    private fun option(id: Long, unavailable: VersionOption.Unavailable? = null, confirmed: Boolean = true, anchor: Boolean = false, positionMs: Long = 0, durationMs: Long = 0, updatedAt: Long = 0) =
        VersionOption(
            itemId = id, key = "1:r$id", qualityRank = 3, picture = null, language = null, playlistName = null,
            categoryName = null, positionMs = positionMs, durationMs = durationMs, completed = false,
            progressUpdatedAt = updatedAt, isFavorite = false, isAnchor = anchor, confirmed = confirmed,
            pictureMayFallBack = false, unavailable = unavailable,
        )

    private val multi = VersionsUi(itemId = 7, ready = true, titleKey = "t|", options = listOf(option(7), option(8)))

    // ---- Movie early OK ----

    @Test fun `Watch during the lookup waits instead of playing directly`() {
        assertEquals(WatchAction.WAIT, watchAction(VersionsUi.loading(7), 7))
        assertEquals(WatchAction.WAIT, watchAction(null, 7))
        // The state of another film never counts for this one.
        assertEquals(WatchAction.WAIT, watchAction(VersionsUi.single(99), 7))
        assertEquals(WatchAction.PLAY_DIRECT, watchAction(VersionsUi.single(7), 7))
        assertEquals(WatchAction.CHOOSE, watchAction(multi, 7))
    }

    @Test fun `an early press opens the picker once the lookup finds versions`() {
        val gate = WatchGate(7)
        assertNull(gate.press(VersionsUi.loading(7)))
        assertTrue(gate.pending)
        assertNull(gate.onVersions(VersionsUi.loading(7)))
        assertEquals(WatchAction.CHOOSE, gate.onVersions(multi))
        assertFalse(gate.pending)
        // Later updates do nothing: one press, one action.
        assertNull(gate.onVersions(multi))
    }

    @Test fun `an early press on a one-version film plays it directly, once`() {
        val gate = WatchGate(7)
        assertNull(gate.press(VersionsUi.loading(7)))
        assertNull(gate.press(VersionsUi.loading(7))) // double OK while waiting adds nothing
        assertEquals(WatchAction.PLAY_DIRECT, gate.onVersions(VersionsUi.single(7)))
        assertNull(gate.onVersions(VersionsUi.single(7)))
    }

    @Test fun `a press after the lookup acts immediately and Back forgets a held press`() {
        val gate = WatchGate(7)
        assertEquals(WatchAction.CHOOSE, gate.press(multi))
        assertNull(gate.onVersions(multi))
        assertNull(gate.press(VersionsUi.loading(7)))
        gate.cancel()
        assertNull(gate.onVersions(multi))
    }

    @Test fun `ExoPlayer-only counts as starting on ExoPlayer, mpv-first does not`() {
        assertTrue(VersionOptions.startsOnExo(EnginePreference.EXO_FIRST))
        assertTrue(VersionOptions.startsOnExo(EnginePreference.EXO_ONLY))
        assertFalse(VersionOptions.startsOnExo(EnginePreference.MPV_FIRST))
        assertFalse(VersionOptions.startsOnExo(EnginePreference.MPV_ONLY))
    }

    // ---- Listed but not choosable ----

    @Test fun `focus never starts on a version without the episode, even a remembered one`() {
        val options = listOf(option(1, VersionOption.Unavailable.MISSING), option(2, confirmed = false, anchor = true), option(3))
        assertEquals(2, VersionChoice.initialIndex(options, null))
        assertEquals(2, VersionChoice.initialIndex(options, "1:r1"))
        assertEquals(1, VersionChoice.initialIndex(options.map { it.copy(confirmed = false) }, null))
    }

    @Test fun `OK on an unavailable row does nothing and the picker stays open`() {
        val options = listOf(option(1), option(2, VersionOption.Unavailable.UNMATCHED))
        val model = VersionPickerModel(options.size, 0) { options[it].unavailable == null }
        model.moveDown()
        assertEquals(1, model.focusedIndex) // still reachable, so its reason can be read
        assertNull(model.choose())
        assertFalse(model.finished)
        model.moveUp()
        assertEquals(0, model.choose())
    }

    @Test fun `an unavailable version's progress is never offered as a resume point`() {
        val chosen = option(1)
        val gone = option(2, VersionOption.Unavailable.NOT_LOADED, positionMs = 600_000, durationMs = 1_800_000, updatedAt = 9)
        assertEquals(VersionChoice.StartPlan(0, null), VersionChoice.startPlan(chosen, 1_800_000, listOf(chosen, gone)))
    }

    @Test fun `episode resume crosses versions only for the same length`() {
        // The episode left part-way in 1080p; the 4K episode is the same length within 2 %.
        val uhdEpisode = option(12)
        val hdEpisode = option(2, positionMs = 900_000, durationMs = 2_640_000, updatedAt = 5)
        assertEquals(VersionChoice.StartPlan(0, 900_000), VersionChoice.startPlan(uhdEpisode, 2_650_000, listOf(hdEpisode, uhdEpisode)))
        // A recap-trimmed cut 3 minutes shorter: start from the beginning.
        assertEquals(VersionChoice.StartPlan(0, null), VersionChoice.startPlan(uhdEpisode, 2_460_000, listOf(hdEpisode, uhdEpisode)))
    }
}
