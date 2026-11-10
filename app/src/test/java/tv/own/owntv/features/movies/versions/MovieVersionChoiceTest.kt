package tv.own.owntv.features.movies.versions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.catalog.versions.AdvertisedPicture
import tv.own.owntv.core.player.DeviceVideoCaps

class MovieVersionChoiceTest {

    private val hdTv = DeviceVideoCaps(uhdOutput = false, hevcUhdHardware = true, hdrDisplay = false, dolbyVisionDisplay = false, dolbyVisionDecoder = false)
    private val uhdHdrTv = DeviceVideoCaps(uhdOutput = true, hevcUhdHardware = true, hdrDisplay = true, dolbyVisionDisplay = false, dolbyVisionDecoder = false)
    private val dvTv = DeviceVideoCaps(uhdOutput = true, hevcUhdHardware = true, hdrDisplay = true, dolbyVisionDisplay = true, dolbyVisionDecoder = true)

    private fun option(
        id: Long,
        rank: Int = 3,
        picture: AdvertisedPicture? = null,
        positionMs: Long = 0,
        durationMs: Long = 0,
        completed: Boolean = false,
        updatedAt: Long = 0,
        anchor: Boolean = false,
        confirmed: Boolean = true,
    ) = MovieVersionOption(
        movieId = id, key = "1:r$id", qualityRank = rank, picture = picture, language = null,
        playlistName = "P", categoryName = null, positionMs = positionMs, durationMs = durationMs,
        completed = completed, progressUpdatedAt = updatedAt, isFavorite = false, isAnchor = anchor,
        confirmed = confirmed, pictureMayFallBack = false,
    )

    // ---- Device compatibility ----

    @Test fun `1080p and unlabelled versions are always confirmed`() {
        assertTrue(MovieVersionChoice.confirmedPlayable(3, null, DeviceVideoCaps.UNKNOWN, filmsStartOnExo = false))
        assertTrue(MovieVersionChoice.confirmedPlayable(0, null, DeviceVideoCaps.UNKNOWN, filmsStartOnExo = false))
    }

    @Test fun `4K needs a 4K output and a hardware HEVC decoder`() {
        assertFalse(MovieVersionChoice.confirmedPlayable(4, null, hdTv, true))
        assertTrue(MovieVersionChoice.confirmedPlayable(4, null, uhdHdrTv, true))
        assertFalse(MovieVersionChoice.confirmedPlayable(4, null, uhdHdrTv.copy(hevcUhdHardware = false), true))
    }

    @Test fun `unknown capabilities are never treated as yes`() {
        val unknown = DeviceVideoCaps.UNKNOWN
        assertFalse(MovieVersionChoice.confirmedPlayable(4, null, unknown, true))
        assertFalse(MovieVersionChoice.confirmedPlayable(3, AdvertisedPicture.HDR10, unknown, true))
        assertFalse(MovieVersionChoice.confirmedPlayable(4, AdvertisedPicture.DOLBY_VISION, unknown, true))
        assertTrue(MovieVersionChoice.pictureMayFallBack(AdvertisedPicture.HDR, unknown))
        assertTrue(MovieVersionChoice.pictureMayFallBack(AdvertisedPicture.DOLBY_VISION, unknown))
    }

    @Test fun `HDR needs an HDR display`() {
        assertFalse(MovieVersionChoice.confirmedPlayable(3, AdvertisedPicture.HDR10, hdTv, true))
        assertTrue(MovieVersionChoice.confirmedPlayable(3, AdvertisedPicture.HDR10_PLUS, uhdHdrTv, true))
        assertFalse(MovieVersionChoice.pictureMayFallBack(AdvertisedPicture.HDR10, uhdHdrTv))
    }

