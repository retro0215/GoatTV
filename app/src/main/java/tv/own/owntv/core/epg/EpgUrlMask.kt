package tv.own.owntv.core.epg

import tv.own.owntv.core.network.HttpClient

/**
 * How an EPG address is shown and edited without putting the account on screen.
 *
 * An Xtream guide address carries the account's user name and password (`xmltv.php?username=…&password=…`).
 * Lists show it through [display]. The edit form starts from [editable], where every credential reads
 * [PLACEHOLDER], and [restore] turns the edited text back into a real address before it is saved:
 *
 * - a credential that still contains any [MASK_CHAR] — untouched, or only partly deleted or typed into —
 *   is the stored value, so a placeholder is never saved as a credential;
 * - a credential typed without any [MASK_CHAR] (after clearing the placeholder) replaces the stored one;
 * - everything else (host, port, path, other parameters) is saved exactly as edited.
 *
 * [MASK_CHAR] is a bullet, not `*`: it is not on a TV keyboard and never valid unencoded in a URL, so a real
 * password can never be mistaken for a leftover mask.
 */
object EpgUrlMask {

    const val MASK_CHAR = '•'
    const val PLACEHOLDER = "•••"

    /** The address as a list may show it (credentials as `***`). */
    fun display(url: String): String = HttpClient.redactUrl(url)

    /** The address as the edit field starts out: every credential replaced by [PLACEHOLDER]. */
    fun editable(url: String): String {
        var out = QUERY.replace(url) { m -> "${m.groupValues[1]}${m.groupValues[2]}=$PLACEHOLDER" }
        out = PATH.replace(out) { m -> "${m.groupValues[1]}$PLACEHOLDER/$PLACEHOLDER/" }
        out = USERINFO.replace(out) { "://$PLACEHOLDER@" }
        return out
    }

    /** [edited] (which started as [editable] of [original]) with every masked credential restored from [original]. */
    fun restore(edited: String, original: String): String {
        if (MASK_CHAR !in edited) return edited
        // Query credentials: matched by parameter name, then by order among that name's occurrences.
        val stored = HashMap<String, ArrayDeque<String>>()
        QUERY.findAll(original).forEach { m -> stored.getOrPut(m.groupValues[2].lowercase()) { ArrayDeque() }.addLast(m.groupValues[3]) }
        var out = QUERY.replace(edited) { m ->
            val value = m.groupValues[3]
            val storedValue = stored[m.groupValues[2].lowercase()]?.removeFirstOrNull()
            when {
                MASK_CHAR !in value -> m.value
                storedValue != null -> "${m.groupValues[1]}${m.groupValues[2]}=$storedValue"
                else -> "${m.groupValues[1]}${m.groupValues[2]}="
            }
        }
        // Xtream path credentials (`/live/<user>/<pass>/`), segment by segment.
        val storedPath = PATH.find(original)
        out = PATH.replace(out) { m ->
            fun seg(i: Int) = m.groupValues[i].let { v -> if (MASK_CHAR !in v) v else storedPath?.groupValues?.get(i) ?: "" }
            "${m.groupValues[1]}${seg(2)}/${seg(3)}/"
        }
        // `scheme://user:pass@host`.
        val storedUserInfo = USERINFO.find(original)?.groupValues?.get(1)
        out = USERINFO.replace(out) { m ->
            val v = m.groupValues[1]
            when {
                MASK_CHAR !in v -> m.value
                storedUserInfo != null -> "://$storedUserInfo@"
                else -> "://"
            }
        }
        // Anything masked that no rule above recognised (e.g. a renamed parameter) is dropped, never saved.
        return out.replace(MASK_CHAR.toString(), "")
    }

    // Same parameter names as HttpClient.redactUrl; group 1 keeps the separator so `user` never matches the
    // tail of `username`.
    private const val NAMES = "username|password|user|pass|token|data|auth|signature|sig|key"
    private val QUERY = Regex("(?i)([?&;])($NAMES)=([^&#]*)")
    private val PATH = Regex("(?i)(://[^/]+/(?:live|movie|series|vod|timeshift)/)([^/]+)/([^/]+)/")
    private val USERINFO = Regex("://([^/@?#]+)@")
}
