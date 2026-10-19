package tv.own.owntv.core.epg

import tv.own.owntv.core.network.HttpClient

/**
 * How an EPG address is shown and edited without putting the account on screen.
 *
 * An Xtream guide address carries the account's user name and password (`xmltv.php?username=…&password=…`),
 * so Settings shows it through [display]. The edit form starts from that masked text, and [restore] puts the
 * stored credentials back into every masked part the user left alone before saving: changing the host keeps
 * the account, typing a new password replaces it, and a mask is never saved in place of a credential.
 */
object EpgUrlMask {

    /** The address as Settings may show it. */
    fun display(url: String): String = HttpClient.redactUrl(url)

    /** [edited] (which started as [display] of [original]) with each untouched mask refilled from [original]. */
    fun restore(edited: String, original: String): String {
        if (original.isEmpty()) return edited
        if (edited == display(original)) return original
        var out = edited
        // Query values (`username=***`), matched by parameter name and order of appearance.
        val stored = HashMap<String, ArrayDeque<String>>()
        QUERY.findAll(original).forEach { m -> stored.getOrPut(m.groupValues[1].lowercase()) { ArrayDeque() }.addLast(m.groupValues[2]) }
        out = QUERY_MASKED.replace(out) { m ->
            val value = stored[m.groupValues[1].lowercase()]?.removeFirstOrNull()
            if (value == null) m.value else "${m.groupValues[1]}=${value}"
        }
        // Xtream path credentials (`/live/•••/•••/`).
        PATH.find(original)?.let { p ->
            out = PATH_MASKED.replace(out) { m -> "${m.groupValues[1]}${p.groupValues[2]}/${p.groupValues[3]}/" }
        }
        // `scheme://user:pass@host`.
        USERINFO.find(original)?.let { u ->
            out = USERINFO_MASKED.replace(out) { "://${u.groupValues[1]}@" }
        }
        return out
    }

    // Same parameter list and patterns as HttpClient.redactUrl.
    private const val NAMES = "username|password|user|pass|token|data|auth|signature|sig|key"
    private val QUERY = Regex("(?i)($NAMES)=([^&]*)")
    private val QUERY_MASKED = Regex("(?i)($NAMES)=\\*\\*\\*(?=&|$|#)")
    private val PATH = Regex("(?i)(://[^/]+/(?:live|movie|series|vod|timeshift)/)([^/]+)/([^/]+)/")
    private val PATH_MASKED = Regex("(?i)(://[^/]+/(?:live|movie|series|vod|timeshift)/)•••/•••/")
    private val USERINFO = Regex("://([^/@:]+(?::[^/@]*)?)@")
    private val USERINFO_MASKED = Regex("://\\*\\*\\*@")
}