    @Test fun `Dolby Vision needs display, decoder and ExoPlayer first - never steered to mpv`() {
        assertTrue(MovieVersionChoice.confirmedPlayable(4, AdvertisedPicture.DOLBY_VISION, dvTv, filmsStartOnExo = true))
        assertFalse(MovieVersionChoice.confirmedPlayable(4, AdvertisedPicture.DOLBY_VISION, dvTv, filmsStartOnExo = false))
        // An HDR10 TV is not a Dolby Vision TV.
        assertFalse(MovieVersionChoice.confirmedPlayable(4, AdvertisedPicture.DOLBY_VISION, uhdHdrTv, true))
        assertTrue(MovieVersionChoice.pictureMayFallBack(AdvertisedPicture.DOLBY_VISION, uhdHdrTv))
        assertFalse(MovieVersionChoice.pictureMayFallBack(AdvertisedPicture.DOLBY_VISION, dvTv))
        // No decoder: the display alone does not count.
        assertFalse(MovieVersionChoice.confirmedPlayable(4, AdvertisedPicture.DOLBY_VISION, dvTv.copy(dolbyVisionDecoder = null), true))
    }

    // ---- Initial focus ----

    @Test fun `focus starts on the best confirmed version, never hiding the others`() {
        val options = listOf(
            option(1, rank = 4, picture = AdvertisedPicture.DOLBY_VISION, confirmed = false),
            option(2, rank = 4, confirmed = false),
            option(3, rank = 3, anchor = true),
        )
        assertEquals(2, MovieVersionChoice.initialIndex(options, null))
        assertEquals(3, options.size)
    }

    @Test fun `remembered choice wins, even an unconfirmed one the customer picked`() {
        val options = listOf(option(1, rank = 4, confirmed = false), option(2, rank = 3))
        assertEquals(0, MovieVersionChoice.initialIndex(options, "1:r1"))
    }

    @Test fun `a remembered version that is gone falls back`() {
        val options = listOf(option(1, rank = 4), option(2, rank = 3))
        assertEquals(0, MovieVersionChoice.initialIndex(options, "1:r99"))
    }

    @Test fun `nothing confirmed falls back to the version the customer opened`() {
        val options = listOf(option(1, confirmed = false), option(2, confirmed = false, anchor = true))
        assertEquals(1, MovieVersionChoice.initialIndex(options, null))
        assertEquals(0, MovieVersionChoice.initialIndex(emptyList(), null))
    }

    // ---- Where a version starts (progress is per version) ----

    @Test fun `own unfinished progress resumes directly`() {
        val chosen = option(1, positionMs = 60_000, durationMs = 6_000_000)
        val plan = MovieVersionChoice.startPlan(chosen, 6_000_000, listOf(chosen, option(2, positionMs = 90_000, durationMs = 6_000_000, updatedAt = 9)))
        assertEquals(MovieVersionChoice.StartPlan(60_000, null), plan)
    }

    @Test fun `same-length other version offers an equivalent resume`() {
        val chosen = option(1)
        val other = option(2, positionMs = 1_800_000, durationMs = 6_000_000, updatedAt = 5)
        val plan = MovieVersionChoice.startPlan(chosen, 6_060_000, listOf(chosen, other)) // 1 % longer
        assertEquals(MovieVersionChoice.StartPlan(0, 1_800_000), plan)
    }

    @Test fun `different cuts or an unknown duration start from the beginning`() {
        val chosen = option(1)
        val other = option(2, positionMs = 1_800_000, durationMs = 6_000_000)
        assertEquals(MovieVersionChoice.StartPlan(0, null), MovieVersionChoice.startPlan(chosen, 7_200_000, listOf(chosen, other)))
        assertEquals(MovieVersionChoice.StartPlan(0, null), MovieVersionChoice.startPlan(chosen, null, listOf(chosen, other)))
        val otherNoDuration = option(2, positionMs = 1_800_000, durationMs = 0)
        assertEquals(MovieVersionChoice.StartPlan(0, null), MovieVersionChoice.startPlan(chosen, 6_000_000, listOf(chosen, otherNoDuration)))
    }

