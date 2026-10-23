package tv.own.owntv.features.tutorials

import tv.own.owntv.BuildConfig

/** Where the tutorial catalog is read from: the build's own Supabase project, or (debug only) staging. */
data class VideoTutorialsEndpoint(val baseUrl: String, val anonKey: String, val isStaging: Boolean) {
    /** Cache namespace: a staging catalog is never shown by a production lookup, and vice versa. */
    val cacheScope: String get() = if (isStaging) "staging" else "production"
}

/**
 * Endpoint selection. Release builds always use the build's production Supabase settings
 * (BuildConfig.SUPABASE_URL / SUPABASE_ANON_KEY): the staging fields are empty strings in every release
 * variant (app/build.gradle.kts), and [stagingSwitch] is only installed by the debug source set.
 *
 * A debug build reads staging only when ALL of these hold: it is a debug build, the adb switch in the
 * debug source set is on, both staging fields were supplied at build time, the staging URL is a
 * Supabase project URL that differs from the production one, and the key is not a secret key.
 */
object VideoTutorialsEndpoints {
    /** Installed by `VideoTutorialsStagingInstaller` (debug manifest only); null in release APKs. */
    @Volatile
    var stagingSwitch: (() -> Boolean)? = null

    fun current(): VideoTutorialsEndpoint = resolve(
        isDebugBuild = BuildConfig.DEBUG,
        stagingRequested = runCatching { stagingSwitch?.invoke() == true }.getOrDefault(false),
        stagingUrl = BuildConfig.TUTORIALS_STAGING_URL,
        stagingKey = BuildConfig.TUTORIALS_STAGING_ANON_KEY,
        productionUrl = BuildConfig.SUPABASE_URL,
        productionKey = BuildConfig.SUPABASE_ANON_KEY,
        isolatedTestBuild = BuildConfig.ISOLATED_TEST_BUILD,
    )

    /** Where the isolated test APK points when it was built without staging values: never resolves. */
    internal const val UNREACHABLE_URL = "https://isolated-test-build.invalid"

    internal fun resolve(
        isDebugBuild: Boolean,
        stagingRequested: Boolean,
        stagingUrl: String,
        stagingKey: String,
        productionUrl: String,
        productionKey: String,
        isolatedTestBuild: Boolean = false,
    ): VideoTutorialsEndpoint {
        // The isolated test APK reads staging only (no switch needed) and has no production fallback:
        // without valid staging values it points at a host that never resolves ("couldn't load").
        if (isolatedTestBuild) {
            val usable = projectRef(stagingUrl) != null && stagingKey.isNotBlank() && !stagingKey.startsWith(SECRET_KEY_PREFIX)
            return if (usable) VideoTutorialsEndpoint(stagingUrl.trimEnd('/'), stagingKey, isStaging = true)
            else VideoTutorialsEndpoint(UNREACHABLE_URL, "", isStaging = true)
        }
        val production = VideoTutorialsEndpoint(productionUrl.trimEnd('/'), productionKey, isStaging = false)
        if (!isDebugBuild || !stagingRequested) return production
        return if (isUsableStaging(stagingUrl, stagingKey, productionUrl)) {
            VideoTutorialsEndpoint(stagingUrl.trimEnd('/'), stagingKey, isStaging = true)
        } else {
            production
        }
    }

    internal fun isUsableStaging(stagingUrl: String, stagingKey: String, productionUrl: String): Boolean {
        val ref = projectRef(stagingUrl) ?: return false
        if (ref == projectRef(productionUrl)) return false
        if (stagingKey.isBlank() || stagingKey.startsWith(SECRET_KEY_PREFIX)) return false
        return true
    }

    /** `https://<20-char ref>.supabase.co` → ref; anything else → null. */
    internal fun projectRef(url: String): String? = PROJECT_URL.matchEntire(url.trim().trimEnd('/'))?.groupValues?.get(1)

    private val PROJECT_URL = Regex("^https://([a-z0-9]{20})[.]supabase[.]co$")
    private const val SECRET_KEY_PREFIX = "sb_secret_"
}
