package tv.own.owntv.features.favorites

import tv.own.owntv.features.shell.MainSection

/**
 * The shell's return paths into Favorites and Search (navigation Phases B/C), pure so they are testable.
 *
 * - Live from Favorites: the player opens with `fullscreenReturnSection = FAVORITES` (the Sports pattern)
 *   and the shell's existing exit brings the customer back; [openedPlayer] only marks the tab to keep.
 * - Movies/Series details opened from Favorites or Search: the section's own details page (the Home
 *   pattern). When it closes with its own Back while still in that section and nothing is playing, the
 *   shell goes back to [FavoritesReturnState.returnTo].
 * - Entering Favorites from anywhere else starts on the Live tab; a return keeps the tab. Search keeps its
 *   own query and tab in its ViewModel, so a return to it simply shows the same results.
 */
data class FavoritesReturnState(
    val keepTab: Boolean = false,
    val detailsFrom: MainSection? = null,
    val detailsSeen: Boolean = false,
    val returnTo: MainSection = MainSection.FAVORITES,
)

object FavoritesReturn {

    /** Result of the selected section changing: the new state, and whether Favorites' tab resets to Live. */
    data class SectionChange(val state: FavoritesReturnState, val resetTab: Boolean)

    fun openedPlayer(s: FavoritesReturnState): FavoritesReturnState = s.copy(keepTab = true)

    fun openedDetails(
        s: FavoritesReturnState,
        from: MainSection,
        returnTo: MainSection = MainSection.FAVORITES,
    ): FavoritesReturnState = s.copy(
        keepTab = returnTo == MainSection.FAVORITES,
        detailsFrom = from,
        detailsSeen = false,
        returnTo = returnTo,
    )

    fun onSectionChanged(s: FavoritesReturnState, section: MainSection): SectionChange = when (section) {
        MainSection.FAVORITES -> SectionChange(FavoritesReturnState(), resetTab = !s.keepTab)
        MainSection.SEARCH -> SectionChange(FavoritesReturnState(), resetTab = false)
        // On the way to the player/details: keep what is pending, unless this is some other detail
        // section than the one that was opened.
        MainSection.LIVE_TV, MainSection.MOVIES, MainSection.SERIES ->
            if (s.detailsFrom != null && s.detailsFrom != section) SectionChange(s.copy(detailsFrom = null, detailsSeen = false), false)
            else SectionChange(s, false)
        // Anywhere else: the customer has left the flow.
        else -> SectionChange(FavoritesReturnState(), false)
    }

    /**
     * The details page's open state changed (or the section / player did). Returns the new state and the
     * section to go back to now, or null to stay.
     */
    fun onDetails(
        s: FavoritesReturnState,
        detailsOpen: Boolean,
        section: MainSection,
        playerIdle: Boolean,
    ): Pair<FavoritesReturnState, MainSection?> {
        if (s.detailsFrom == null) return s to null
        if (detailsOpen) return s.copy(detailsSeen = true) to null
        return if (s.detailsSeen && section == s.detailsFrom && playerIdle) {
            s.copy(detailsFrom = null, detailsSeen = false) to s.returnTo
        } else s to null
    }
}