    @Test fun `finished versions offer nothing and the most recent other progress is used`() {
        val chosen = option(1, positionMs = 5_900_000, durationMs = 6_000_000, completed = true)
        val old = option(2, positionMs = 600_000, durationMs = 6_000_000, updatedAt = 1)
        val recent = option(3, positionMs = 2_400_000, durationMs = 6_000_000, updatedAt = 7)
        val done = option(4, positionMs = 5_990_000, durationMs = 6_000_000, completed = true, updatedAt = 9)
        assertEquals(MovieVersionChoice.StartPlan(0, 2_400_000), MovieVersionChoice.startPlan(chosen, 6_000_000, listOf(chosen, old, recent, done)))
        assertEquals(MovieVersionChoice.StartPlan(0, null), MovieVersionChoice.startPlan(chosen, 6_000_000, listOf(chosen, done)))
    }

    // ---- Remembered choice: per profile, bounded ----

    @Test fun `choices are remembered per profile and title`() {
        var json: String? = null
        json = VersionChoiceStore.write(json, 1, "dune|2021", "1:r4k")
        json = VersionChoiceStore.write(json, 2, "dune|2021", "2:rhd")
        assertEquals("1:r4k", VersionChoiceStore.read(json, 1, "dune|2021"))
        assertEquals("2:rhd", VersionChoiceStore.read(json, 2, "dune|2021"))
        assertNull(VersionChoiceStore.read(json, 3, "dune|2021")) // a Kids profile sees none of these
        assertNull(VersionChoiceStore.read(json, 1, "dune|1984"))
        json = VersionChoiceStore.write(json, 1, "dune|2021", "1:rhd")
        assertEquals("1:rhd", VersionChoiceStore.read(json, 1, "dune|2021"))
    }

    @Test fun `the store is bounded and drops the oldest choice first`() {
        var json: String? = null
        for (i in 0 until VersionChoiceStore.MAX_ENTRIES + 5) json = VersionChoiceStore.write(json, 1, "t$i", "v$i")
        assertNull(VersionChoiceStore.read(json, 1, "t0"))
        assertNull(VersionChoiceStore.read(json, 1, "t4"))
        assertEquals("v5", VersionChoiceStore.read(json, 1, "t5"))
        assertEquals("v${VersionChoiceStore.MAX_ENTRIES + 4}", VersionChoiceStore.read(json, 1, "t${VersionChoiceStore.MAX_ENTRIES + 4}"))
    }

    @Test fun `re-choosing refreshes an entry so it is not evicted`() {
        var json: String? = VersionChoiceStore.write(null, 1, "keep", "k")
        for (i in 1 until VersionChoiceStore.MAX_ENTRIES) json = VersionChoiceStore.write(json, 1, "t$i", "v$i")
        json = VersionChoiceStore.write(json, 1, "keep", "k2")
        json = VersionChoiceStore.write(json, 1, "new", "n")
        assertEquals("k2", VersionChoiceStore.read(json, 1, "keep"))
        assertNull(VersionChoiceStore.read(json, 1, "t1"))
    }

    @Test fun `corrupt stored value reads as nothing and is replaced`() {
        assertNull(VersionChoiceStore.read("{not json", 1, "x"))
        assertEquals("v", VersionChoiceStore.read(VersionChoiceStore.write("{not json", 1, "x", "v"), 1, "x"))
    }

    // ---- The picker on a remote ----

    @Test fun `D-pad moves within bounds`() {
        val model = VersionPickerModel(optionCount = 3, initialIndex = 1)
        model.moveUp(); model.moveUp()
        assertEquals(0, model.focusedIndex)
        model.moveDown(); model.moveDown(); model.moveDown()
        assertEquals(2, model.focusedIndex)
        assertEquals(2, VersionPickerModel(3, 9).focusedIndex)
    }

    @Test fun `double OK plays once`() {
        val model = VersionPickerModel(3, 0)
        model.moveDown()
        assertEquals(1, model.choose())
        assertNull(model.choose())
        assertNull(model.choose(2))
        assertFalse(model.dismiss())
    }

    @Test fun `Back closes without playing, once`() {
        val model = VersionPickerModel(2, 0)
        assertTrue(model.dismiss())
        assertFalse(model.dismiss())
        assertNull(model.choose())
    }
}
