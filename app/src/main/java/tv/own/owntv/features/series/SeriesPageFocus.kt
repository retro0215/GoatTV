package tv.own.owntv.features.series

/**
 * Where focus lands on a series page that has just opened (or switched version).
 *
 * The page asks for focus 100 ms after it opens, but the Play button only exists once the episodes
 * and progress have loaded. Until then the first button is Play Trailer, so a version switch (whose
 * episodes load after the page has already asked) left focus there (4.6.1 RC3 on the onn).
 *
 * The page now keeps asking for Play until it gets it, but only until the customer presses a key:
 * once they have moved, the page never pulls focus away from them.
 */
class PlayFocusOnArrival {
    var armed: Boolean = true
        private set

    /** Any key the page receives: the customer has taken over, leave focus where they put it. */
    fun onUserKey() {
        armed = false
    }

    /** True once, when Play has appeared and nobody has pressed a key yet. */
    fun takePlayFocus(playShown: Boolean): Boolean {
        if (!armed || !playShown) return false
        armed = false
        return true
    }
}

/**
 * Leaving a series page shows the catalog again, built from scratch; nothing took focus, so it fell
 * to the top bar's Search chip. Back from the page now asks the catalog to put it on that show's
 * card, or, when the show is not in the list on screen, on the first card.
 */
object SeriesGridReturn {
    /** The card to focus: the show's index if it is listed, else the first card. */
    fun target(indexOfShow: Int): Int = if (indexOfShow >= 0) indexOfShow else 0
}
