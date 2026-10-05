package tv.own.owntv.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.diagnostics.DiagnosticTestFacts.healthy
import java.io.File
import kotlin.reflect.KClass

class DiagnosticReportTest {

    private val text = EnglishDiagnosticText()

    private fun report(f: DiagnosticFacts, finished: Boolean = true): String {
        val results = DiagnosticEvaluator.results(f)
        return DiagnosticReport.build(
            f, results, DiagnosticEvaluator.summary(results, finished), DiagnosticEvaluator.recommendations(f), text, nowMs = 42,
        )
    }

    @Test
    fun `healthy report carries the expected sections`() {
        val r = report(healthy)
        listOf(
            "DIAGNOSTIC REPORT",
            "SUMMARY: All checks passed",
            "APP: GoatTV 4.4.24 (build 40424)",
            "DEVICE: onn 4K Plus",
            "ANDROID: Android 14 (API 34)",
            "NETWORK: WIFI",
            "INTERNET: PASS",
            "SERVICE: PASS",
            "CHANNELS: 4625",
            "MOVIES: 12345",
            "SERIES: 3456",
            "VOD_PLAYER: EXO",
            "SPORTS_EVENTS: 120",
            "APP_UPDATE: Current",
            "RECOMMENDATIONS: None",
            "CPU: arm64-v8a",
            "TIMESTAMP: T42",
        ).forEach { assertTrue("missing '$it' in:\n$r", r.contains(it)) }
    }

    @Test
    fun `issues and recommendations are listed`() {
        val r = report(healthy.copy(service = ServiceFact.Unreachable(ServiceKind.XTREAM), update = UpdateFact.Available("4.4.25")))
        assertTrue(r.contains("SUMMARY: 3 issues found"))
        assertTrue(r.contains("- SERVICE_UNREACHABLE_VPN"))
        assertTrue(r.contains("- UPDATE_APP"))
        assertTrue(r.contains("APP_UPDATE: Available 4.4.25"))
    }

    @Test
    fun `an unfinished run never claims all checks passed`() {
        val r = report(DiagnosticFacts(app = DiagnosticTestFacts.app), finished = false)
        assertTrue(r.contains("SUMMARY: Diagnostic not finished"))
        assertFalse(r.contains("All checks passed"))
    }

    @Test
    fun `multi-brand - the report names whichever brand the build is`() {
        listOf("GoatTV", "AllAccess", "5Star Ultra", "Supreme TV").forEach { brand ->
            val r = report(healthy.copy(app = DiagnosticTestFacts.app.copy(brandName = brand)))
            assertTrue(r.contains("APP: $brand 4.4.24"))
            listOf("GoatTV", "AllAccess", "5Star Ultra", "Supreme TV").filter { it != brand }
                .forEach { other -> assertFalse("$brand report mentions $other", r.contains(other)) }
        }
    }

    // --- secret exclusion ---

    private val secrets = listOf(
        "http://line.provider-example.net:8080",
        "https://goattv-sports-lnc6f.ondigitalocean.app/sports/home?brand=goat",
        "player_api.php?username=alice&password=hunter2",
        "username=alice",
        "password=hunter2",
        "00:1A:79:12:34:56",
        "192.168.1.20:8080",
        "alice@example.com",
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefghij",
        "provider-example.net",
    )

    @Test
    fun `scrub removes every address, credential, MAC, IP, email and token shape`() {
        secrets.forEach { s ->
            val out = DiagnosticReport.scrub("before $s after")
            assertFalse("not scrubbed: $s -> $out", out.contains(s))
            assertTrue(out.startsWith("before ") && out.endsWith(" after"))
        }
    }

    @Test
    fun `scrub leaves ordinary report values alone`() {
        listOf("GoatTV 4.4.24 (build 40424)", "Android 14 (API 34)", "arm64-v8a", "3840x2160@60", "H.264 / AVC: Supported", "onn 4K Plus", "Chromecast HD")
            .forEach { assertEquals(it, DiagnosticReport.scrub(it)) }
    }

    @Test
    fun `secrets smuggled into device strings never reach the report`() {
        val hostile = healthy.copy(
            device = DiagnosticTestFacts.device.copy(
                manufacturer = "http://line.provider-example.net:8080",
                model = "box 00:1A:79:12:34:56 192.168.1.20:8080 password=hunter2",
                abi = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9abcdefghij",
            ),
        )
        val r = report(hostile)
        secrets.forEach { assertFalse("leaked: $it", r.contains(it)) }
        assertFalse(r.contains("hunter2"))
    }

    /**
     * Structural guard: the facts a report is built from may only carry these string fields. A new
     * String field (a URL, a source name, an error message...) must be reviewed here first.
     */
    @Test
    fun `facts carry no free-text fields beyond the reviewed allowlist`() {
        val allowed = setOf(
            "AppFact.brandName", "AppFact.versionName",
            "DeviceFact.manufacturer", "DeviceFact.model", "DeviceFact.androidRelease", "DeviceFact.abi",
            "Available.version",
        )
        val types: List<KClass<*>> = listOf(
            AppFact::class, InternetFact::class, ServiceFact.Ok::class, ServiceFact.Rejected::class, ServiceFact.Unreachable::class,
            PlaylistFact::class, EpgFact::class, PlayerFact::class, SportsFact.Connected::class,
            UpdateFact.Available::class, DeviceFact::class, DiagnosticFacts::class,
        )
        val found = types.flatMap { k ->
            k.java.declaredFields.filter { it.type == String::class.java && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { "${k.java.simpleName}.${it.name}" }
        }.toSet()
        assertEquals(allowed, found)
    }

    // --- multi-brand naming in resources ---

    // Gradle runs unit tests from the module directory; fall back to the repo root for IDE runs.
    private val moduleDir: File = if (File("src/main").isDirectory) File(".").absoluteFile else File("app").absoluteFile

    @Test
    fun `each flavor names itself and diagnostic copy hard-codes no brand`() {
        val names = mapOf("goat" to "GoatTV", "allaccess" to "AllAccess", "fivestar" to "5Star Ultra", "supreme" to "Supreme TV")
        names.forEach { (flavor, name) ->
            val xml = File(moduleDir, "src/$flavor/res/values/donottranslate.xml").readText()
            assertTrue("$flavor app_name", Regex("""name="app_name"[^>]*>\Q$name\E<""").containsMatchIn(xml))
        }
        val diag = File(moduleDir, "src/main/res/values/strings_settings.xml").readLines().filter { it.contains("name=\"diag_") }
        assertTrue(diag.size > 100)
        names.values.forEach { brand -> assertFalse("diagnostic strings mention $brand", diag.any { it.contains(brand) }) }
    }
}
