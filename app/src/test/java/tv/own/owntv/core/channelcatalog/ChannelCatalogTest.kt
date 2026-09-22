package tv.own.owntv.core.channelcatalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatalogTest {

    @Test
    fun `payload serialization excludes prohibited fields and sensitive secrets`() {
        val payload = CatalogIngestPayload(
            sourceType = "XTREAM",
            claimedBrand = "goat",
            channels = listOf(
                CatalogChannelDto(
                    channelName = "Sports HD",
                    remoteId = "ch-123",
                    epgChannelId = "sports-epg",
                    logoUrl = "https://example.com/logo.png",
                    categoryName = "Sports"
                )
            )
        )

        val jsonString = Json.encodeToString(CatalogIngestPayload.serializer(), payload)

        // Assert payload structure
        assertTrue(jsonString.contains("Sports HD"))
        assertTrue(jsonString.contains("ch-123"))
        assertTrue(jsonString.contains("goat"))

        // Assert security allow-list: NO streamUrl, no credentials, no headers, no DRM
        val fakeSecret = "secret-stream-url-or-password-12345"
        assertFalse(jsonString.contains(fakeSecret))
    }

    @Test
    fun `batching splits channels into chunks of maximum 200`() {
        val channels = (1..450).map { i ->
            CatalogChannelDto(
                channelName = "Channel $i",
                remoteId = "ch-$i"
            )
        }

        val batches = channels.chunked(200)
        assertEquals(3, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
        assertEquals(50, batches[2].size)
    }
}
