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

    private const val STOP = """[^/\s?#&"'<>]"""

    /** `://host/live/<user>/<pass>/...` and the archive (`timeshift`), VOD and series forms. */
    private val XTREAM_TYPED = Regex("""(?i)(://[^/\s]+/(?:live|movie|movies|series|vod|timeshift)/)$STOP+/$STOP+/""")

    /** The short Xtream live form `://host/<user>/<pass>/<stream id>[.ext]`. */
    private val XTREAM_SHORT = Regex("""(?i)(://[^/\s]+/)$STOP+/$STOP+/(\d+(?:\.[a-z0-9]{2,5})?)(?=$|[\s?#&"'<>)\],;])""")

    /** `scheme://user[:pass]@host`. */
    private val USERINFO = Regex("""(?i)([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")

    /** Credential, token and device-identity parameters, in a query string or a `key=value` log field. */
    private val PARAMS = Regex(
        """(?i)(?<![A-Za-z0-9_])(username|user|login|password|passwd|pass|pwd|token|access_token|auth_token|""" +
            """play_token|refresh_token|id_token|auth|key|api_key|apikey|signature|sig|hash|session|sessionid|""" +
            """jwt|mac|sn|serial|device_id|data)=([^&\s"'#<>]+)""",
    )

    /** `Authorization: ...`, `Cookie: ...` and similar header values, as mpv or OkHttp print them. */
    private val HEADERS = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-auth-token|x-api-key)(\s*[:=]\s*)[^\r\n]+""",
    )

    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{6,}""")

    private val MAC_ADDRESS = Regex("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}[:\-]){5}[0-9a-f]{2}(?![0-9a-f:])""")

    /** [text] with every credential-shaped part replaced by [MASK]. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = HEADERS.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        s = BEARER.replace(s) { "${it.groupValues[1]} $MASK" }
        s = USERINFO.replace(s) { "${it.groupValues[1]}$MASK@" }
        s = XTREAM_TYPED.replace(s) { "${it.groupValues[1]}$MASK/$MASK/" }
        s = XTREAM_SHORT.replace(s) { "${it.groupValues[1]}$MASK/$MASK/${it.groupValues[2]}" }
        s = PARAMS.replace(s) { "${it.groupValues[1]}=$MASK" }
        s = MAC_ADDRESS.replace(s, MASK)
        return s
    }

    /** Null-safe [redact]. */
    fun redactOrNull(text: String?): String? = text?.let(::redact)

    /** A throwable's stack trace, every message in its cause chain redacted (frames carry no credentials). */
    fun stackTrace(t: Throwable): String {
        val w = StringWriter()
        PrintWriter(w).use { t.printStackTrace(it) }
        return redact(w.toString())
    }

    /** `ClassName: message` for each throwable in the cause chain (at most [depth]), redacted. */
    fun describe(t: Throwable, depth: Int = 4): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        while (cur != null && parts.size < depth) {
            parts += "${cur.javaClass.simpleName}: ${cur.message.orEmpty()}"
            cur = cur.cause.takeIf { it !== cur }
        }
        return redact(parts.joinToString(" <- "))
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
