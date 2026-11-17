package tv.own.owntv.features.favorites

import tv.own.owntv.features.shell.MainSection

/**
 * The shell's return paths into Favorites (navigation Phase B), pure so they are testable.
 *
 * - Live: the player opens with `fullscreenReturnSection = FAVORITES` (the Sports pattern) and the shell's
 *   existing exit brings the customer back; [openedPlayer] only marks that the tab should be kept.
 * - Movies/Series: the section's own details page opens (the Home pattern). When that page closes with
 *   its own Back while still in that section and nothing is playing, the shell returns to Favorites.
 * - Entering Favorites from anywhere else starts on the Live tab; a return keeps the tab.
 */
data class FavoritesReturnState(
    val keepTab: Boolean = false,
    val detailsFrom: MainSection? = null,
    val detailsSeen: Boolean = false,
)

object FavoritesReturn {

    /** Result of the selected section changing: the new state, and whether the tab resets to Live. */
    data class SectionChange(val state: FavoritesReturnState, val resetTab: Boolean)

    fun openedPlayer(s: FavoritesReturnState): FavoritesReturnState = s.copy(keepTab = true)

    fun openedDetails(s: FavoritesReturnState, from: MainSection): FavoritesReturnState =
        s.copy(keepTab = true, detailsFrom = from, detailsSeen = false)

    fun onSectionChanged(s: FavoritesReturnState, section: MainSection): SectionChange = when (section) {
        MainSection.FAVORITES -> SectionChange(FavoritesReturnState(), resetTab = !s.keepTab)
        // On the way to Favorites' own player/details: keep what is pending, unless this is some other
        // detail section than the one Favorites opened.
        MainSection.LIVE_TV, MainSection.MOVIES, MainSection.SERIES ->
            if (s.detailsFrom != null && s.detailsFrom != section) SectionChange(s.copy(detailsFrom = null, detailsSeen = false), false)
            else SectionChange(s, false)
        // Anywhere else: the customer has left Favorites' flow.
        else -> SectionChange(FavoritesReturnState(), false)
    }

    /**
     * The details page's open state changed (or the section / player did). Returns the new state and
     * whether to go back to Favorites now.
     */
    fun onDetails(
        s: FavoritesReturnState,
        detailsOpen: Boolean,
        section: MainSection,
        playerIdle: Boolean,
    ): Pair<FavoritesReturnState, Boolean> {
        if (s.detailsFrom == null) return s to false
        if (detailsOpen) return s.copy(detailsSeen = true) to false
        return if (s.detailsSeen && section == s.detailsFrom && playerIdle) {
            s.copy(detailsFrom = null, detailsSeen = false) to true
        } else s to false
    }
}
