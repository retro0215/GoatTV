package tv.own.owntv.features.tutorials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.rooms.BrandResolver

class VideoTutorialsCatalogTest {

    private fun row(
        id: String,
        title: String? = "Title $id",
        videoId: String? = "dQw4w9WgXcQ",
        category: String? = null,
        sort: Int = 0,
        description: String? = null,
        duration: Int? = null,
    ): String {
        fun q(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "\\\"") + "\""
        return """{"id":${q(id)},"title":${q(title)},"description":${q(description)},"youtube_video_id":${q(videoId)},""" +
            """"category":${q(category)},"duration_seconds":${duration ?: "null"},"sort_order":$sort,"updated_at":"2026-10-23T00:46:13Z"}"""
    }

    @Test
    fun `brand mapping covers the four production app ids`() {
        assertEquals("goat", BrandResolver.resolveBrandId("tv.own.owntv", flavorBrand = ""))
        assertEquals("allaccess", BrandResolver.resolveBrandId("tv.allaccess.app", flavorBrand = ""))
        assertEquals("fivestar", BrandResolver.resolveBrandId("tv.fivestar.ultra", flavorBrand = ""))
        assertEquals("supreme", BrandResolver.resolveBrandId("tv.supreme.app", flavorBrand = ""))
    }

    @Test
    fun `the brand flavor decides, so a suffixed test application id keeps its brand`() {
        for ((appId, brand) in listOf(
            "tv.own.owntv.tutorialtest" to "goat",
            "tv.allaccess.app.tutorialtest" to "allaccess",
            "tv.fivestar.ultra.tutorialtest" to "fivestar",
            "tv.supreme.app.tutorialtest" to "supreme",
        )) {
            assertEquals(appId, brand, BrandResolver.resolveBrandId(appId, flavorBrand = brand))
            assertEquals(appId, brand, BrandResolver.resolveBrandId(appId, flavorBrand = ""))
        }
        // The flavor wins over an application id that disagrees with it.
        assertEquals("supreme", BrandResolver.resolveBrandId("tv.own.owntv", flavorBrand = "supreme"))
        // The build under test resolves to its own flavor.
        assertEquals(tv.own.owntv.BuildConfig.FLAVOR_brand, BrandResolver.resolveBrandId())
    }

    @Test
    fun `parses the staging RPC row shape`() {
        val body = """[{"id":"0763d369-c56f-411b-8da7-7378f5aac10a","title":"How to Use GoatTV","description":null,""" +
            """"youtube_video_id":"_y_Xpps46Uc","category":"getting-started","duration_seconds":null,"sort_order":0,""" +
            """"updated_at":"2026-10-23T00:46:13.733009+00:00"}]"""
        val list = VideoTutorialsParser.parse(body)!!
        assertEquals(1, list.size)
        with(list[0]) {
            assertEquals("0763d369-c56f-411b-8da7-7378f5aac10a", id)
            assertEquals("How to Use GoatTV", title)
            assertNull(description)
            assertEquals("_y_Xpps46Uc", youtubeVideoId)
            assertEquals("getting-started", category)
            assertNull(durationSeconds)
            assertEquals(0, sortOrder)
        }
    }

    @Test
    fun `drops rows with invalid video ids, missing ids or blank titles, and duplicate ids`() {
        val body = "[" + listOf(
            row("ok1", sort = 2),
            row("bad-url", videoId = "https://youtu.be/dQw4w9WgXcQ"),
            row("bad-short", videoId = "short"),
            row("bad-space", videoId = "aaaaaaaaa a"),
            row("bad-inject", videoId = "dQw4w9WgXcQ&list=x"),
            row("bad-null", videoId = null),
            row("no-title", title = "   "),
            row("ok1", title = "duplicate", sort = 0),
            row("ok2", sort = 1, description = "  Step by step  ", duration = 212),
        ).joinToString(",") + "," + """{"title":"no id","youtube_video_id":"dQw4w9WgXcQ"}""" + ",42]"
        val list = VideoTutorialsParser.parse(body)!!
        assertEquals(listOf("ok2", "ok1"), list.map { it.id })
        assertEquals("Title ok1", list[1].title)
        assertEquals("Step by step", list[0].description)
        assertEquals(212, list[0].durationSeconds)
    }

    @Test
    fun `non-array bodies are rejected and an empty array is an empty catalog`() {
        assertNull(VideoTutorialsParser.parse("""{"code":"PGRST202"}"""))
        assertNull(VideoTutorialsParser.parse("<html>"))
        assertNull(VideoTutorialsParser.parse(""))
        assertEquals(emptyList<VideoTutorial>(), VideoTutorialsParser.parse("[]"))
    }

    @Test
    fun `unsafe category values become uncategorized and zero durations are dropped`() {
        val list = VideoTutorialsParser.parse("[" + row("a", category = "Live-TV") + "," + row("b", category = "bad category!", sort = 1, duration = 0) + "]")!!
        assertEquals("live-tv", list[0].category)
        assertNull(list[1].category)
        assertNull(list[1].durationSeconds)
    }

    @Test
    fun `grouping keeps catalog order and places each category at its first tutorial`() {
        val list = VideoTutorialsParser.parse(
            "[" + listOf(
                row("1", category = "getting-started", sort = 1),
                row("2", category = "sports", sort = 2),
                row("3", category = null, sort = 3),
                row("4", category = "getting-started", sort = 4),
                row("5", category = "sports", sort = 5),
            ).joinToString(",") + "]",
        )!!
        val groups = groupByCategory(list)
        assertEquals(listOf("getting-started", "sports", null), groups.map { it.category })
        assertEquals(listOf("1", "4"), groups[0].tutorials.map { it.id })
        assertEquals(listOf("2", "5"), groups[1].tutorials.map { it.id })
        assertEquals(listOf("3"), groups[2].tutorials.map { it.id })
        assertEquals(emptyList<VideoTutorialGroup>(), groupByCategory(emptyList()))
    }

    @Test
    fun `thumbnails are built only from valid ids`() {
        assertEquals("https://i.ytimg.com/vi/_y_Xpps46Uc/hqdefault.jpg", YouTubeVideoId.thumbnailUrl("_y_Xpps46Uc"))
        assertNull(YouTubeVideoId.thumbnailUrl("../../evil"))
        assertFalse(YouTubeVideoId.isValid(null))
        assertTrue(YouTubeVideoId.isValid("abc-DEF_123"))
    }

    @Test
    fun `unknown category slugs are shown readably`() {
        assertEquals("New thing", readableSlug("new-thing"))
        assertEquals("Faq", readableSlug("faq"))
    }

    @Test
    fun `API answers are interpreted`() {
        val ok = VideoTutorialsApi.interpret(200, "[" + row("a") + "]")
        assertTrue(ok is VideoTutorialsFetch.Success && ok.tutorials.single().id == "a")
        assertEquals(VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.BAD_RESPONSE), VideoTutorialsApi.interpret(200, "{}"))
        assertEquals(
            VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.NOT_AVAILABLE),
            VideoTutorialsApi.interpret(404, """{"code":"PGRST202","message":"Could not find the function"}"""),
        )
        assertEquals(VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.SERVER), VideoTutorialsApi.interpret(401, "{}"))
        assertEquals(VideoTutorialsFetch.Failure(VideoTutorialsFetch.Reason.SERVER), VideoTutorialsApi.interpret(503, ""))
        assertEquals("""{"p_brand_id":"supreme"}""", VideoTutorialsApi.requestBody("supreme"))
    }
}
