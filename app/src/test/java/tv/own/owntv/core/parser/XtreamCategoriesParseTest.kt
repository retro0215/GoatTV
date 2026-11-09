package tv.own.owntv.core.parser

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.network.HttpClient

/**
 * `get_*_categories` objects with boolean fields (`"is_adult": false`) used to fail the whole Xtream
 * import with "Expected a string but was BOOLEAN" (upstream OwnTV #349, OwnTV_Core eb5e09de).
 */
class XtreamCategoriesParseTest {

    private val client = XtreamClient(HttpClient(OkHttpClient()))

    private fun parse(json: String): List<XtCategory> = runBlocking { client.parseCategories(json.byteInputStream()) }

    @Test
    fun `boolean fields no longer fail the category list`() {
        val json = """
            [
              {"category_id": "1", "category_name": "News", "parent_id": 0, "is_adult": false},
              {"category_id": "2", "category_name": "Late night", "parent_id": 0, "is_adult": true},
              {"category_id": 3, "category_name": "Numbers as ids", "is_adult": "0"}
            ]
        """.trimIndent()
        assertEquals(
            listOf(XtCategory("1", "News"), XtCategory("2", "Late night"), XtCategory("3", "Numbers as ids")),
            parse(json),
        )
    }

    @Test
    fun `nulls, nested values and a missing name keep their old handling`() {
        val json = """
            [
              {"category_id": "7", "category_name": null, "tags": ["a", "b"], "meta": {"x": 1}},
              {"category_name": "No id is skipped"}
            ]
        """.trimIndent()
        assertEquals(listOf(XtCategory("7", "7")), parse(json))
    }

    @Test
    fun `an error object instead of a list still reads as no categories`() {
        assertEquals(emptyList<XtCategory>(), parse("""{"user_info": {"auth": 0}}"""))
    }
}
