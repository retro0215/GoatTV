package tv.own.owntv.features.sports.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsApiClientInterpretTest {

    private fun interpret(code: Int, type: String?, body: String, retryAfter: String? = null) =
        SportsApiClient.interpret(code, type, retryAfter, body, SportsApiParser::parseEventsPage)

    private val okPage = SportsFixtures.page(listOf(SportsFixtures.event("evt_a")))

    @Test
    fun `200 JSON is a success`() {
        val r = interpret(200, "application/json; charset=utf-8", okPage)
        assertTrue(r is SportsApiResult.Success)
        assertEquals("evt_a", (r as SportsApiResult.Success).value.events.single().id)
    }

    @Test
    fun `DigitalOcean HTML 504 is temporarily unavailable, never parsed`() {
        val html = "<html><head><title>504 Gateway Time-out</title></head><body>…</body></html>"
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER), interpret(504, "text/html", html))
    }

    @Test
    fun `backend JSON 503 is temporarily unavailable`() {
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER), interpret(503, "application/json", """{"error":"Sports data is temporarily unavailable."}"""))
    }

    @Test
    fun `a 200 HTML page (captive portal, edge error) is not JSON`() {
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON), interpret(200, "text/html", "<!doctype html><p>Sign in to Wi-Fi</p>"))
    }

    @Test
    fun `malformed JSON is unavailable, not a crash`() {
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED), interpret(200, "application/json", """{"events":"""))
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED), interpret(200, "application/json", """{"unexpected":true}"""))
    }

    @Test
    fun `4xx JSON is rejected with the API error text`() {
        val r = interpret(400, "application/json", """{"error":"since is too old; reload /home.","requestId":"x"}""")
        assertEquals(SportsApiResult.Rejected(400, "since is too old; reload /home."), r)
    }

    @Test
    fun `429 honours Retry-After (bounded)`() {
        assertEquals(SportsApiResult.RateLimited(17), interpret(429, "application/json", "{}", retryAfter = "17"))
        assertEquals(SportsApiResult.RateLimited(60), interpret(429, null, "", retryAfter = null))
        assertEquals(SportsApiResult.RateLimited(600), interpret(429, null, "", retryAfter = "99999"))
    }

    @Test
    fun `unexpected status codes are unavailable`() {
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER), interpret(302, "application/json", "{}"))
        assertEquals(SportsApiResult.Unavailable(SportsApiResult.Reason.NOT_JSON), interpret(404, "text/html", "<html>not found</html>"))
    }
}
