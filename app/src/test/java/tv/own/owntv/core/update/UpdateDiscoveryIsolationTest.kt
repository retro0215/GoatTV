package tv.own.owntv.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verify brand-specific update isolation (Stage 1 scaffolding).
 *
 * This replicates the logic in [UpdateManager.check] and [UpdateManager.downloadAndInstall]
 * to ensure that the prefixes chosen for each brand strictly exclude each other.
 */
class UpdateDiscoveryIsolationTest {

    private data class Brand(
        val name: String,
        val tagPrefix: String,
        val apkPrefix: String
    )

    private val goat = Brand("GoatTV", "v", "GoatTV")
    private val fivestar = Brand("5Star", "5star-v", "5Star-Ultra")
    private val allaccess = Brand("AllAccess", "allaccess-v", "AllAccess")

    private val allBrands = listOf(goat, fivestar, allaccess)

    @Test
    fun `tag discovery isolation`() {
        val versionRegex = Regex("""\d+\.\d+\.\d+""")

        fun matches(brand: Brand, tag: String): Boolean {
            val brandVersion = tag.removePrefix(brand.tagPrefix)
            // Strict brand isolation: tag must start with the prefix AND the very next
            // character must be a digit.
            return tag.startsWith(brand.tagPrefix) &&
                brandVersion.firstOrNull()?.isDigit() == true &&
                brandVersion.matches(versionRegex)
        }

        // --- GoatTV ---
        assertTrue("GoatTV accepts its own tag", matches(goat, "v4.2.13"))
        assertFalse("GoatTV rejects 5Star tag", matches(goat, "5star-v4.2.18"))
        assertFalse("GoatTV rejects AllAccess tag", matches(goat, "allaccess-v1.0.0"))
        assertFalse("GoatTV rejects malformed tag", matches(goat, "v-4.2.13"))
        assertFalse("GoatTV rejects 5Star tag even if it contains 'v'", matches(goat, "5star-v4.2.18"))

        // --- 5Star ---
        assertTrue("5Star accepts its own tag", matches(fivestar, "5star-v4.2.18"))
        assertFalse("5Star rejects GoatTV tag", matches(fivestar, "v4.2.13"))
        assertFalse("5Star rejects AllAccess tag", matches(fivestar, "allaccess-v1.0.0"))

        // --- AllAccess ---
        assertTrue("AllAccess accepts its own tag", matches(allaccess, "allaccess-v1.0.0"))
        assertFalse("AllAccess rejects GoatTV tag", matches(allaccess, "v4.2.13"))
        assertFalse("AllAccess rejects 5Star tag", matches(allaccess, "5star-v4.2.18"))
    }

    @Test
    fun `APK asset selection isolation`() {
        fun selects(brand: Brand, assetName: String): Boolean {
            return assetName.endsWith(".apk") && assetName.startsWith(brand.apkPrefix, ignoreCase = true)
        }

        // --- GoatTV ---
        assertTrue("GoatTV selects its own APK", selects(goat, "GoatTV-v4.2.13.apk"))
        assertTrue("GoatTV selects its own generic APK", selects(goat, "GoatTV.apk"))
        assertFalse("GoatTV rejects 5Star APK", selects(goat, "5Star-Ultra-v4.2.18.apk"))
        assertFalse("GoatTV rejects AllAccess APK", selects(goat, "AllAccess-v1.0.0.apk"))

        // --- 5Star ---
        assertTrue("5Star selects its own APK", selects(fivestar, "5Star-Ultra-v4.2.18.apk"))
        assertTrue("5Star selects its own generic APK", selects(fivestar, "5Star-Ultra.apk"))
        assertFalse("5Star rejects GoatTV APK", selects(fivestar, "GoatTV-v4.2.13.apk"))
        assertFalse("5Star rejects AllAccess APK", selects(fivestar, "AllAccess-v1.0.0.apk"))

        // --- AllAccess ---
        assertTrue("AllAccess selects its own APK", selects(allaccess, "AllAccess-v1.0.0.apk"))
        assertTrue("AllAccess selects its own generic APK", selects(allaccess, "AllAccess.apk"))
        assertFalse("AllAccess rejects GoatTV APK", selects(allaccess, "GoatTV-v4.2.13.apk"))
        assertFalse("AllAccess rejects 5Star APK", selects(allaccess, "5Star-Ultra-v4.2.18.apk"))
    }
}
