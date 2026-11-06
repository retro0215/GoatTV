package tv.own.owntv.core.diagnostics

import java.io.PrintWriter
import java.io.StringWriter

/**
 * Removes stream credentials from text before it reaches logcat, a diagnostic file or an exported report:
 * Xtream path credentials (`/live/<user>/<pass>/`, `/timeshift/<user>/<pass>/`, the short `/<user>/<pass>/<id>`
 * form), `user:pass@` userinfo, credential and token query parameters, Authorization/Cookie header values,
 * bearer tokens and MAC addresses (Stalker's device identity). Everything else (hosts, stream ids, codecs,
 * HTTP codes, timings) is kept, so playback diagnostics stay useful.
 *
 * Over-masking is acceptable here; leaking is not. Applied on every player diagnostic path, including the
 * mpv log observer (the native bridge no longer prints mpv's messages itself, see third_party/mpv-bridge).
 */
object SensitiveText {
    const val MASK = "•••"

    /**
     * What [redact] returns when the redaction rules could not be compiled on this device: the text is withheld
     * rather than passed through, and nothing throws, so logging can never break networking or playback.
     */
    const val WITHHELD = "[diagnostic text withheld: redaction unavailable]"

    // Android's regex engine is ICU, not the JVM's. ICU reads a character class that starts with `[:` as a
    // POSIX property (`[:alpha:]`) whenever a `:]` follows anywhere later in the pattern, and rejects the
    // pattern if the text in between is not a property name. Never start a class with `:`: write `[\-:]`, not
    // `[:\-]`. SensitiveTextIcuSyntaxTest guards this on the JVM; SensitiveTextAndroidTest compiles on ICU.
    private const val STOP = """[^/\s?#&"'<>]"""

    /** Each rule's source, in the order [redact] applies them. Named so a compile failure can be reported. */
    internal val PATTERNS: List<Pair<String, String>> = listOf(
        // `Authorization: ...`, `Cookie: ...` and similar header values, as mpv or OkHttp print them.
        "HEADERS" to
            """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-auth-token|x-api-key)(\s*[=:]\s*)[^\r\n]+""",
        "BEARER" to """(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{6,}""",
        // `scheme://user[:pass]@host`.
        "USERINFO" to """(?i)([a-z][a-z0-9+.\-]*://)[^/\s@]+@""",
        // `://host/live/<user>/<pass>/...` and the archive (`timeshift`), VOD and series forms.
        "XTREAM_TYPED" to """(?i)(://[^/\s]+/(?:live|movie|movies|series|vod|timeshift)/)$STOP+/$STOP+/""",
        // The short Xtream live form `://host/<user>/<pass>/<stream id>[.ext]`.
        "XTREAM_SHORT" to """(?i)(://[^/\s]+/)$STOP+/$STOP+/(\d+(?:\.[a-z0-9]{2,5})?)(?=$|[\s?#&"'<>)\],;])""",
        // Credential, token and device-identity parameters, in a query string or a `key=value` log field.
        "PARAMS" to
            """(?i)(?<![A-Za-z0-9_])(username|user|login|password|passwd|pass|pwd|token|access_token|auth_token|""" +
            """play_token|refresh_token|id_token|auth|key|api_key|apikey|signature|sig|hash|session|sessionid|""" +
            """jwt|mac|sn|serial|device_id|data)=([^&\s"'#<>]+)""",
        // Stalker's device identity.
        "MAC_ADDRESS" to """(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[\-:]){5}[0-9a-f]{2}(?![0-9a-f:])""",
    )

    private val redactor = Redactor(PATTERNS)

    /** The rule that failed to compile on this device, or null when redaction works. Logged once at startup. */
    val unavailableRule: String? get() = redactor.unavailableRule

    /** True when the rules compiled and [redact] masks credentials (false: it returns [WITHHELD]). */
    val available: Boolean get() = redactor.available

    /** [text] with every credential-shaped part replaced by [MASK]; [WITHHELD] if that can't be guaranteed. */
    fun redact(text: String): String = redactor.redact(text)

    /** Null-safe [redact]. */
    fun redactOrNull(text: String?): String? = text?.let(::redact)

