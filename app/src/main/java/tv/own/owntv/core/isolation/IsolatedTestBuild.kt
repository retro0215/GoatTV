package tv.own.owntv.core.isolation

import tv.own.owntv.BuildConfig

/**
 * The isolated side-by-side test APK (build type `tutorialTest`, application id `<brand id>.tutorialtest`).
 *
 * That build already cannot reach the production Supabase project (its SUPABASE_URL / key are staging,
 * or a host that never resolves) and carries no Pushwoosh/Firebase code. On top of that, every path
 * that writes somewhere outside the device refuses here, so isolation does not depend on the tester:
 * channel-catalog upload, anonymous TV sign-in, phone pairing, Social Rooms messages/reactions,
 * launcher channel / Watch Next publishing and the in-app updater. False in every customer build.
 */
object IsolatedTestBuild {
    val active: Boolean get() = BuildConfig.ISOLATED_TEST_BUILD

    /** Log / error text for refused operations (never shown as UI copy). */
    const val REFUSED = "disabled in the isolated test build"
}
