package tv.own.owntv.rooms

import tv.own.owntv.BuildConfig

object BrandResolver {
    /**
     * The backend brand id. The build's brand flavor decides it (`goat`, `allaccess`, `fivestar`,
     * `supreme`), so an application id suffix — the isolated `<id>.tutorialtest` APK — keeps its real
     * brand instead of falling back to `goat`. The application-id table remains as the fallback for a
     * build without a known brand flavor; for the four customer builds both give the same answer.
     */
    fun resolveBrandId(
        applicationId: String = BuildConfig.APPLICATION_ID,
        flavorBrand: String = BuildConfig.FLAVOR_brand,
    ): String {
        if (flavorBrand in BRANDS) return flavorBrand
        return when (applicationId.removeSuffix(TEST_SUFFIX)) {
            "tv.own.owntv" -> "goat"
            "tv.allaccess.app" -> "allaccess"
            "tv.fivestar.ultra" -> "fivestar"
            "tv.supreme.app" -> "supreme"
            else -> "goat"
        }
    }

    private val BRANDS = setOf("goat", "allaccess", "fivestar", "supreme")
    private const val TEST_SUFFIX = ".tutorialtest"
}