    /** A throwable's stack trace, every message in its cause chain redacted (frames carry no credentials). */
    fun stackTrace(t: Throwable): String = redactor.stackTrace(t)

    /** `ClassName: message` for each throwable in the cause chain (at most [depth]), redacted. */
    fun describe(t: Throwable, depth: Int = 4): String = redactor.describe(t, depth)
}

/**
 * [SensitiveText]'s implementation, separate so tests can build one from a broken rule list.
 *
 * Rules are compiled in the constructor and a failure is caught there: it must never make [SensitiveText]
 * unloadable (ExceptionInInitializerError, then NoClassDefFoundError on every call, which stopped every
 * HttpClient request in 83c72047 on Android). If any rule fails, nothing is partially redacted: every call
 * returns [SensitiveText.WITHHELD] (traces keep exception class names only) and nothing throws.
 */
internal class Redactor(patterns: List<Pair<String, String>>) {
    var unavailableRule: String? = null
        private set

    private val rules: Map<String, Regex>? = run {
        val compiled = LinkedHashMap<String, Regex>()
        for ((name, pattern) in patterns) {
            val r = try { Regex(pattern) } catch (t: Throwable) { null }
            if (r == null) { unavailableRule = name; return@run null }
            compiled[name] = r
        }
        compiled
    }

    val available: Boolean get() = rules != null

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val r = rules ?: return SensitiveText.WITHHELD
        val mask = SensitiveText.MASK
        return try {
            var s = text
            s = r.getValue("HEADERS").replace(s) { "${it.groupValues[1]}${it.groupValues[2]}$mask" }
            s = r.getValue("BEARER").replace(s) { "${it.groupValues[1]} $mask" }
            s = r.getValue("USERINFO").replace(s) { "${it.groupValues[1]}$mask@" }
            s = r.getValue("XTREAM_TYPED").replace(s) { "${it.groupValues[1]}$mask/$mask/" }
            s = r.getValue("XTREAM_SHORT").replace(s) { "${it.groupValues[1]}$mask/$mask/${it.groupValues[2]}" }
            s = r.getValue("PARAMS").replace(s) { "${it.groupValues[1]}=$mask" }
            r.getValue("MAC_ADDRESS").replace(s, mask)
        } catch (t: Throwable) {
            SensitiveText.WITHHELD // e.g. StackOverflowError on pathological input: withhold, never the raw text
        }
    }

    fun stackTrace(t: Throwable): String {
        if (rules == null) return classChain(t)
        return try {
            val w = StringWriter()
            PrintWriter(w).use { t.printStackTrace(it) }
            redact(w.toString())
        } catch (e: Throwable) {
            classChain(t)
        }
    }

    fun describe(t: Throwable, depth: Int): String {
        if (rules == null) return classChain(t, depth)
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        while (cur != null && parts.size < depth) {
            parts += "${cur.javaClass.simpleName}: ${cur.message.orEmpty()}"
            cur = cur.cause.takeIf { it !== cur }
        }
        return redact(parts.joinToString(" <- "))
    }

    /** Exception class names only (messages can carry URLs): still says what failed when redaction can't run. */
    private fun classChain(t: Throwable, depth: Int = 8): String {
        val names = ArrayList<String>()
        var cur: Throwable? = t
        while (cur != null && names.size < depth) {
            names += cur.javaClass.name
            cur = cur.cause.takeIf { it !== cur }
        }
        return names.joinToString(" <- ") + " " + SensitiveText.WITHHELD
    }
}

/** android.util.Log for player diagnostic paths: the message and any throwable are redacted first. */
object SafeLog {
    fun i(tag: String, message: String) { android.util.Log.i(tag, SensitiveText.redact(message)) }
    fun w(tag: String, message: String, t: Throwable? = null) {
        android.util.Log.w(tag, withTrace(message, t))
    }
    fun e(tag: String, message: String, t: Throwable? = null) {
        android.util.Log.e(tag, withTrace(message, t))
    }

    private fun withTrace(message: String, t: Throwable?): String =
        if (t == null) SensitiveText.redact(message) else SensitiveText.redact(message) + "\n" + SensitiveText.stackTrace(t)
}
