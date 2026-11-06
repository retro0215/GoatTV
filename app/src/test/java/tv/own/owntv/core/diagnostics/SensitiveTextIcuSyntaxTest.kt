package tv.own.owntv.core.diagnostics

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regexes with ICU, which the JVM running these tests does not. ICU reads a class starting with
 * `[:` (or `[^:`) as a POSIX property whenever a `:]` follows later in the pattern, and throws
 * PatternSyntaxException if the text between is not a property name; the JVM accepts it. That broke
 * [SensitiveText] on every Android device in 83c72047. SensitiveTextAndroidTest compiles on ICU itself; this
 * test catches the trap without a device. Synthetic credentials only.
 */
class SensitiveTextIcuSyntaxTest {
    /** The ICU trap: a class opening with `:` (`[:` / `[^:`, not an escaped `\[`) and a `:]` anywhere after it. */
    private val icuTrap = Regex("""(?<!\\)\[\^?:.*:]""")
    private val classStartsWithColon = Regex("""(?<!\\)\[\^?:""")

    @Test
    fun `no redaction rule starts a character class with a colon`() {
        for ((name, pattern) in SensitiveText.PATTERNS) {
            assertFalse("$name starts a class with ':' (ICU POSIX-property trap): $pattern", classStartsWithColon.containsMatchIn(pattern))
        }
    }

    @Test
    fun `the 83c72047 MAC pattern is the shape this guard catches`() {
        val shipped = """(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}(?![0-9a-f:])"""
        assertTrue(icuTrap.containsMatchIn(shipped))
        assertFalse(icuTrap.containsMatchIn(SensitiveText.PATTERNS.toMap().getValue("MAC_ADDRESS")))
    }

    @Test
    fun `no regex in the app sources has the ICU POSIX-property trap`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") } }
                .filter { (_, line) -> icuTrap.containsMatchIn(line) }
                .map { (i, line) -> "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
        }.toList()
        assertEquals("regexes Android's ICU engine rejects:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun `SensitiveText initializes with every rule`() {
        assertTrue(SensitiveText.available)
        assertNull(SensitiveText.unavailableRule)
        assertEquals(
            listOf("HEADERS", "BEARER", "USERINFO", "XTREAM_TYPED", "XTREAM_SHORT", "PARAMS", "MAC_ADDRESS"),
            SensitiveText.PATTERNS.map { it.first },
        )
    }

    @Test
    fun `MAC addresses with either separator are still masked`() {
        for (mac in listOf("AA:BB:CC:11:22:33", "aa-bb-cc-11-22-33", "00:1A:79:Ab:cD:Ef")) {
            assertEquals("mac ${SensitiveText.MASK} ok", SensitiveText.redact("mac $mac ok"))
        }
        assertEquals("time 12:34:56 ok", SensitiveText.redact("time 12:34:56 ok"))
    }

    @Test
    fun `a rule that fails to compile withholds everything and never throws`() {
        val broken = Redactor(SensitiveText.PATTERNS.map { (n, p) -> if (n == "MAC_ADDRESS") n to "(" else n to p })
        assertFalse(broken.available)
        assertEquals("MAC_ADDRESS", broken.unavailableRule)
        val secret = "http://panel.invalid/live/synthUser42/synthPass99/1.ts"
        assertEquals(SensitiveText.WITHHELD, broken.redact(secret))
        assertEquals("", broken.redact(""))
        val t = IOException("open failed: $secret", IllegalStateException("password=synthPass99"))
        for (out in listOf(broken.stackTrace(t), broken.describe(t, 4))) {
            assertFalse(out, out.contains("synth"))
            assertTrue(out, out.contains("java.io.IOException <- java.lang.IllegalStateException"))
            assertTrue(out, out.contains(SensitiveText.WITHHELD))
        }
    }

    @Test
    fun `a rule failing at match time withholds instead of throwing`() {
        // A missing rule makes redact() fail after compiling; it must fall back to WITHHELD, not the raw text.
        val partial = Redactor(SensitiveText.PATTERNS.filterNot { it.first == "PARAMS" })
        assertTrue(partial.available)
        assertEquals(SensitiveText.WITHHELD, partial.redact("password=synthPass99"))
    }
}
