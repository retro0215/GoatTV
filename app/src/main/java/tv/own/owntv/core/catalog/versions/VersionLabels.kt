package tv.own.owntv.core.catalog.versions

import tv.own.owntv.core.catalog.ProviderCatalogMetadataParser

/**
 * A picture format the PROVIDER claims for a version — in its title or its category name. It says what the
 * stream is labelled as, never that this TV can show it: a TV without Dolby Vision plays a "Dolby Vision"
 * file as HDR or SDR, and a mislabelled file is not HDR at all. Device checks belong to the selector.
 */
enum class AdvertisedPicture { DOLBY_VISION, HDR10_PLUS, HDR10, HDR }

/**
 * How one version is labelled: quality rank (0 unknown, 1 SD, 2 HD/720p, 3 FHD/1080p, 4 4K/UHD/2160p,
 * 5 8K), the picture format it is advertised with, and its language code when one is given.
 */
data class VersionLabel(
    val qualityRank: Int,
    val advertisedPicture: AdvertisedPicture?,
    val language: String?,
)

object VersionLabels {

    /**
     * The label of [row], from its title (parsed when the row was written) and its category name (parsed
     * here, read time — a "4K MOVIES" category marks every film in it, though no title says so). The
     * stronger of the two wins for quality and picture; the title's language wins over the category's.
     */
    fun of(row: VersionCandidateRow): VersionLabel {
        val category = row.categoryName.orEmpty()
        val rank = maxOf(row.qualityRank, if (category.isBlank()) 0 else ProviderCatalogMetadataParser.extractQualityRank(category))
        val picture = strongest(
            pictureOf(row.advertisedCapabilities?.split(CAPABILITY_SEPARATOR).orEmpty()),
            if (category.isBlank()) null else pictureOf(ProviderCatalogMetadataParser.extractCapabilities(category)),
        )
        val language = row.providerLanguage ?: categoryLanguage(category)
        return VersionLabel(rank, picture, language)
    }

    /** The language a category name states, by code ("EN | ACTION") or by name ("FRENCH MOVIES"). */
    fun categoryLanguage(name: String): String? {
        if (name.isBlank()) return null
        ProviderCatalogMetadataParser.extractLanguage(name)?.let { return it }
        // Upper-cased first: case-insensitive matching of non-ASCII letters (ñ, ç, ü, ê) differs between
        // the JVM and Android's ICU engine, so the names below are matched in upper case on both.
        val upper = name.uppercase(java.util.Locale.ROOT)
        return LANGUAGE_NAMES.entries.firstOrNull { (_, pattern) -> pattern.containsMatchIn(upper) }?.key
    }

    private fun pictureOf(capabilities: List<String>): AdvertisedPicture? {
        val names = capabilities.map { it.trim() }
        return when {
            "Dolby Vision" in names -> AdvertisedPicture.DOLBY_VISION
            "HDR10+" in names -> AdvertisedPicture.HDR10_PLUS
            "HDR10" in names -> AdvertisedPicture.HDR10
            "HDR" in names -> AdvertisedPicture.HDR
            else -> null
        }
    }

    /** The more specific of two claims (Dolby Vision over HDR10+ over HDR10 over plain HDR). */
    private fun strongest(a: AdvertisedPicture?, b: AdvertisedPicture?): AdvertisedPicture? = when {
        a == null -> b
        b == null -> a
        else -> if (a.ordinal <= b.ordinal) a else b
    }

    private const val CAPABILITY_SEPARATOR = " • "

    /** Language names providers write in category names, to the codes the title parser uses. */
    private val LANGUAGE_NAMES: Map<String, Regex> = linkedMapOf(
        "EN" to word("ENGLISH"),
        "FR" to word("FRENCH|FRANCAIS|FRANÇAIS"),
        "DE" to word("GERMAN|DEUTSCH"),
        "ES" to word("SPANISH|ESPANOL|ESPAÑOL|CASTELLANO"),
        "IT" to word("ITALIAN|ITALIANO"),
        "PT" to word("PORTUGUESE|PORTUGUES|PORTUGUÊS"),
        "NL" to word("DUTCH|NEDERLANDS"),
        "PL" to word("POLISH|POLSKI"),
        "TR" to word("TURKISH|TURKCE|TÜRKÇE"),
        "AR" to word("ARABIC"),
        "RU" to word("RUSSIAN"),
        "HI" to word("HINDI"),
    )

    /** [alternatives] (upper case) as whole words: not inside a longer word ("FRENCHIE" is not French). */
    private fun word(alternatives: String) = Regex("""(?<![\p{L}\p{N}])(?:$alternatives)(?![\p{L}\p{N}])""")
}
